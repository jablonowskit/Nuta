#!/usr/bin/env python3
import os
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SCREENSHOTS = Path("/artifacts/screenshots")
SCREENSHOTS.mkdir(parents=True, exist_ok=True)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"ok")
            return

        if self.path != "/screenshot":
            self.send_error(404)
            return

        target = SCREENSHOTS / f"nuta-{int(time.time() * 1000)}.png"
        env = dict(os.environ)
        env["DISPLAY"] = env.get("DISPLAY", ":99")
        try:
            subprocess.run(["scrot", "-o", str(target)], env=env, check=True, timeout=10)
            payload = target.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(payload)
        except Exception as error:
            self.send_error(500, str(error))

    def log_message(self, format, *args):
        print(f"screenshot-server: {format % args}", flush=True)


ThreadingHTTPServer(("0.0.0.0", 6081), Handler).serve_forever()
