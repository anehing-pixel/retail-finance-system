# -*- coding: utf-8 -*-
"""网关限流冒烟（真实网关进程 + 真实 HTTP），支持 redis / memory 两套实现。

口径：不把「进程起来了」当「限流生效了」（反模式 A-16）——
每条断言都打真实 HTTP，验证 429 语义、退避头、计数键隔离、可信代理口径、限流开关。

被测对象：gateway 模块的限流过滤器。**两套实现由一个开关二选一**：

· mode=redis（默认）：分布式令牌桶。配置前缀 retail.gateway.rate-limit.*，
  环境变量 RETAIL_GATEWAY_RATE_LIMIT_*。
  计数键 = key-prefix + 规则 path + ":" + 调用方指纹，指纹是 Authorization 头的
  SHA-256 哈希（无该头时退化到来源 IP）。桶在 Redis（Lua 原子扣减），
  本冒烟模板显式 fail-open（生产默认 fail-closed，R11 P0-5）。
  429 响应：状态 429 + Retry-After 头；响应体 details 只有 path；
  **不下发 X-RateLimit-* 头**（故本脚本在 redis 模式下不断言其存在）。

· mode=memory：进程内令牌桶。配置前缀 retail.gateway.rate-limit.memory.*，
  环境变量 RETAIL_GATEWAY_RATE_LIMIT_MEMORY_*。
  计数键 = 命中的路由段 + "|" + 客户端 IP（按 IP 计数，与 redis 的调用方指纹口径不同），
  桶在进程内存 → 单实例口径（多副本时实际放行量 ≈ 单副本额度 × 副本数）。
  429 响应：状态 429 + Retry-After + X-RateLimit-Limit/Remaining/Reset 头；
  响应体 details 含 limit / remaining / retryAfterSeconds / policy。

两种模式的断言差异：
  · 计数键隔离：redis = 不同 Authorization 头各算各的桶（无头时按来源 IP）；
                memory = 不同客户端 IP 各算各的桶（换 Authorization 头不换桶）。
  · 429 响应头：redis 无 X-RateLimit-*（按口径不适用）；memory 必须有 Limit/Remaining。
  · 429 响应体 details：redis 有 path；memory 有 policy（命中的路由段）。

边缘 Nginx 层不在本脚本范围内（需要 nginx 运行时）：边缘层由
scripts/smoke_ratelimit_edge.py 单独验收（details.layer=edge 与网关层可区分）。

前置：先构建网关 jar
    mvn -B -o -pl gateway -am package
    redis 模式还需要 Redis 可达（默认 localhost:6382，可用 REDIS_HOST / REDIS_PORT 覆盖；
    不可达时冒烟模板会 fail-open 放行，限流无从验证，故直接判环境不具备）。

用法：
    python scripts/smoke_ratelimit.py                 # 默认 redis 模式
    python scripts/smoke_ratelimit.py --mode memory
    SMOKE_RATELIMIT_MODE=memory python scripts/smoke_ratelimit.py
退出码：0 = 全部通过；1 = 有断言失败；2 = 环境不具备（jar 缺失、Redis 不可达、参数非法等）。
"""
from __future__ import annotations

import argparse
import json
import os
import re
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
JAR = ROOT / "gateway" / "target" / "retail-api-gateway-1.0.0-SNAPSHOT.jar"
TEMPLATE = ROOT / "scripts" / "smoke_ratelimit_application.yml"
NGINX_CONF = ROOT / "nginx" / "nginx.conf"

MODE_REDIS = "redis"
MODE_MEMORY = "memory"

# redis 模式限流态所在 Redis（与网关生产配置同口径）
REDIS_HOST = os.environ.get("REDIS_HOST", "localhost")
REDIS_PORT = int(os.environ.get("REDIS_PORT", "6382"))

# 模板占位符识别（渲染后必须一个不剩，否则说明模板与脚本漂移）
PLACEHOLDER = re.compile(r"__[A-Z0-9_]+__")

PASS = 0
FAIL = []
TMP = Path(tempfile.mkdtemp(prefix="rf-ratelimit-"))

# ---- 两套实现的参数档：与配置模板里的占位符一一对应 ----
# redis 模式：规则按 Ant 路径匹配（多条命中取 pattern 最长的）；replenish-rate 为整数（令牌/秒）。
REDIS_PROFILE = {
    "__REDIS_ENABLED__": "true",
    "__REDIS_FAIL_OPEN__": "true",
    "__REDIS_KEY_BY__": "auth_or_ip",
    # 冒烟专用键前缀：与同机运行中的生产网关限流键隔离，避免互相干扰
    "__REDIS_KEY_PREFIX__": "rf:rl:smoke:redis:",
    "__REDIS_HEALTH_REPLENISH__": "50",
    "__REDIS_HEALTH_BURST__": "50",
    "__REDIS_STRICT_REPLENISH__": "1",
    "__REDIS_STRICT_BURST__": "2",
    "__REDIS_REPLENISH__": "1",
    "__REDIS_BURST__": "3",
}

# memory 模式：规则按路径前缀匹配（最长前缀命中）；refill-per-second 允许小数。
MEMORY_PROFILE = {
    "__MEMORY_ENABLED__": "true",
    "__MEMORY_MAX_TRACKED_KEYS__": "50000",
    "__MEMORY_IDLE_SECONDS__": "600",
    "__MEMORY_DEFAULT_CAPACITY__": "3",
    "__MEMORY_DEFAULT_REFILL__": "0.05",
    "__MEMORY_HEALTH_CAPACITY__": "50",
    "__MEMORY_HEALTH_REFILL__": "50",
    "__MEMORY_STRICT_CAPACITY__": "2",
    "__MEMORY_STRICT_REFILL__": "0.05",
    "__MEMORY_CAPACITY__": "3",
    "__MEMORY_REFILL__": "0.05",
}

# 关闭开关实例用的环境变量：两套实现的开关都关掉，与实现选择无关
DISABLE_ENV = {
    "RETAIL_GATEWAY_RATE_LIMIT_ENABLED": "false",
    "RETAIL_GATEWAY_RATE_LIMIT_MEMORY_ENABLED": "false",
}


def hget(headers, name):
    """HTTP 头名大小写不敏感（RFC 7230）——urllib 会把 X-RateLimit-* 归一化成 X-Ratelimit-*。"""
    for k, v in headers.items():
        if k.lower() == name.lower():
            return v
    return None


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


def req(method, url, headers=None):
    request = urllib.request.Request(url, method=method)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=10) as resp:
            return resp.status, resp.read().decode("utf-8", "replace"), dict(resp.headers)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace"), dict(exc.headers)
    except Exception as exc:  # 连接失败也算失败态
        return 0, str(exc), {}


def free_port():
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


def redis_reachable(host, port, timeout=2.0):
    """redis 模式的前置检查：Redis 不可达时冒烟模板会 fail-open 放行，限流无从验证。"""
    try:
        with socket.create_connection((host, int(port)), timeout=timeout):
            return True
    except OSError:
        return False


class StubHandler(BaseHTTPRequestHandler):
    """本地桩上游：记录收到的请求数，用来证明「被限流的请求没有打到下游」。"""

    protocol_version = "HTTP/1.0"

    def do_GET(self):
        path = self.path.split("?")[0]
        with self.server.lock:
            self.server.counts[path] = self.server.counts.get(path, 0) + 1
        body = json.dumps({"service": "stub-upstream", "status": "UP"}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def start_stub(port):
    httpd = ThreadingHTTPServer(("127.0.0.1", port), StubHandler)
    httpd.lock = threading.Lock()
    httpd.counts = {}
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


def render_config(gateway_port, stub_uri, mode, trusted):
    """按模式渲染网关配置模板（两套实现的键都写在模板里，由占位符选择生效值）。"""
    text = TEMPLATE.read_text(encoding="utf-8")
    mapping = {
        "__GATEWAY_PORT__": str(gateway_port),
        "__STUB_URI__": stub_uri,
        "__REDIS_HOST__": REDIS_HOST,
        "__REDIS_PORT__": str(REDIS_PORT),
        "__RATE_LIMIT_MODE__": mode,
        "__REDIS_TRUSTED__": trusted,
        "__MEMORY_TRUSTED__": trusted,
    }
    mapping.update(REDIS_PROFILE)
    mapping.update(MEMORY_PROFILE)
    for key, value in mapping.items():
        text = text.replace(key, value)
    return text


def start_gateway(name, config_text, mode, env_extra=None):
    config_path = TMP / "{}.yml".format(name)
    config_path.write_text(config_text, encoding="utf-8")
    log_path = TMP / "{}.log".format(name)
    env = os.environ.copy()
    # 模式开关同时走环境变量（优先级高于任何配置文件），杜绝「配置文件口径不一致」导致跑错实现
    env["RETAIL_GATEWAY_RATE_LIMIT_MODE"] = mode
    if env_extra:
        env.update(env_extra)
    handle = open(log_path, "w", encoding="utf-8")
    proc = subprocess.Popen(
        ["java", "-jar", str(JAR), "--spring.config.location=file:" + config_path.as_posix()],
        stdout=handle, stderr=subprocess.STDOUT, env=env)
    proc.log_handle = handle
    proc.log_path = log_path
    return proc


def wait_ready(proc, port, timeout=120):
    """轮询到就绪为止（不用固定 sleep 糊弄）：网关起来才算通过。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if proc.poll() is not None:
            return False
        code, _body, _headers = req("GET", "http://127.0.0.1:{}/api/health".format(port))
        if code == 200:
            return True
        time.sleep(0.5)
    return False


def stop(proc):
    if proc is None:
        return
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
    handle = getattr(proc, "log_handle", None)
    if handle:
        handle.close()


def stub_count(httpd, path):
    with httpd.lock:
        return httpd.counts.get(path, 0)


def check_reject(name, code, body, headers, mode, expect_path, expect_trace=None):
    """429 语义断言：按模式分支（redis 无 X-RateLimit-* 头、details 只有 path；
    memory 有 X-RateLimit-* 头、details 含 policy）。"""
    check("{}：返回 429".format(name), code == 429, "实际 HTTP {}".format(code))
    check("{}：带 Retry-After 退避头".format(name), bool(hget(headers, "Retry-After")),
          "headers={}".format(headers))
    payload = parse_json(body)
    check("{}：响应体为统一响应结构且错误码为 RF_GATEWAY_001".format(name),
          bool(payload) and payload.get("code") == "RF_GATEWAY_001",
          "body={}".format(body[:200]))
    trace = (payload or {}).get("traceId")
    check("{}：响应体带 traceId".format(name), bool(trace), "traceId={}".format(trace))
    if expect_trace:
        check("{}：traceId 与入站 X-Request-Id 一致".format(name),
              trace == expect_trace, "traceId={} 期望={}".format(trace, expect_trace))
    details = (payload or {}).get("details") or {}
    if mode == MODE_REDIS:
        check("{}：details.path 为被限流路径（redis 口径）".format(name),
              details.get("path") == expect_path,
              "details={}".format(details))
        info("{}：redis 模式不下发 X-RateLimit-* 头（按口径不适用，不计失败）".format(name))
    else:
        check("{}：下发 X-RateLimit-Limit 头（memory 口径）".format(name),
              bool(hget(headers, "X-RateLimit-Limit")), "headers={}".format(headers))
        check("{}：下发 X-RateLimit-Remaining 头且为 0（memory 口径）".format(name),
              hget(headers, "X-RateLimit-Remaining") == "0",
              "headers={}".format(headers))
        check("{}：details 含 limit/remaining/retryAfterSeconds/policy（memory 口径）".format(name),
              all(key in details for key in ("limit", "remaining", "retryAfterSeconds", "policy")),
              "details={}".format(details))
        check("{}：details.policy 为命中的路由段（memory 口径）".format(name),
              bool(details.get("policy")), "details={}".format(details))
        check("{}：details.remaining 为 0（桶已空）".format(name),
              str(details.get("remaining")) == "0", "details={}".format(details))


def check_nginx_conf():
    """边缘层配置口径自检（本机无 nginx 运行时，只能做口径与登记一致性检查）。"""
    if not NGINX_CONF.is_file():
        check("边缘层配置存在 nginx/nginx.conf", False, "文件缺失")
        return
    check("边缘层配置存在 nginx/nginx.conf", True)
    text = NGINX_CONF.read_text(encoding="utf-8")
    for token in ("limit_req_zone", "limit_conn_zone", "limit_req ", "limit_req_status   429",
                  "error_page 429", "proxy_pass http://retail_gateway",
                  "proxy_pass http://retail_dmz", "X-Forwarded-For"):
        check("边缘配置含限流指令 {}".format(token.strip()), token in text)
    r00 = (ROOT / "docs" / "R00-设计基线与命名定源.md").read_text(encoding="utf-8")
    for port in ("18076", "18077"):
        check("边缘入口端口 {} 已登记到 R00".format(port),
              "listen      {};".format(port) in text and port in r00)


def parse_mode(argv):
    """模式选择：--mode 优先，其次环境变量 SMOKE_RATELIMIT_MODE，最后默认 redis。"""
    parser = argparse.ArgumentParser(description="网关限流冒烟（redis / memory 双模式）")
    parser.add_argument("--mode", choices=[MODE_REDIS, MODE_MEMORY], default=None,
                        help="限流实现模式：redis（默认，分布式令牌桶）/ memory（进程内令牌桶）")
    args = parser.parse_args(argv)
    mode = args.mode or os.environ.get("SMOKE_RATELIMIT_MODE") or MODE_REDIS
    mode = str(mode).strip().lower()
    if mode not in (MODE_REDIS, MODE_MEMORY):
        print("参数错误：模式只支持 {} / {}".format(MODE_REDIS, MODE_MEMORY))
        return None
    return mode


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    mode = parse_mode(argv)
    if mode is None:
        return 2
    if not JAR.is_file():
        print("环境不具备：网关 jar 不存在 {}".format(JAR))
        print("请先执行：mvn -B -o -pl gateway -am package")
        return 2
    if not TEMPLATE.is_file():
        print("环境不具备：冒烟配置模板不存在 {}".format(TEMPLATE))
        return 2
    if mode == MODE_REDIS and not redis_reachable(REDIS_HOST, REDIS_PORT):
        print("环境不具备：redis 模式需要 Redis 可达（{}:{}）".format(REDIS_HOST, REDIS_PORT))
        print("Redis 不可达时冒烟模板按 fail-open 放行，限流是否生效无从验证。")
        print("可先启动 Redis 实例，或用 REDIS_HOST / REDIS_PORT 指定其他实例；")
        print("也可改跑进程内实现：--mode memory。")
        return 2

    print("限流实现模式：{}".format(mode))
    if mode == MODE_REDIS:
        print("口径：计数键 = key-prefix + 规则 path + 调用方指纹（Authorization 头哈希，"
              "无头时退化到 IP）；429 响应不带 X-RateLimit-* 头，details 只有 path。")
    else:
        print("口径：计数键 = 命中的路由段 + 客户端 IP（按 IP 计数）；"
              "429 响应带 X-RateLimit-Limit/Remaining/Reset 头，details 含 policy。")

    stub_port = free_port()
    httpd = start_stub(stub_port)
    stub_uri = "http://127.0.0.1:{}".format(stub_port)
    print("桩上游已启动：{}".format(stub_uri))

    procs = []
    try:
        # ---------- 实例 A：可信代理（模拟前置 Nginx），默认桶容量 3 ----------
        port_a = free_port()
        # 补充速率取最小值（redis 模式 replenish-rate 为整数，最小 1 令牌/秒），
        # 保证「突发 3 次后第 4 次必被拒」不被补充打断
        cfg_a = render_config(port_a, stub_uri, mode, "127.0.0.1/32")
        leftover = PLACEHOLDER.findall(cfg_a)
        check("配置模板占位符全部替换", not leftover,
              "未替换={}".format(sorted(set(leftover))))
        proc_a = start_gateway("trusted", cfg_a, mode)
        procs.append(proc_a)
        check("可信代理实例启动就绪", wait_ready(proc_a, port_a),
              "网关未就绪，见 {}".format(proc_a.log_path))
        base_a = "http://127.0.0.1:{}".format(port_a)
        ip_a = {"X-Forwarded-For": "203.0.113.9"}

        codes = [req("GET", base_a + "/api/plain/x", ip_a)[0] for _ in range(3)]
        check("额度内请求全部放行 (200/200/200)", codes == [200, 200, 200], "实际 {}".format(codes))

        trace_a = "smoke-ratelimit-{}-a".format(mode)
        code, body, headers = req("GET", base_a + "/api/plain/x",
                                  dict(ip_a, **{"X-Request-Id": trace_a}))
        check_reject("超额度请求", code, body, headers, mode, "/api/plain/x", trace_a)

        check("被限流的请求未打到下游", stub_count(httpd, "/api/plain/x") == 3,
              "下游计数={}".format(stub_count(httpd, "/api/plain/x")))

        # ---------- 计数键隔离：两种模式口径不同，分支断言 ----------
        if mode == MODE_REDIS:
            code, _body, _headers = req("GET", base_a + "/api/plain/x",
                                        dict(ip_a, **{"Authorization": "Bearer smoke-a"}))
            check("redis 模式：不同 Authorization 头各算各的桶", code == 200,
                  "实际 HTTP {}".format(code))
            code, _body, _headers = req("GET", base_a + "/api/plain/x",
                                        {"X-Forwarded-For": "203.0.113.10"})
            check("redis 模式：无 Authorization 头时退化为按来源 IP 分桶（不同 IP 各算各的）",
                  code == 200, "实际 HTTP {}".format(code))
        else:
            code, _body, _headers = req("GET", base_a + "/api/plain/x",
                                        {"X-Forwarded-For": "203.0.113.10"})
            check("memory 模式：不同客户端 IP 各算各的桶", code == 200,
                  "实际 HTTP {}".format(code))
            code, _body, _headers = req("GET", base_a + "/api/plain/x",
                                        dict(ip_a, **{"Authorization": "Bearer smoke-a"}))
            check("memory 模式：换 Authorization 头不换桶（按客户端 IP 计数，仍被拒）",
                  code == 429, "实际 HTTP {}".format(code))

        codes = [req("GET", base_a + "/api/strict/y", ip_a)[0] for _ in range(2)]
        code = req("GET", base_a + "/api/strict/y", ip_a)[0]
        check("路径级规则按更严配额命中 (2 次 200 后 429)",
              codes == [200, 200] and code == 429, "实际 {}".format(codes + [code]))

        # 桶被取空后应随补充恢复放行（redis 模式 1 令牌/秒；memory 模式 0.05 令牌/秒 → 约 20 秒）
        recovered = False
        deadline = time.time() + 60
        while time.time() < deadline:
            code, _body, _headers = req("GET", base_a + "/api/plain/x", ip_a)
            if code == 200:
                recovered = True
                break
            time.sleep(0.5)
        check("令牌补充后恢复放行", recovered, "60 秒内未恢复")

        # ---------- 实例 B：直连来源不可信（不采信 X-Forwarded-For） ----------
        port_b = free_port()
        cfg_b = render_config(port_b, stub_uri, mode, "")
        proc_b = start_gateway("untrusted", cfg_b, mode)
        procs.append(proc_b)
        check("不可信代理实例启动就绪", wait_ready(proc_b, port_b),
              "网关未就绪，见 {}".format(proc_b.log_path))
        base_b = "http://127.0.0.1:{}".format(port_b)
        codes = [req("GET", base_b + "/api/plain/x", {"X-Forwarded-For": "198.51.100.1"})[0]
                 for _ in range(3)]
        code = req("GET", base_b + "/api/plain/x", {"X-Forwarded-For": "198.51.100.2"})[0]
        check("伪造 X-Forwarded-For 不能重置限流（可信代理网段为空 → 不采信转发头）",
              codes == [200, 200, 200] and code == 429, "实际 {}".format(codes + [code]))

        # ---------- 实例 C：环境变量关闭开关（验证限流总开关生效） ----------
        port_c = free_port()
        cfg_c = render_config(port_c, stub_uri, mode, "127.0.0.1/32")
        proc_c = start_gateway("disabled", cfg_c, mode, dict(DISABLE_ENV))
        procs.append(proc_c)
        check("限流关闭实例启动就绪", wait_ready(proc_c, port_c),
              "网关未就绪，见 {}".format(proc_c.log_path))
        base_c = "http://127.0.0.1:{}".format(port_c)
        codes = [req("GET", base_c + "/api/plain/x")[0] for _ in range(8)]
        check("限流关闭后完全放行（限流开关生效）",
              codes == [200] * 8, "实际 {}".format(codes))

        check_nginx_conf()
    finally:
        for proc in procs:
            stop(proc)
        httpd.shutdown()

    print("-" * 74)
    print("模式：{}    汇总：PASS {} / FAIL {}".format(mode, PASS, len(FAIL)))
    if FAIL:
        print("失败断言：{}".format(FAIL))
        print("结果：FAIL（EXIT 1）")
        return 1
    print("结果：PASS（EXIT 0）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
