# -*- coding: utf-8 -*-
"""边缘限流层（Nginx）端到端验收 + 双层联动验证（支持网关限流的 redis / memory 两套实现）。

口径：先验**交付配置本身**（`nginx -t` 语法校验），再起真实 nginx 进程打真实 HTTP，
验证边缘层真的泄洪：突发额度内放行、超额 429 且响应体是统一结构、
反向代理头（X-Request-Id / X-Forwarded-For）确实透传到上游。
最后做**双层联动**：真实 nginx 前置真实网关，验证「边缘透传 XFF + 网关登记可信代理网段」
这条前提成立——同客户端被网关层拒绝、换客户端独立放行，且拒绝来自网关层而非边缘层。

双层联动里的网关层限流由**模式开关**决定（网关有两套限流实现，一个开关二选一）：
  · mode=redis（默认）：分布式令牌桶，配置前缀 retail.gateway.rate-limit.*，
    环境变量 RETAIL_GATEWAY_RATE_LIMIT_*；桶在 Redis（Lua 原子扣减），
    Redis 不可达时网关 fail-open 放行 → 本脚本会跳过双层联动并说明原因；
    429 响应体的 details 只有 path（无 layer、无 policy）。
  · mode=memory：进程内令牌桶，配置前缀 retail.gateway.rate-limit.memory.*，
    环境变量 RETAIL_GATEWAY_RATE_LIMIT_MEMORY_*；桶在网关进程内存；
    429 响应体的 details 含 policy（命中的路由段）。
  两种模式下**边缘层拒绝**的判定都一样：响应体 details.layer=edge；
  **网关层拒绝**的判定按模式分支：redis 看 details.path，memory 看 details.policy，
  两者都不含 details.layer=edge —— 这正是两层可区分的依据。

运行形态（两种，均为**真实 nginx**，不是模拟）：
  · Docker 模式（默认优先）：用本地镜像 `nginx:latest` 起容器，`--network host` 与宿主同网络命名空间，
    交付配置原样挂载进 `/etc/nginx/nginx.conf` → **不需要在机器上装 nginx**，镜像在本地即可离线跑；
  · 宿主模式：直接用宿主 nginx 可执行文件（`NGINX_BIN` 可指定路径）。
  `EDGE_NGINX_MODE=auto|docker|host` 控制选择，默认 `auto`（有本地 nginx 镜像就用 Docker，否则回落宿主）。

前置：Docker 模式需要本地已有 nginx 镜像（`docker image inspect nginx:latest` 通过）；
      宿主模式需要 nginx 运行时（本机无 nginx 时请在 Linux / WSL 侧执行；Windows 侧可用
      `wsl -u root python3 scripts/smoke_ratelimit_edge.py`）。
      双层联动还需要网关 jar（`mvn -B -o -pl gateway -am package`），缺失则跳过该段。
用法：
    python scripts/smoke_ratelimit_edge.py
    EDGE_NGINX_MODE=host NGINX_BIN=/usr/sbin/nginx python scripts/smoke_ratelimit_edge.py
    EDGE_NGINX_MODE=docker NGINX_IMAGE=nginx:latest python scripts/smoke_ratelimit_edge.py
    python scripts/smoke_ratelimit_edge.py --mode memory
    SMOKE_RATELIMIT_MODE=memory python scripts/smoke_ratelimit_edge.py
退出码：0 = 全部通过；1 = 有断言失败；2 = 环境不具备（无 nginx 镜像 / 无 nginx 可执行文件等）。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONF = ROOT / "nginx" / "nginx.conf"
JAR = ROOT / "gateway" / "target" / "retail-api-gateway-1.0.0-SNAPSHOT.jar"
TEMPLATE = ROOT / "scripts" / "smoke_ratelimit_application.yml"
NGINX = os.environ.get("NGINX_BIN") or shutil.which("nginx") or "/usr/sbin/nginx"
NGINX_IMAGE = os.environ.get("NGINX_IMAGE", "nginx:latest")
MODE = (os.environ.get("EDGE_NGINX_MODE", "auto") or "auto").strip().lower()

MODE_REDIS = "redis"
MODE_MEMORY = "memory"

# redis 模式限流态所在 Redis（与网关生产配置同口径）
REDIS_HOST = os.environ.get("REDIS_HOST", "localhost")
REDIS_PORT = int(os.environ.get("REDIS_PORT", "6382"))

# 同机部署：边缘 Nginx 与网关都在 127.0.0.1，网关须采信 Nginx 转发的 XFF
TRUSTED_PROXY = "127.0.0.1/32"

# 模板占位符识别（渲染后必须一个不剩，否则说明模板与脚本漂移）
PLACEHOLDER = re.compile(r"__[A-Z0-9_]+__")

PASS = 0
FAIL = []


def check(name, cond, detail=""):
    global PASS
    if cond:
        PASS += 1
        print("PASS  {}".format(name))
    else:
        FAIL.append(name)
        print("FAIL  {}  {}".format(name, detail))


def info(message):
    """口径说明型输出：不计入 PASS / FAIL。"""
    print("INFO  {}".format(message))


def parse_json(text):
    try:
        data = json.loads(text)
        return data if isinstance(data, dict) else None
    except Exception:
        return None


def free_port():
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


def req(url, headers=None):
    request = urllib.request.Request(url, method="GET")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=10) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")
    except Exception as exc:
        return 0, str(exc)


def redis_reachable(host, port, timeout=2.0):
    """redis 模式的前置检查：Redis 不可达时网关会 fail-open 放行，限流无从验证。"""
    try:
        with socket.create_connection((host, int(port)), timeout=timeout):
            return True
    except OSError:
        return False


class EchoHandler(BaseHTTPRequestHandler):
    """桩上游：回显收到的请求头，用来证明反向代理头确实透传。"""

    protocol_version = "HTTP/1.0"

    def do_GET(self):
        payload = {
            "service": "edge-stub-upstream",
            "status": "UP",
            "path": self.path,
            "xRequestId": self.headers.get("X-Request-Id"),
            "xForwardedFor": self.headers.get("X-Forwarded-For"),
            "xRealIp": self.headers.get("X-Real-IP"),
        }
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def start_stub(port):
    httpd = ThreadingHTTPServer(("127.0.0.1", port), EchoHandler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


# ---------------------------------------------------------------------------
# nginx 运行形态：Docker 容器 / 宿主进程
# ---------------------------------------------------------------------------

def docker_cli():
    return shutil.which("docker")


def docker_image_present(image):
    cli = docker_cli()
    if not cli:
        return False
    proc = subprocess.run([cli, "image", "inspect", image], capture_output=True, text=True)
    return proc.returncode == 0


def pick_mode():
    """auto：优先 Docker（镜像本地即可离线跑，不改宿主环境），否则回落宿主 nginx。"""
    if MODE in ("docker", "host"):
        return MODE
    if docker_image_present(NGINX_IMAGE):
        return "docker"
    if Path(NGINX).exists():
        return "host"
    return None


def check_conf(mode):
    """语法校验交付配置本身（验的是交付物，不是临时改写版）。"""
    if mode == "docker":
        proc = subprocess.run(
            [docker_cli(), "run", "--rm", "-v", "{}:/etc/nginx/nginx.conf:ro".format(CONF),
             NGINX_IMAGE, "nginx", "-t"],
            capture_output=True, text=True)
        return proc.returncode, (proc.stderr or "")[-400:]
    proc = subprocess.run([NGINX, "-t", "-c", str(CONF)], capture_output=True, text=True)
    return proc.returncode, (proc.stderr or "")[-400:]


def patch_conf(stub_port, edge_port, rate, burst, daemon_off=True):
    """按需改写交付配置：上游指向桩、端口换空闲、额度调小（仅用于测试，不改交付物）。"""
    conf = CONF.read_text(encoding="utf-8")
    conf = conf.replace("server 127.0.0.1:18081;", "server 127.0.0.1:{};".format(stub_port))
    conf = conf.replace("server 127.0.0.1:18091;", "server 127.0.0.1:{};".format(stub_port))
    conf = conf.replace("listen      18076;", "listen      {};".format(edge_port))
    conf = conf.replace("listen      18077;", "listen      {};".format(free_port()))
    conf = conf.replace("rate=50r/s;", "rate={}r/s;".format(rate))
    conf = conf.replace("rate=20r/s;", "rate={}r/s;".format(rate))
    conf = conf.replace("burst=100 nodelay;", "burst={} nodelay;".format(burst))
    conf = conf.replace("burst=50 nodelay;", "burst={} nodelay;".format(burst))
    # 宿主模式需要前台常驻；Docker 模式由镜像入口自带 `-g "daemon off;"`，
    # 配置里再写一次会触发 nginx "daemon directive is duplicate" 致命错误。
    return ("daemon off;\n" + conf) if daemon_off else conf


class HostNginx:
    """宿主 nginx 进程形态。"""

    label = "宿主 nginx"

    def __init__(self, conf_text, tmp, name):
        path = tmp / name
        path.write_text(conf_text, encoding="utf-8")
        # 每个实例独立 pid 文件：否则第二个实例会因共用系统 pid 文件被判「已在运行」而退出
        pid_file = tmp / (name + ".pid")
        self.err_path = tmp / (name + ".stderr")
        err_file = open(self.err_path, "wb")
        self.proc = subprocess.Popen([NGINX, "-p", str(tmp), "-c", str(path), "-g", "pid {};".format(pid_file)],
                                     stdout=err_file, stderr=err_file)
        err_file.close()

    def alive(self):
        return self.proc.poll() is None

    def tail(self):
        try:
            return self.err_path.read_text(errors="replace")[-300:] if self.err_path.exists() else ""
        except Exception:
            return ""

    def stop(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()


class DockerNginx:
    """Docker 容器形态：--network host 与宿主同网络命名空间，交付配置原样挂载。

    不加 `daemon off;`（镜像入口已带 `-g "daemon off;"`）；端口直接落在宿主命名空间，
    上游 127.0.0.1 指向宿主上的桩 / 网关进程，语义与同机部署一致。
    """

    label = "Docker nginx"

    def __init__(self, conf_text, tmp, name):
        path = tmp / name
        path.write_text(conf_text, encoding="utf-8")
        self.cname = "rf-edge-" + re.sub(r"\W+", "-", name)
        cmd = [docker_cli(), "run", "-d", "--rm", "--network", "host",
               "-v", "{}:/etc/nginx/nginx.conf:ro".format(path),
               "--name", self.cname, NGINX_IMAGE]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        self.cid = (proc.stdout or "").strip()
        if proc.returncode != 0:
            raise RuntimeError("docker run 失败 rc={} stderr={}".format(proc.returncode, (proc.stderr or "")[-300:]))

    def alive(self):
        proc = subprocess.run([docker_cli(), "inspect", "-f", "{{.State.Running}}", self.cid],
                              capture_output=True, text=True)
        return proc.stdout.strip() == "true"

    def tail(self):
        proc = subprocess.run([docker_cli(), "logs", "--tail", "30", self.cid], capture_output=True, text=True)
        return ((proc.stdout or "") + (proc.stderr or ""))[-300:]

    def stop(self):
        subprocess.run([docker_cli(), "rm", "-f", self.cid], capture_output=True)


def start_nginx(mode, conf_text, tmp, name):
    if mode == "docker":
        return DockerNginx(conf_text, tmp, name)
    return HostNginx(conf_text, tmp, name)


def wait_http(url, runner, tries=40, interval=0.25):
    """等待 URL 返回 200；返回 (是否就绪, 最后一次状态码与响应体片段)。"""
    last = (0, "")
    for _ in range(tries):
        if not runner.alive():
            return False, last
        code, body = req(url)
        last = (code, body[:200])
        if code == 200:
            return True, last
        time.sleep(interval)
    return False, last


def render_gateway_config(gw_port, gw_stub_port, mode):
    """渲染双层联动用的网关配置：复用 scripts/smoke_ratelimit_application.yml 模板，
    两套限流实现的键都写在模板里，按模式填各自那一套的占位符。"""
    text = TEMPLATE.read_text(encoding="utf-8")
    mapping = {
        "__GATEWAY_PORT__": str(gw_port),
        "__STUB_URI__": "http://127.0.0.1:{}".format(gw_stub_port),
        "__REDIS_HOST__": REDIS_HOST,
        "__REDIS_PORT__": str(REDIS_PORT),
        "__RATE_LIMIT_MODE__": mode,
        # 两套实现都登记边缘 Nginx 所在网段为可信代理，否则网关看到的是 Nginx 自己
        "__REDIS_TRUSTED__": TRUSTED_PROXY,
        "__MEMORY_TRUSTED__": TRUSTED_PROXY,
        # ---- redis 实现：业务段桶容量 1（第二个请求即被网关层拒绝）----
        "__REDIS_ENABLED__": "true",
        "__REDIS_FAIL_OPEN__": "true",
        "__REDIS_KEY_BY__": "auth_or_ip",
        "__REDIS_KEY_PREFIX__": "rf:rl:smoke:edge:redis:",
        "__REDIS_HEALTH_REPLENISH__": "50",
        "__REDIS_HEALTH_BURST__": "50",
        "__REDIS_STRICT_REPLENISH__": "1",
        "__REDIS_STRICT_BURST__": "2",
        "__REDIS_REPLENISH__": "1",
        "__REDIS_BURST__": "1",
        # ---- memory 实现：业务段桶容量 1 ----
        "__MEMORY_ENABLED__": "true",
        "__MEMORY_MAX_TRACKED_KEYS__": "50000",
        "__MEMORY_IDLE_SECONDS__": "600",
        "__MEMORY_DEFAULT_CAPACITY__": "1",
        "__MEMORY_DEFAULT_REFILL__": "0.05",
        "__MEMORY_HEALTH_CAPACITY__": "50",
        "__MEMORY_HEALTH_REFILL__": "50",
        "__MEMORY_STRICT_CAPACITY__": "2",
        "__MEMORY_STRICT_REFILL__": "0.05",
        "__MEMORY_CAPACITY__": "1",
        "__MEMORY_REFILL__": "0.05",
    }
    for key, value in mapping.items():
        text = text.replace(key, value)
    leftover = sorted(set(PLACEHOLDER.findall(text)))
    if leftover:
        raise RuntimeError("网关配置模板占位符未替换：{}".format(leftover))
    return text


def check_gateway_layer_reject(mode, body):
    """双层联动的核心断言：这次的 429 来自**网关层**而不是边缘层。

    边缘层拒绝：details.layer=edge；
    网关层拒绝：details 不含 layer=edge，且按模式带 path（redis）/ policy（memory）。
    """
    payload = parse_json(body)
    details = (payload or {}).get("details") or {}
    check("双层联动：拒绝来自网关层（错误码 RF_GATEWAY_001 且 details.layer 不为 edge）",
          bool(payload) and payload.get("code") == "RF_GATEWAY_001"
          and details.get("layer") != "edge",
          "body={}".format(body[:220]))
    if mode == MODE_REDIS:
        check("双层联动：网关层拒绝体 details 带 path（redis 口径）",
              bool(details.get("path")), "details={}".format(details))
    else:
        check("双层联动：网关层拒绝体 details 带 policy（memory 口径）",
              bool(details.get("policy")), "details={}".format(details))


def dual_layer(tmp, gw_mode, run_mode):
    """双层联动：真实 nginx 前置真实网关。"""
    if not JAR.is_file():
        print("SKIP  双层联动（网关 jar 不存在，先跑 mvn -B -o -pl gateway -am package）")
        return
    if gw_mode == MODE_REDIS and not redis_reachable(REDIS_HOST, REDIS_PORT):
        print("SKIP  双层联动（redis 模式需要 Redis 可达（{}:{}）；Redis 不可达时网关 "
              "fail-open 放行，「网关层拒绝」无从验证）".format(REDIS_HOST, REDIS_PORT))
        return
    gw_port = free_port()
    gw_stub_port = free_port()
    gw_stub = start_stub(gw_stub_port)
    gw_conf = tmp / "gateway.yml"
    gw_conf.write_text(render_gateway_config(gw_port, gw_stub_port, gw_mode), encoding="utf-8")
    gw_env = dict(os.environ)
    # 模式开关同时走环境变量（优先级高于配置文件），杜绝「配置文件口径不一致」导致跑错实现
    gw_env["RETAIL_GATEWAY_RATE_LIMIT_MODE"] = gw_mode
    gw = subprocess.Popen(["java", "-jar", str(JAR), "--spring.config.location=file:" + gw_conf.as_posix()],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=gw_env)
    nginx = None
    try:
        gw_ready, gw_last = wait_http("http://127.0.0.1:{}/api/health".format(gw_port), _ProcRunner(gw), tries=120, interval=0.5)
        check("双层联动：网关实例启动就绪", gw_ready, "网关未就绪 last={}".format(gw_last))
        if not gw_ready:
            return
        edge_port = free_port()
        nginx = start_nginx(run_mode, patch_conf(gw_port, edge_port, 1000, 1000, daemon_off=(run_mode == "host")),
                             tmp, "nginx-dual.conf")
        base = "http://127.0.0.1:{}".format(edge_port)
        edge_ready, edge_last = wait_http(base + "/api/health", nginx)
        detail = "边缘层未就绪 last={}".format(edge_last)
        tail = nginx.tail()
        if tail:
            detail += " nginx 输出=" + tail
        check("双层联动：边缘层启动并可服务", edge_ready, detail)
        if not edge_ready:
            return
        client_a = "203.0.113.9"
        client_b = "203.0.113.77"
        code1, _b1 = req(base + "/api/plain/x", {"X-Forwarded-For": client_a})
        check("双层联动：同一客户端首次请求经边缘到网关放行", code1 == 200, "HTTP {}".format(code1))
        code2, body2 = req(base + "/api/plain/x", {"X-Forwarded-For": client_a})
        check("双层联动：同一客户端第二次被网关层拒绝（429）", code2 == 429, "HTTP {}".format(code2))
        check_gateway_layer_reject(gw_mode, body2)
        code3, _b3 = req(base + "/api/plain/x", {"X-Forwarded-For": client_b})
        check("双层联动：换客户端 IP 后网关层独立放行（证明经边缘取到真实客户端）",
              code3 == 200, "HTTP {}".format(code3))
    finally:
        if nginx is not None:
            nginx.stop()
        if gw.poll() is None:
            gw.terminate()
            try:
                gw.wait(timeout=10)
            except subprocess.TimeoutExpired:
                gw.kill()
        gw_stub.shutdown()


def parse_mode(argv):
    """网关限流实现模式：--mode 优先，其次 SMOKE_RATELIMIT_MODE，最后默认 redis。"""
    parser = argparse.ArgumentParser(description="边缘限流层验收（网关限流 redis / memory 双模式）")
    parser.add_argument("--mode", choices=[MODE_REDIS, MODE_MEMORY], default=None,
                        help="网关限流实现模式：redis（默认）/ memory")
    args = parser.parse_args(argv)
    gw_mode = args.mode or os.environ.get("SMOKE_RATELIMIT_MODE") or MODE_REDIS
    gw_mode = str(gw_mode).strip().lower()
    if gw_mode not in (MODE_REDIS, MODE_MEMORY):
        print("参数错误：模式只支持 {} / {}".format(MODE_REDIS, MODE_MEMORY))
        return None
    return gw_mode


class _ProcRunner:
    """把普通子进程包装成 runner 接口（网关进程用）。"""

    def __init__(self, proc):
        self.proc = proc

    def alive(self):
        return self.proc.poll() is None

    def tail(self):
        return ""

    def stop(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    gw_mode = parse_mode(argv)
    if gw_mode is None:
        return 2
    run_mode = pick_mode()
    if run_mode is None:
        print("环境不具备：既没有本地 nginx 镜像（{}），也没有宿主 nginx 可执行文件（{}）".format(
            NGINX_IMAGE, NGINX))
        print("请在有 nginx 的环境执行，或用 EDGE_NGINX_MODE=host + NGINX_BIN 指定路径。")
        return 2
    if run_mode == "docker" and not docker_image_present(NGINX_IMAGE):
        print("环境不具备：本地没有 nginx 镜像（{}），先 docker pull 或指定 NGINX_IMAGE。".format(NGINX_IMAGE))
        return 2
    if run_mode == "host" and not Path(NGINX).exists():
        print("环境不具备：找不到 nginx 可执行文件（{}），或用 NGINX_BIN 指定路径。".format(NGINX))
        return 2
    if not CONF.is_file():
        print("环境不具备：缺少 {}".format(CONF))
        return 2
    print("网关限流实现模式：{}".format(gw_mode))

    runner_label = DockerNginx.label if run_mode == "docker" else HostNginx.label
    print("运行形态：{}（{}）".format(runner_label, NGINX_IMAGE if run_mode == "docker" else NGINX))

    # ---------- 1) 交付配置本身的语法校验（验的是交付物，不是临时改写版）----------
    rc, stderr = check_conf(run_mode)
    check("nginx -t 校验交付配置 nginx/nginx.conf 通过", rc == 0, "rc={} stderr={}".format(rc, stderr))

    # ---------- 2) 起真实 nginx（上游指向桩，额度调小以秒级触发 429）----------
    stub_port = free_port()
    edge_port = free_port()
    httpd = start_stub(stub_port)
    tmp = Path(tempfile.mkdtemp(prefix="rf-edge-"))
    (tmp / "logs").mkdir(exist_ok=True)
    nginx = start_nginx(run_mode, patch_conf(stub_port, edge_port, 1, 3, daemon_off=(run_mode == "host")),
                         tmp, "nginx.conf")
    try:
        base = "http://127.0.0.1:{}".format(edge_port)
        ready, nginx_last = wait_http(base + "/api/health", nginx)
        check("nginx 进程启动并可服务", ready,
              "nginx 未就绪 last={} 输出={}".format(nginx_last, nginx.tail()))
        if ready:
            codes = []
            first_body = ""
            for _ in range(12):
                code, body = req(base + "/api/health")
                codes.append(code)
                if code == 200 and not first_body:
                    first_body = body
                if code == 429:
                    break
            check("突发额度内放行、超额被边缘层拒绝",
                  429 in codes and all(c == 200 for c in codes[:codes.index(429)]),
                  "实际状态序列 {}".format(codes))
            code, body = req(base + "/api/health")
            check("边缘 429 响应体为统一响应结构且错误码为 RF_GATEWAY_001",
                  code == 429 and "RF_GATEWAY_001" in body,
                  "HTTP {} body={}".format(code, body[:200]))
            check("边缘 429 响应体标记来源层 details.layer=edge",
                  '"layer":"edge"' in body.replace(" ", ""),
                  "body={}".format(body[:200]))
            try:
                echoed = json.loads(first_body)
            except Exception:
                echoed = {}
            check("反向代理头 X-Request-Id 已透传到上游",
                  bool(echoed.get("xRequestId")),
                  "上游收到={}".format(echoed.get("xRequestId")))
            check("反向代理头 X-Forwarded-For / X-Real-IP 已透传到上游",
                  bool(echoed.get("xForwardedFor")) and bool(echoed.get("xRealIp")),
                  "上游收到 XFF={} XRealIP={}".format(echoed.get("xForwardedFor"), echoed.get("xRealIp")))
            dual_layer(tmp, gw_mode, run_mode)
    finally:
        nginx.stop()
        httpd.shutdown()
        shutil.rmtree(tmp, ignore_errors=True)

    print("-" * 74)
    print("网关模式：{}    运行形态：{}    汇总：PASS {} / FAIL {}".format(
        gw_mode, run_mode, PASS, len(FAIL)))
    if FAIL:
        print("失败断言：{}".format(FAIL))
        print("结果：FAIL（EXIT 1）")
        return 1
    print("结果：PASS（EXIT 0）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
