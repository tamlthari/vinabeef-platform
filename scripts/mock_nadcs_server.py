#!/usr/bin/env python3
"""A stand-in for the national animal-disease API, to test the NADCS transmit without the real agency.

    python3 scripts/mock_nadcs_server.py                 # 202 + receipt, listens on 127.0.0.1:9099
    python3 scripts/mock_nadcs_server.py --mode reject   # 422 schema error
    python3 scripts/mock_nadcs_server.py --mode error    # 500
    python3 scripts/mock_nadcs_server.py --mode slow     # answers after 10 s (the backend times out -> UNREACHABLE)
    python3 scripts/mock_nadcs_server.py --key test-key  # require this key in the X-API-Key header, else 401

Point the backend at it (restart the backend after changing these):
    NADCS_MODE=LIVE NADCS_ENDPOINT=http://127.0.0.1:9099/v2/surveillance/herd-health \
    NADCS_API_KEY=test-key NADCS_INSECURE_HOSTS=127.0.0.1

Every request is printed and the last 50 payloads are kept in ./nadcs_mock_received/ so you can open exactly what was sent.
This is NOT the real agency: DELIVERED against it proves the plumbing, not acceptance by the Department of Animal Health."""
import argparse
import json
import os
import time
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer

ap = argparse.ArgumentParser()
ap.add_argument('--host', default='127.0.0.1'); ap.add_argument('--port', type=int, default=9099)
ap.add_argument('--mode', choices=['accept', 'reject', 'error', 'slow'], default='accept')
ap.add_argument('--key', default='', help='required X-API-Key value (empty = any)')
ap.add_argument('--header', default='X-API-Key')
args = ap.parse_args()
OUT = 'nadcs_mock_received'; os.makedirs(OUT, exist_ok=True)

class H(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0))).decode('utf-8', 'replace')
        key = self.headers.get(args.header, '')
        kind = self.headers.get('Content-Type', '')
        records = '?'
        try:
            records = json.loads(body).get('recordCount', '?') if 'json' in kind else body.count('<Record>')
        except Exception: pass
        print(f'{time.strftime("%H:%M:%S")} POST {self.path} type={kind} bytes={len(body)} records={records} key={"ok" if key else "MISSING"}', flush=True)
        if args.key and key != args.key:
            return self.send(401, {'error': 'invalid or missing API key'})
        name = f'{OUT}/{int(time.time())}-{uuid.uuid4().hex[:6]}.{"xml" if "xml" in kind else "json"}'
        open(name, 'w').write(body)
        files = sorted(os.listdir(OUT))
        for f in files[:-50]: os.remove(os.path.join(OUT, f))
        if args.mode == 'reject': return self.send(422, {'error': 'schema validation failed', 'detail': 'mock rejection'})
        if args.mode == 'error': return self.send(500, {'error': 'internal error (mock)'})
        if args.mode == 'slow': time.sleep(10)
        self.send(202, {'status': 'ACCEPTED', 'receipt': 'MOCK-' + uuid.uuid4().hex[:8].upper(), 'records': records})
    def send(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
    def log_message(self, *a): pass

print(f'mock NADCS on http://{args.host}:{args.port}  mode={args.mode}  key={"required" if args.key else "any"}', flush=True)
HTTPServer((args.host, args.port), H).serve_forever()
