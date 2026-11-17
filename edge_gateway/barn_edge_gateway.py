import json
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
import httpx

DB_PATH = "edge_outbox.db"
CLOUD_BASE_URL = "http://localhost:8080/api/v1"
BATCH_SIZE = 100

def init_wal_db():
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    # Enable Write-Ahead Logging & FULL durability so zero records are lost on power/network drop
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA synchronous=FULL;")
    conn.execute("""
        CREATE TABLE IF NOT EXISTS edge_queue (
            seq_id INTEGER PRIMARY KEY AUTOINCREMENT,
            event_key INTEGER UNIQUE,
            hardware_ts INTEGER NOT NULL,
            payload_json TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'PENDING'
        );
    """)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_edge_status_ts ON edge_queue(status, hardware_ts ASC, seq_id ASC);")
    conn.commit()
    return conn

db_lock = threading.Lock()
db_conn = init_wal_db()

def enqueue_gate_read(event_dict: dict):
    event_key = int(event_dict.get("eventKey") or int(time.time() * 1000))
    hw_ts = int(event_dict.get("timestamp") or int(time.time() * 1000))
    event_dict["eventKey"] = event_key
    event_dict["timestamp"] = hw_ts

    with db_lock:
        db_conn.execute(
            "INSERT OR IGNORE INTO edge_queue (event_key, hardware_ts, payload_json, status) VALUES (?, ?, ?, 'PENDING')",
            (event_key, hw_ts, json.dumps(event_dict))
        )
        db_conn.commit()

class LocalGateIngestHandler(BaseHTTPRequestHandler):
    """Receives RFID gate reads over Barn LAN even when WAN internet is completely offline."""
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8")
        try:
            event = json.loads(raw)
            enqueue_gate_read(event)
            self.send_response(202)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"status":"BUFFERED_IN_EDGE_WAL"}')
        except Exception as e:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(str(e).encode("utf-8"))

    def log_message(self, format, *args):
        return

def wan_replay_worker():
    """Background daemon that drains SQLite WAL outbox chronologically when Cloud WAN is reachable."""
    with httpx.Client(timeout=5.0) as client:
        while True:
            try:
                # 1. Probe Cloud WAN reachability
                health = client.get(f"{CLOUD_BASE_URL}/ingestion/health")
                if health.status_code != 200:
                    time.sleep(3)
                    continue

                # 2. Fetch oldest PENDING events in strict chronological order
                with db_lock:
                    rows = db_conn.execute("""
                        SELECT seq_id, payload_json
                        FROM edge_queue
                        WHERE status = 'PENDING'
                        ORDER BY hardware_ts ASC, seq_id ASC
                        LIMIT ?
                    """, (BATCH_SIZE,)).fetchall()

                if not rows:
                    time.sleep(1.5)
                    continue

                seq_ids = [r[0] for r in rows]
                batch_payload = [json.loads(r[1]) for r in rows]

                # 3. Replay batch to Spring Boot idempotent endpoint
                resp = client.post(f"{CLOUD_BASE_URL}/ingestion/batch", json=batch_payload)
                if resp.status_code == 200:
                    with db_lock:
                        placeholders = ",".join("?" for _ in seq_ids)
                        db_conn.execute(f"DELETE FROM edge_queue WHERE seq_id IN ({placeholders})", seq_ids)
                        db_conn.commit()
                    print(f"[EDGE-SYNC] Replayed {len(seq_ids)} buffered gate reads -> Cloud OK")
                else:
                    print(f"[EDGE-SYNC] Cloud returned {resp.status_code}, retaining in WAL outbox.")
                    time.sleep(3)

            except Exception:
                # WAN offline: keep buffering locally without crashing
                with db_lock:
                    pending = db_conn.execute("SELECT COUNT(*) FROM edge_queue WHERE status = 'PENDING'").fetchone()[0]
                if pending > 0:
                    print(f"[EDGE-OFFLINE] WAN unreachable. {pending} gate events safely buffered in SQLite WAL.")
                time.sleep(3)

if __name__ == "__main__":
    threading.Thread(target=wan_replay_worker, daemon=True).start()
    server = HTTPServer(("0.0.0.0", 8090), LocalGateIngestHandler)
    print("Barn Edge Store-and-Forward Gateway running on LAN port 8090 (SQLite WAL active)...")
    server.serve_forever()