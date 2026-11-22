"""Fake VinaBeef ingestion API. Mode is read from the file given as argv[2] on every request:
   ok | down503 | hang | health_ok_write_503. Every accepted event is appended to argv[3] as JSON lines."""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

port, modefile, outfile = int(sys.argv[1]), sys.argv[2], sys.argv[3]
seen = set()
def mode(): return open(modefile).read().strip()
class H(BaseHTTPRequestHandler):
    def _send(self, code, body):
        d = json.dumps(body).encode(); self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(d))); self.end_headers(); self.wfile.write(d)
    def do_GET(self):
        m = mode()
        if m == "hang": time.sleep(30)
        self._send(503 if m == "down503" else 200, {"status": "x"})
    def do_POST(self):
        m = mode(); body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        if m == "hang": time.sleep(30)
        if m in ("down503", "health_ok_write_503"): return self._send(503, {"error": "db down"})
        with open(outfile, "a") as f:
            for e in body:
                dup = e["EVENT_KEY"] in seen; seen.add(e["EVENT_KEY"])
                f.write(json.dumps({"key": e["EVENT_KEY"], "ts": e["TIMESTAMP"], "dup": dup, "at": time.time()}) + "\n")
        self._send(200, {"accepted": len(body)})
    def log_message(self, *a): pass
ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
