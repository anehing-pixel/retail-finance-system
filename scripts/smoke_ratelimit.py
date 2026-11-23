# -*- coding: utf-8 -*-
"""网关令牌桶限流冒烟（真实网关进程 + 真实 HTTP）。

口径：不把「进程起来了」当「限流生效了」（反模式 A-16）——
每条断言都打真实 HTTP，验证 429 语义、退避头、计数键隔离、可信代理口径、限流开关。

被测对象：gateway 模块的令牌桶限流过滤器（配置键 retail.ratelimit.*）。
边缘 Nginx 层不在本脚本范围内：需要 nginx 运行时，
本机既无 nginx 也拉不到镜像（Docker 源不可达），故边缘层改为交付配置 +
`nginx -t` 与验证步骤（nginx/README.md §4 §5），本脚本另做配置口径一致性自检。

前置：先构建网关 jar
    mvn -B -o -pl gateway -am package

用法：
    python scripts/smoke_ratelimit.py
退出码：0 = 全部通过；1 = 有断言失败；2 = 环境不具备（jar 缺失等）。
"""
from __future__ import annotations

import json
import os
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

PASS = 0
FAIL = []
TMP = Path(tempfile.mkdtemp(prefix="rf-ratelimit-"))


def check(name, cond, detail=""):
    global PASS
    if cond:
        PASS += 1
        print("PASS  {}".format(name))
    else:
        FAIL.append(name)
        print("FAIL  {}  {}".format(name, detail))


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


def render_config(gateway_port, stub_uri, enabled, capacity, refill, trusted, strict_capacity):
    text = TEMPLATE.read_text(encoding="utf-8")
    mapping = {
        "__GATEWAY_PORT__": str(gateway_port),
        "__STUB_URI__": stub_uri,
        "__ENABLED__": str(enabled).lower(),
        "__CAPACITY__": str(capacity),
        "__REFILL__": str(refill),
        "__TRUSTED__": trusted,
        "__STRICT_CAPACITY__": str(strict_capacity),
    }
    for key, value in mapping.items():
        text = text.replace(key, value)
    return text


def start_gateway(name, config_text, env_extra=None):
    config_path = TMP / "{}.yml".format(name)
    config_path.write_text(config_text, encoding="utf-8")
    log_path = TMP / "{}.log".format(name)
    env = os.environ.copy()
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


def main():
    if not JAR.is_file():
        print("环境不具备：网关 jar 不存在 {}".format(JAR))
        print("请先执行：mvn -B -o -pl gateway -am package")
        return 2
    if not TEMPLATE.is_file():
        print("环境不具备：冒烟配置模板不存在 {}".format(TEMPLATE))
        return 2

    stub_port = free_port()
    httpd = start_stub(stub_port)
    stub_uri = "http://127.0.0.1:{}".format(stub_port)
    print("桩上游已启动：{}".format(stub_uri))

    procs = []
    try:
        # ---------- 实例 A：可信代理（模拟前置 Nginx），默认桶容量 3 ----------
        port_a = free_port()
        # 补充速率取 0.05/s（1 个令牌需 20 秒）：保证「突发 3 次后第 4 次必被拒」不被补充打断
        cfg_a = render_config(port_a, stub_uri, True, 3, 0.05, "127.0.0.1/32", 2)
        proc_a = start_gateway("trusted", cfg_a)
        procs.append(proc_a)
        check("可信代理实例启动就绪", wait_ready(proc_a, port_a),
              "网关未就绪，见 {}".format(proc_a.log_path))
        base_a = "http://127.0.0.1:{}".format(port_a)
        ip_a = {"X-Forwarded-For": "203.0.113.9"}

        codes = [req("GET", base_a + "/api/plain/x", ip_a)[0] for _ in range(3)]
        check("额度内请求全部放行 (200/200/200)", codes == [200, 200, 200], "实际 {}".format(codes))

        code, body, headers = req("GET", base_a + "/api/plain/x", ip_a)
        check("超额度请求返回 429", code == 429, "实际 HTTP {}".format(code))
        check("429 带 Retry-After 头", bool(headers.get("Retry-After")),
              "headers={}".format(headers))
        check("429 响应体为统一响应结构且错误码为 RF_GATEWAY_001",
              "\"RF_GATEWAY_001\"" in body, "body={}".format(body[:200]))
        check("429 响应头 X-RateLimit-Remaining 为 0",
              headers.get("X-RateLimit-Remaining") == "0",
              "headers={}".format(headers))
        check("被限流的请求未打到下游", stub_count(httpd, "/api/plain/x") == 3,
              "下游计数={}".format(stub_count(httpd, "/api/plain/x")))

        code, _body, _headers = req("GET", base_a + "/api/plain/x",
                                    {"X-Forwarded-For": "203.0.113.10"})
        check("不同客户端 IP 各算各的桶", code == 200, "实际 HTTP {}".format(code))

        codes = [req("GET", base_a + "/api/strict/y", ip_a)[0] for _ in range(2)]
        code = req("GET", base_a + "/api/strict/y", ip_a)[0]
        check("路径级规则按更严配额命中 (2 次 200 后 429)",
              codes == [200, 200] and code == 429, "实际 {}".format(codes + [code]))

        code, _body, headers = req("GET", base_a + "/api/plain/z", ip_a)
        check("放行响应也带限流头",
              headers.get("X-RateLimit-Limit") and headers.get("X-RateLimit-Remaining") is not None,
              "headers={}".format(headers))

        # 桶被取空后应随补充恢复放行（补充 0.05/s → 最多等 20 秒，轮询到恢复为止）
        recovered = False
        deadline = time.time() + 30
        while time.time() < deadline:
            code, _body, _headers = req("GET", base_a + "/api/plain/x", ip_a)
            if code == 200:
                recovered = True
                break
            time.sleep(0.5)
        check("令牌补充后恢复放行", recovered, "30 秒内未恢复")

        # ---------- 实例 B：直连来源不可信（不采信 X-Forwarded-For） ----------
        port_b = free_port()
        cfg_b = render_config(port_b, stub_uri, True, 3, 0.05, "", 2)
        proc_b = start_gateway("untrusted", cfg_b)
        procs.append(proc_b)
        check("不可信代理实例启动就绪", wait_ready(proc_b, port_b),
              "网关未就绪，见 {}".format(proc_b.log_path))
        base_b = "http://127.0.0.1:{}".format(port_b)
        codes = [req("GET", base_b + "/api/plain/x", {"X-Forwarded-For": "198.51.100.1"})[0]
                 for _ in range(3)]
        code = req("GET", base_b + "/api/plain/x", {"X-Forwarded-For": "198.51.100.2"})[0]
        check("伪造 X-Forwarded-For 不能重置限流",
              codes == [200, 200, 200] and code == 429, "实际 {}".format(codes + [code]))

        # ---------- 实例 C：环境变量关闭开关（验证 RETAIL_RATELIMIT_ENABLED 生效） ----------
        port_c = free_port()
        cfg_c = render_config(port_c, stub_uri, True, 3, 0.5, "127.0.0.1/32", 2)
        proc_c = start_gateway("disabled", cfg_c, {"RETAIL_RATELIMIT_ENABLED": "false"})
        procs.append(proc_c)
        check("限流关闭实例启动就绪", wait_ready(proc_c, port_c),
              "网关未就绪，见 {}".format(proc_c.log_path))
        base_c = "http://127.0.0.1:{}".format(port_c)
        codes = [req("GET", base_c + "/api/plain/x")[0] for _ in range(8)]
        check("限流关闭后完全放行（RETAIL_RATELIMIT_ENABLED=false 生效）",
              codes == [200] * 8, "实际 {}".format(codes))

        check_nginx_conf()
    finally:
        for proc in procs:
            stop(proc)
        httpd.shutdown()

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
