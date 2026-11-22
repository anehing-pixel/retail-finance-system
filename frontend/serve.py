# -*- coding: utf-8 -*-
"""前端开发服务器（本地验证用，不属于 12 部署单元）。

静态文件（本目录）+ 同源反向代理（绕开 CORS，页面与后端同源直连）：
  /gw/**   -> http://127.0.0.1:18081（api-gateway，员工运营台主链路）
  /svc/**  -> http://127.0.0.1:8098 （servicing-service，DPD 快照直连段，同 e2e_w8 S12 口径）
  /cust/** -> http://127.0.0.1:8092 （customer-service，客户自助直连段，同 e2e_w8 S13 口径）

端口 18082（PROGRESS.md §2 登记，不与 8090–8099 / 18081 / 18091 冲突）。
用法：python frontend/serve.py   （Ctrl+C 停止）
"""
import io
import os
import sys
import urllib.error
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = int(os.environ.get("FE_PORT", "18082"))
ROUTES = [
    ("/gw/", os.environ.get("FE_GATEWAY", "http://127.0.0.1:18081")),
    ("/svc/", os.environ.get("FE_SERVICING", "http://127.0.0.1:8098")),
    ("/cust/", os.environ.get("FE_CUSTOMER", "http://127.0.0.1:8092")),
]
# 不透传的头（hop-by-hop 与会改变语义的头）
SKIP_HEADERS = {"host", "content-length", "connection", "keep-alive",
                "proxy-authenticate", "proxy-authorization", "te", "trailers",
                "transfer-encoding", "upgrade", "accept-encoding", "origin",
                "referer"}

# 禁用系统代理（本机 shell 注入 HTTP(S)_PROXY 会把 127.0.0.1 也送进代理 → 502）
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class Handler(SimpleHTTPRequestHandler):

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=HERE, **kwargs)

    def log_message(self, fmt, *args):
        sys.stdout.write("[fe:{}]\n".format(fmt % args))

    # ---- 代理 ----

    def _route(self):
        for prefix, target in ROUTES:
            if self.path.startswith(prefix):
                # /gw/api/x -> target + /api/x
                return target.rstrip("/") + self.path[len(prefix) - 1:]
        return None

    def _proxy(self):
        url = self._route()
        if url is None:
            self.send_error(404, "no proxy route for %s" % self.path)
            return
        headers = {}
        for key, value in self.headers.items():
            if key.lower() not in SKIP_HEADERS:
                headers[key] = value
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        request = urllib.request.Request(url, data=body, method=self.command)
        for key, value in headers.items():
            request.add_header(key, value)
        try:
            with opener.open(request, timeout=60) as resp:
                self._relay(resp.status, resp.headers, resp.read())
        except urllib.error.HTTPError as e:
            self._relay(e.code, e.headers, e.read())
        except Exception as e:  # 上游不可达
            self._relay(502, {"Content-Type": "application/json; charset=utf-8"},
                        ("{\"code\":\"RF_FE_502\",\"message\":\"上游不可达: %s\"}" % e).encode())

    def _relay(self, status, headers, body):
        self.send_response(status)
        ctype = headers.get("Content-Type") if hasattr(headers, "get") else None
        self.send_header("Content-Type", ctype or "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_POST(self):
        if self._route():
            self._proxy()
        else:
            super().do_POST()

    def do_GET(self):
        if self._route():
            self._proxy()
        else:
            super().do_GET()

    def do_PUT(self):
        self._proxy() if self._route() else self.send_error(404)

    def do_DELETE(self):
        self._proxy() if self._route() else self.send_error(404)

    def do_HEAD(self):
        if self._route():
            self._proxy()
        else:
            super().do_HEAD()


class Server(ThreadingHTTPServer):
    allow_reuse_address = True


if __name__ == "__main__":
    print("=" * 60)
    print("前端开发服务器 http://127.0.0.1:{}  静态目录={}".format(PORT, HERE))
    print("代理：/gw -> 18081  /svc -> 8098  /cust -> 8092")
    print("=" * 60)
    Server(("127.0.0.1", PORT), Handler).serve_forever()
