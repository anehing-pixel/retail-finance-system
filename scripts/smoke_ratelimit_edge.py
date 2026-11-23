# -*- coding: utf-8 -*-
"""边缘限流层（Nginx）端到端验收 + 双层联动验证。

口径：先验**交付配置本身**（`nginx -t` 语法校验），再起真实 nginx 进程打真实 HTTP，
验证边缘层真的泄洪：突发额度内放行、超额 429 且响应体是统一结构、
反向代理头（X-Request-Id / X-Forwarded-For）确实透传到上游。
最后做**双层联动**：真实 nginx 前置真实网关，验证「边缘透传 XFF + 网关登记可信代理网段」
这条前提成立——同客户端被网关层拒绝、换客户端独立放行，且拒绝来自网关层而非边缘层。

前置：需要 nginx 运行时（本机无 nginx 时请在 Linux / WSL 侧执行；Windows 侧可用
      `wsl -u root python3 scripts/smoke_ratelimit_edge.py`）。
双层联动还需要网关 jar（`mvn -B -o -pl gateway -am package`），缺失则跳过该段。
用法：
    python scripts/smoke_ratelimit_edge.py
    NGINX_BIN=/usr/sbin/nginx python scripts/smoke_ratelimit_edge.py
退出码：0 = 全部通过；1 = 有断言失败；2 = 环境不具备（无 nginx 等）。
"""
from __future__ import annotations

import json
import os
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
NGINX = os.environ.get("NGINX_BIN") or shutil.which("nginx") or "/usr/sbin/nginx"

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


def patch_conf(stub_port, edge_port, rate, burst):
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
    return "daemon off;\n" + conf


def start_nginx(conf_text, tmp, name):
    path = tmp / name
    path.write_text(conf_text, encoding="utf-8")
    # 每个实例独立 pid 文件：否则第二个实例会因共用系统 pid 文件被判「已在运行」而退出
    pid_file = tmp / (name + ".pid")
    err_path = tmp / (name + ".stderr")
    err_file = open(err_path, "wb")
    proc = subprocess.Popen([NGINX, "-p", str(tmp), "-c", str(path), "-g", "pid {};".format(pid_file)],
                            stdout=err_file, stderr=err_file)
    proc.err_file = err_file
    proc.err_path = err_path
    return proc


def wait_http(url, proc, tries=40, interval=0.25):
    """等待 URL 返回 200；返回 (是否就绪, 最后一次状态码与响应体片段)。"""
    last = (0, "")
    for _ in range(tries):
        if proc.poll() is not None:
            return False, last
        code, body = req(url)
        last = (code, body[:200])
        if code == 200:
            return True, last
        time.sleep(interval)
    return False, last


def dual_layer(tmp):
    """双层联动：真实 nginx 前置真实网关。"""
    if not JAR.is_file():
        print("SKIP  双层联动（网关 jar 不存在，先跑 mvn -B -o -pl gateway -am package）")
        return
    gw_port = free_port()
    gw_stub_port = free_port()
    gw_stub = start_stub(gw_stub_port)
    gw_conf = tmp / "gateway.yml"
    gw_conf.write_text("""server:
  port: {}
spring:
  application:
    name: api-gateway-dual-smoke
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
        fail-fast: false
    gateway:
      discovery:
        locator:
          enabled: false
      routes:
        - id: core
          uri: http://127.0.0.1:{}
          predicates:
            - Path=/api/**
retail:
  ratelimit:
    enabled: true
    default-policy:
      capacity: 1
      refill-per-second: 0.05
    trustedProxyCidrs: "127.0.0.1/32"
    rules:
      # 健康检查单独成段：就绪探活不应占用业务配额
      - pathPrefix: /api/health
        capacity: 50
        refill-per-second: 50
      - pathPrefix: /api/plain/
        capacity: 1
        refill-per-second: 0.05
""".format(gw_port, gw_stub_port), encoding="utf-8")
    gw = subprocess.Popen(["java", "-jar", str(JAR), "--spring.config.location=file:" + gw_conf.as_posix()],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    nginx = None
    try:
        gw_ready, gw_last = wait_http("http://127.0.0.1:{}/api/health".format(gw_port), gw, tries=120, interval=0.5)
        check("双层联动：网关实例启动就绪", gw_ready, "网关未就绪 last={}".format(gw_last))
        if not gw_ready:
            return
        edge_port = free_port()
        nginx = start_nginx(patch_conf(gw_port, edge_port, 1000, 1000), tmp, "nginx-dual.conf")
        base = "http://127.0.0.1:{}".format(edge_port)
        edge_ready, edge_last = wait_http(base + "/api/health", nginx)
        detail = "边缘层未就绪 last={}".format(edge_last)
        try:
            detail += " stderr=" + (nginx.err_path.read_text(errors="replace")[-300:] if nginx.err_path.exists() else "")
        except Exception:
            pass
        check("双层联动：边缘层启动并可服务", edge_ready, detail)
        if not edge_ready:
            return
        client_a = "203.0.113.9"
        client_b = "203.0.113.77"
        code1, _b1 = req(base + "/api/plain/x", {"X-Forwarded-For": client_a})
        check("双层联动：同一客户端首次请求经边缘到网关放行", code1 == 200, "HTTP {}".format(code1))
        code2, body2 = req(base + "/api/plain/x", {"X-Forwarded-For": client_a})
        check("双层联动：同一客户端第二次被网关层拒绝（429）", code2 == 429, "HTTP {}".format(code2))
        check("双层联动：拒绝来自网关层（details.policy=段标识，非 edge）",
              "RF_GATEWAY_001" in body2 and '"policy"' in body2.replace(" ", "")
              and '"layer":"edge"' not in body2.replace(" ", ""),
              "body={}".format(body2[:220]))
        code3, _b3 = req(base + "/api/plain/x", {"X-Forwarded-For": client_b})
        check("双层联动：换客户端 IP 后网关层独立放行（证明经边缘取到真实客户端）",
              code3 == 200, "HTTP {}".format(code3))
    finally:
        if nginx is not None and nginx.poll() is None:
            nginx.terminate()
            try:
                nginx.wait(timeout=10)
            except subprocess.TimeoutExpired:
                nginx.kill()
        if gw.poll() is None:
            gw.terminate()
            try:
                gw.wait(timeout=10)
            except subprocess.TimeoutExpired:
                gw.kill()
        gw_stub.shutdown()


def main():
    if not Path(NGINX).exists():
        print("环境不具备：找不到 nginx 可执行文件（{}）".format(NGINX))
        print("请在有 nginx 的环境执行，或用 NGINX_BIN 指定路径。")
        return 2
    if not CONF.is_file():
        print("环境不具备：缺少 {}".format(CONF))
        return 2

    # ---------- 1) 交付配置本身的语法校验（验的是交付物，不是临时改写版）----------
    proc = subprocess.run([NGINX, "-t", "-c", str(CONF)], capture_output=True, text=True)
    check("nginx -t 校验交付配置 nginx/nginx.conf 通过",
          proc.returncode == 0,
          "rc={} stderr={}".format(proc.returncode, (proc.stderr or "")[-300:]))

    # ---------- 2) 起真实 nginx（上游指向桩，额度调小以秒级触发 429）----------
    stub_port = free_port()
    edge_port = free_port()
    httpd = start_stub(stub_port)
    tmp = Path(tempfile.mkdtemp(prefix="rf-edge-"))
    (tmp / "logs").mkdir(exist_ok=True)
    nginx = start_nginx(patch_conf(stub_port, edge_port, 1, 3), tmp, "nginx.conf")
    try:
        base = "http://127.0.0.1:{}".format(edge_port)
        ready, nginx_last = wait_http(base + "/api/health", nginx)
        check("nginx 进程启动并可服务", ready, "nginx 未就绪 last={}".format(nginx_last))
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
            dual_layer(tmp)
    finally:
        if nginx.poll() is None:
            nginx.terminate()
            try:
                nginx.wait(timeout=10)
            except subprocess.TimeoutExpired:
                nginx.kill()
        httpd.shutdown()
        shutil.rmtree(tmp, ignore_errors=True)

    print("-" * 74)
    print("汇总：PASS {} / FAIL {}".format(PASS, len(FAIL)))
    if FAIL:
        print("失败断言：{}".format(FAIL))
        print("结果：FAIL（EXIT 1）")
        return 1
    print("结果：PASS（EXIT 0）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
