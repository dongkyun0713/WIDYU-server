#!/usr/bin/env python3
"""Deterministic AI/FCM HTTP stub for heart-rate load tests."""

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class StubState:
    def __init__(self, ai_mode: str, ai_delay_ms: int, fcm_delay_ms: int):
        self.ai_mode = ai_mode
        self.ai_delay_ms = ai_delay_ms
        self.fcm_delay_ms = fcm_delay_ms
        self.ai_requests = 0
        self.fcm_requests = 0
        self.lock = threading.Lock()

    def increment_ai(self):
        with self.lock:
            self.ai_requests += 1

    def increment_fcm(self):
        with self.lock:
            self.fcm_requests += 1

    def snapshot(self):
        with self.lock:
            return {
                "ai_mode": self.ai_mode,
                "ai_requests": self.ai_requests,
                "fcm_requests": self.fcm_requests,
            }


class StubHandler(BaseHTTPRequestHandler):
    server_version = "WidyuLoadTestStub/1.0"

    def do_GET(self):
        if self.path == "/health":
            self.respond(200, {"status": "UP"})
            return
        if self.path == "/metrics":
            self.respond(200, self.server.state.snapshot())
            return
        self.respond(404, {"error": "not_found"})

    def do_POST(self):
        if self.path == "/api/hr":
            self.handle_ai()
            return
        if self.path.endswith("/messages:send"):
            self.handle_fcm()
            return
        self.respond(404, {"error": "not_found"})

    def handle_ai(self):
        body = self.read_json()
        if body is None:
            return
        time.sleep(self.server.state.ai_delay_ms / 1000)
        self.server.state.increment_ai()
        emergency = self.server.state.ai_mode == "emergency"
        self.respond(200, {
            "alert": emergency,
            "level": "EMERGENCY" if emergency else "NORMAL",
            "reason": "tachycardia" if emergency else "normal",
            "layer": "L0",
            "baseline_source": "PRIOR",
            "sample_count": 1,
            "bpm": body.get("bpm"),
            "context": body.get("context"),
            "timestamp": body.get("timestamp"),
        })

    def handle_fcm(self):
        if self.read_json() is None:
            return
        time.sleep(self.server.state.fcm_delay_ms / 1000)
        self.server.state.increment_fcm()
        self.respond(200, {"name": "projects/widyu-load-test/messages/stub"})

    def read_json(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            return json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError):
            self.respond(400, {"error": "invalid_json"})
            return None

    def respond(self, status, body):
        payload = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, _format, *_args):
        return


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5001)
    parser.add_argument("--ai-mode", choices=("normal", "emergency"), default="normal")
    parser.add_argument("--ai-delay-ms", type=int, default=0)
    parser.add_argument("--fcm-delay-ms", type=int, default=0)
    return parser.parse_args()


def main():
    args = parse_args()
    server = ThreadingHTTPServer((args.host, args.port), StubHandler)
    server.state = StubState(args.ai_mode, args.ai_delay_ms, args.fcm_delay_ms)
    print(f"stub listening on http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()


if __name__ == "__main__":
    main()
