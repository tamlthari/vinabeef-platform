"""Tests for the read-only web console: what the forwarder records for it, what the API returns, how it is protected,
and that it can never change the store.  Run with:  python -m pytest -q edge_gateway"""
import json
import os
import re
import shutil
import socket
import sqlite3
import ssl
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, os.path.dirname(__file__))
import barn_edge_gateway as edge  # noqa: E402
import edge_console  # noqa: E402
from edge_console import ConsoleApi, SyncJournal, run_probe  # noqa: E402

HERE = Path(__file__).parent
BASE = "http://backend.test/api/v1"


def card_event(key, ts, card="044E6602AD1490", reader="DrC0F8"):
    return {"EVENT_KEY": key, "TIMESTAMP": ts, "CARD_UUID": card, "READER_NAME": reader, "NOTES": {"ACTION": "OPEN_DOOR"}}


def make_gateway(tmp_path, **cfg):
    base = dict(db_path=str(tmp_path / "q.db"), cloud_base_url=BASE, max_attempts=3, batch_size=50,
                bind_host="127.0.0.1", bind_port=0, local_readers="DrC0F8")
    base.update(cfg)
    return edge.Gateway(edge.Config(**base))


@pytest.fixture
def gw(tmp_path):
    return make_gateway(tmp_path)


def queue(gw, *events):
    for e in events:
        gw.accept_event(e, json.dumps(e))


def put_row(gw, key, ts, status, attempts=0, error=None, payload=None):
    """A row in a state the forwarder reaches only after a while (DEAD) or only for USB swipes (HELD)."""
    payload = payload or card_event(key, ts)
    with gw.outbox.lock:
        gw.outbox.db.execute("INSERT INTO edge_queue (event_key, hardware_ts, payload_json, status, attempts, last_error, is_card, created_at) "
                             "VALUES (?,?,?,?,?,?,1,?)", (key, ts, json.dumps(payload), status, attempts, error, ts))
        gw.outbox.db.commit()


def client_for(handler):
    return httpx.Client(transport=httpx.MockTransport(handler))


@pytest.fixture
def no_proxy(monkeypatch):
    monkeypatch.setattr(edge_console, "getproxies", lambda: {})


@pytest.fixture
def served(request, tmp_path):
    """A real gateway HTTP server on an ephemeral port. Returns (gateway, base_url)."""
    cfg = getattr(request, "param", {})
    gateway = make_gateway(tmp_path, **cfg)
    server = gateway.http_server()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield gateway, f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()
    server.server_close()


def http(method, url, **kw):
    with httpx.Client(trust_env=False, timeout=10) as c:
        return c.request(method, url, **kw)


# ---- the journal ------------------------------------------------------------------------------------------
class Clock:
    def __init__(self, now=36_005.0):
        self.now = now

    def __call__(self):
        return self.now


def test_journal_buckets_events_per_minute_and_forgets_old_ones():
    clock = Clock()
    j = SyncJournal(clock)
    j.received(3)
    j.forwarded(2, 40)
    clock.now += 60
    j.received()
    snap = j.snapshot()
    assert len(snap["perMinute"]) == 30
    assert snap["perMinute"][-1] == {"t": int(clock.now // 60) * 60_000, "received": 1, "sent": 0, "rejected": 0}
    assert snap["perMinute"][-2]["received"] == 3 and snap["perMinute"][-2]["sent"] == 2
    clock.now += 3 * 3600
    j.received()
    assert len(j.minutes) <= 2                      # the minutes of three hours ago are gone


def test_journal_folds_the_same_failure_into_one_line_with_a_count():
    clock = Clock()
    j = SyncJournal(clock)
    for _ in range(5):
        clock.now += 2
        j.retry("The cloud cannot be reached.")
    j.retry("Something else.")
    recent = j.snapshot()["recent"]
    assert [(e["text"], e["repeat"]) for e in recent] == [("Something else.", 1), ("The cloud cannot be reached.", 5)]
    assert j.snapshot()["failStreak"] == 6


def test_journal_streak_resets_when_events_are_sent_or_the_link_is_idle_and_healthy():
    j = SyncJournal(Clock())
    j.retry("x")
    j.retry("y")
    j.forwarded(1, 5)
    assert j.snapshot()["failStreak"] == 0
    j.retry("z")
    j.healthy()
    assert j.snapshot()["failStreak"] == 0


def test_journal_a_recovery_reads_before_the_events_it_then_sent():
    clock = Clock()
    j = SyncJournal(clock)
    clock.now += 5
    answered_at = clock.now
    clock.now += 0.2
    j.forwarded(4, 12)                              # logged first, because the send finishes first
    j.link_changed("OFFLINE", "ONLINE", at=answered_at)
    kinds = [e["kind"] for e in j.snapshot()["recent"]]
    assert kinds == ["SENT", "LINK"]


def test_journal_nothing_to_report_for_zero_sent():
    j = SyncJournal(Clock())
    j.forwarded(0, 3)
    assert j.snapshot()["recent"] == []


# ---- what the forwarder records ---------------------------------------------------------------------------
def test_forwarder_records_outage_and_recovery(gw):
    queue(gw, card_event(1, 1_000), card_event(2, 2_000))

    def down(request):
        raise httpx.ConnectError("no route")

    assert gw.forward_once(client_for(down)) == "RETRY"
    snap = gw.journal.snapshot()
    assert snap["link"]["state"] == "OFFLINE" and "unreachable" in snap["link"]["why"]
    assert snap["lastProbe"]["ok"] is False and snap["lastProbe"]["error"] == "ConnectError"
    assert snap["failStreak"] == 1

    def up(request):
        return httpx.Response(200, json={})

    assert gw.forward_once(client_for(up)) == "SENT"
    snap = gw.journal.snapshot()
    assert snap["link"]["state"] == "ONLINE" and snap["failStreak"] == 0 and snap["lastProbe"]["ok"] is True
    assert [e["kind"] for e in snap["recent"]] == ["SENT", "LINK", "RETRY", "LINK"]
    assert snap["recent"][0]["n"] == 2
    assert sum(m["sent"] for m in snap["perMinute"]) == 2


def test_forwarder_records_an_unhealthy_cloud_without_touching_the_queue(gw):
    queue(gw, card_event(1, 1_000))
    assert gw.forward_once(client_for(lambda r: httpx.Response(503))) == "RETRY"
    snap = gw.journal.snapshot()
    assert snap["link"]["state"] == "DEGRADED"
    assert snap["lastProbe"]["http"] == 503
    assert gw.outbox.stats()["pending"] == 1


def test_forwarder_records_events_the_cloud_refuses(gw):
    queue(gw, card_event(1, 1_000), card_event(2, 2_000))

    def handler(request):
        if request.url.path.endswith("/health"):
            return httpx.Response(200)
        return httpx.Response(207, json={"results": [{"eventKey": 1, "status": "OK"}, {"eventKey": 2, "status": "ERROR", "error": "unknown card"}]})

    assert gw.forward_once(client_for(handler)) == "RETRY"
    recent = gw.journal.snapshot()["recent"]
    refused = [e for e in recent if e["kind"] == "REJECTED"]
    assert len(refused) == 1 and "unknown card" in refused[0]["text"] and "Event 2" in refused[0]["text"]
    assert any(e["kind"] == "SENT" and e["n"] == 1 for e in recent)


def test_intake_and_usb_swipes_count_as_received_once_they_are_really_queued(gw):
    queue(gw, card_event(1, 1_000))
    queue(gw, card_event(1, 1_000))                 # a duplicate is not a new event
    gw.accept_local("DrC0F8", "usb0", "38")         # MQTT is down: queued at once
    assert sum(m["received"] for m in gw.journal.snapshot()["perMinute"]) == 2

    gw.mqtt_connected = True
    gw.accept_local("DrC0F8", "usb0", "39")         # held for the reader's own copy: not counted yet
    assert sum(m["received"] for m in gw.journal.snapshot()["perMinute"]) == 2
    gw.cfg.local_hold_ms = 0
    time.sleep(0.01)
    released = gw.local.release_due(int(time.time() * 1000) + 60_000)
    gw.journal.received(released)
    assert sum(m["received"] for m in gw.journal.snapshot()["perMinute"]) == 3


def test_status_endpoint_is_unchanged(served):
    gateway, url = served
    body = http("GET", f"{url}/status").json()
    assert set(body) == {"wan", "mqttConnected", "queue", "lastForwardedAgeSeconds", "counters", "local"}
    assert set(body["queue"]) == {"pending", "dead", "held", "oldestPendingAgeSeconds"}


# ---- overview ---------------------------------------------------------------------------------------------
def test_overview_reports_the_link_and_the_store(tmp_path):
    gw = make_gateway(tmp_path, cloud_base_url="https://user:secret@cloud.example.com:8443/api/v1",
                      mqtt_url="mqtts://mq-user:mq-pass@broker.example.com:8883")
    now_ms = int(time.time() * 1000)
    queue(gw, card_event(1, now_ms - 90_000), card_event(2, now_ms - 30_000))
    put_row(gw, 3, now_ms - 5_000, "DEAD", 3, "HTTP 422")
    put_row(gw, 4, now_ms - 4_000, "HELD")
    ov = ConsoleApi(gw).overview()
    assert ov["store"]["pending"] == 2 and ov["store"]["held"] == 1 and ov["store"]["dead"] == 1 and ov["store"]["total"] == 4
    assert 85 <= ov["store"]["oldestPendingAgeS"] <= 100
    assert ov["store"]["dbBytes"] > 0 and ov["store"]["diskFreeBytes"] > 0
    assert ov["cloud"]["state"] == "UNKNOWN" and ov["cloud"]["host"] == "cloud.example.com"
    assert (ov["mqtt"]["configured"], ov["mqtt"]["connected"], ov["mqtt"]["broker"]) == (True, False, "broker.example.com")
    assert ov["mqtt"]["state"] == "CONNECTING" and ov["mqtt"]["url"] == "mqtts://broker.example.com:8883"
    assert ov["gateway"]["name"] == "Gateway 1" and ov["gateway"]["consoleProtected"] is False
    dumped = json.dumps(ov)
    assert "secret" not in dumped and "mq-pass" not in dumped and "mq-user" not in dumped     # no credentials leave the gateway


def test_overview_estimates_when_the_store_will_be_empty(gw):
    now_ms = int(time.time() * 1000)
    queue(gw, *[card_event(i, now_ms - 1000 + i) for i in range(1, 11)])
    gw.wan_state = "ONLINE"
    gw.journal.forwarded(30, 10)                     # 30 events in the last minute
    ov = ConsoleApi(gw).overview()
    assert ov["store"]["sendRatePerMin"] == 30.0 and ov["store"]["emptyInS"] == 20
    gw.wan_state = "OFFLINE"
    assert ConsoleApi(gw).overview()["store"]["emptyInS"] is None


# ---- browsing the store -----------------------------------------------------------------------------------
@pytest.fixture
def filled(gw):
    base = int(time.time() * 1000) - 600_000
    for i in range(1, 8):
        queue(gw, card_event(100 + i, base + i * 1000, card=f"AABB{i:02d}CCDD", reader="DrC0F8" if i % 2 else "BarnEast"))
    put_row(gw, 200, base + 100, "DEAD", 3, "HTTP 422", card_event(200, base + 100, card="DEAD00BEEF"))
    put_row(gw, 201, base + 200, "HELD", payload={"EVENT_KEY": 201, "TIMESTAMP": base + 200, "CREDENTIAL_IDENTIFIER": "1042",
                                                   "READER_NAME": "DrC0F8", "EDGE_SOURCE": "LOCAL_USB", "EDGE_DEVICE": "usb0"})
    return gw, ConsoleApi(gw)


def test_queue_filters_by_state_and_counts_the_whole_set(filled):
    gw, api = filled
    assert api.queue({"status": "ALL"})["total"] == 9
    pending = api.queue({"status": "pending"})
    assert pending["total"] == 7 and {r["status"] for r in pending["rows"]} == {"PENDING"}
    dead = api.queue({"status": "DEAD"})["rows"]
    assert len(dead) == 1 and dead[0]["lastError"] == "HTTP 422" and dead[0]["attempts"] == 3
    held = api.queue({"status": "HELD"})["rows"][0]
    assert held["kind"] == "usb" and held["credential"] == "1042" and held["device"] == "usb0"


def test_queue_order_is_the_send_order_and_can_be_reversed(filled):
    _, api = filled
    oldest = [r["eventKey"] for r in api.queue({"status": "PENDING"})["rows"]]
    newest = [r["eventKey"] for r in api.queue({"status": "PENDING", "order": "newest"})["rows"]]
    assert oldest == [101, 102, 103, 104, 105, 106, 107] and newest == oldest[::-1]
    sent_order = [r[1] for r in api.gw.outbox.next_batch(50)]       # the order the forwarder really uses
    assert sent_order == oldest


def test_queue_pages(filled):
    _, api = filled
    first = api.queue({"status": "PENDING", "limit": "3"})
    second = api.queue({"status": "PENDING", "limit": "3", "offset": "3"})
    last = api.queue({"status": "PENDING", "limit": "3", "offset": "6"})
    assert (first["total"], len(first["rows"]), len(second["rows"]), len(last["rows"])) == (7, 3, 3, 1)
    assert [r["eventKey"] for r in first["rows"] + second["rows"] + last["rows"]] == [101, 102, 103, 104, 105, 106, 107]
    assert api.queue({"limit": "100000"})["limit"] == 200        # a request cannot ask for the whole store


def test_queue_search_by_card_reader_and_event_number(filled):
    _, api = filled
    assert [r["eventKey"] for r in api.queue({"q": "aabb03"})["rows"]] == [103]          # case does not matter for card text
    assert api.queue({"q": "BarnEast"})["total"] == 3
    assert [r["eventKey"] for r in api.queue({"q": "105"})["rows"]] == [105]             # an event number
    assert [r["eventKey"] for r in api.queue({"q": "1042"})["rows"]] == [201]            # a credential number
    assert api.queue({"q": "no such thing"})["total"] == 0


def test_queue_search_does_not_match_numbers_buried_in_timestamps(gw):
    queue(gw, card_event(7, 1_234_567_890_123))
    api = ConsoleApi(gw)
    assert api.queue({"q": "4567890"})["total"] == 0 and api.queue({"q": "044E66"})["total"] == 1


def test_queue_search_still_works_without_sqlite_json_functions(filled):
    _, api = filled
    api._json_ok = False
    assert [r["eventKey"] for r in api.queue({"q": "aabb03"})["rows"]] == [103]


@pytest.mark.parametrize("text", ["%", "_", "\\", "'; DROP TABLE edge_queue; --", "\" OR 1=1"])
def test_queue_search_treats_what_you_type_as_text_not_as_a_pattern_or_sql(filled, text):
    _, api = filled
    assert api.queue({"q": text})["total"] == 0
    assert api.queue({})["total"] == 9                                                   # and the table is still there


def test_queue_rejects_bad_parameters(filled):
    gw, api = filled
    for query in ({"status": "SENT"}, {"order": "random"}, {"limit": "x"}, {"offset": "-"}):
        with pytest.raises(ValueError):
            api.queue(query)
    status, body = api.handle("GET", "/api/queue?status=SENT", {})
    assert status == 400 and "status" in body["error"]


def test_event_detail(filled):
    _, api = filled
    seq = api.queue({"q": "AABB02CCDD"})["rows"][0]["id"]
    status, ev = api.handle("GET", f"/api/queue/{seq}", {})
    assert status == 200 and ev["eventKey"] == 102 and ev["maxAttempts"] == 3
    assert json.loads(ev["payload"])["CARD_UUID"] == "AABB02CCDD" and ev["payloadCut"] is False
    assert api.handle("GET", "/api/queue/99999", {})[0] == 404
    assert api.handle("GET", "/api/queue/abc", {})[0] == 400


def test_text_from_events_comes_back_as_data_only(gw):
    evil = "<script>alert(1)</script><img src=x onerror=alert(2)>"
    queue(gw, card_event(1, 1_000, reader=evil))
    api = ConsoleApi(gw)
    row = api.queue({})["rows"][0]
    assert row["reader"] == evil                      # untouched: the page shows it with textContent, never as markup


# ---- read-only --------------------------------------------------------------------------------------------
def test_the_console_connection_cannot_write(filled):
    gw, api = filled
    with api._db() as db:
        for sql in ("DELETE FROM edge_queue", "UPDATE edge_queue SET status = 'DEAD'", "DROP TABLE edge_queue",
                    "INSERT INTO edge_meta VALUES ('k', 'v')"):
            with pytest.raises(sqlite3.OperationalError):
                db.execute(sql)
    assert gw.outbox.stats()["pending"] == 7


def test_browsing_never_waits_for_a_write_in_progress(gw):
    """The forwarder and the intake write all day. While one is mid-transaction the console still answers at once,
    and sees only what has been committed."""
    queue(gw, card_event(1, 1_000))
    with gw.outbox.lock:
        gw.outbox.db.execute("BEGIN IMMEDIATE")
        gw.outbox.db.execute("INSERT INTO edge_queue (event_key, hardware_ts, payload_json, created_at) VALUES (2, 2000, '{}', 0)")
        started = time.monotonic()
        total = ConsoleApi(gw).queue({})["total"]
        took = time.monotonic() - started
        gw.outbox.db.rollback()
    assert total == 1 and took < 1.0


# ---- protection and the page ------------------------------------------------------------------------------
def test_page_is_served_with_a_fresh_nonce_and_a_strict_policy(served):
    _, url = served
    a, b = http("GET", f"{url}/"), http("GET", f"{url}/console")
    assert a.status_code == 200 and a.headers["content-type"].startswith("text/html")
    nonce = re.search(r"script-src 'nonce-([\w-]+)'", a.headers["content-security-policy"]).group(1)
    assert f'<script nonce="{nonce}">' in a.text and f'<style nonce="{nonce}">' in a.text and "{{NONCE}}" not in a.text
    assert nonce != re.search(r"script-src 'nonce-([\w-]+)'", b.headers["content-security-policy"]).group(1)
    csp = a.headers["content-security-policy"]
    for part in ("default-src 'none'", "connect-src 'self'", "frame-ancestors 'none'", "base-uri 'none'", "form-action 'none'"):
        assert part in csp
    assert "unsafe-inline" not in csp and "unsafe-eval" not in csp
    assert a.headers["x-content-type-options"] == "nosniff" and a.headers["cache-control"] == "no-store"
    assert a.headers["referrer-policy"] == "no-referrer"


def test_page_source_never_builds_markup_from_data_and_loads_nothing_from_outside():
    html = (HERE / "console.html").read_text(encoding="utf-8")
    for banned in ("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function", "setAttribute('style'", ' style="'):
        assert banned not in html, banned
    assert not re.search(r"""\bstyle:\s*['"]""", html), "an inline style set from script would be blocked by the policy"
    assert not re.search(r"""(src|href|action)\s*=\s*["']\s*(https?:)?//""", html), "external reference"
    assert not re.search(r"url\(\s*['\"]?(https?:)?//", html), "external stylesheet asset"
    assert "@import" not in html and "<link rel=\"stylesheet\"" not in html


@pytest.mark.parametrize("served", [{"console_token": "s3cret"}], indirect=True)
def test_console_api_needs_the_token_when_one_is_set(served):
    _, url = served
    assert http("GET", f"{url}/").status_code == 200                                          # the page holds no data
    assert http("GET", f"{url}/api/overview").status_code == 401
    assert http("GET", f"{url}/api/overview", headers={"X-Edge-Token": "wrong"}).status_code == 401
    assert http("GET", f"{url}/api/queue", headers={"X-Edge-Token": "wrong"}).status_code == 401
    assert http("POST", f"{url}/api/probe").status_code == 401
    assert http("GET", f"{url}/api/overview", headers={"X-Edge-Token": "s3cret"}).status_code == 200
    assert http("GET", f"{url}/api/overview", headers={"X-Edge-Token": "sécret-ünïcode".encode()}).status_code == 401   # no crash on non-ASCII


@pytest.mark.parametrize("served", [{"ingest_token": "ingest-only"}], indirect=True)
def test_the_two_tokens_are_independent(served):
    gateway, url = served
    assert http("POST", f"{url}/", json=card_event(1, 1_000)).status_code == 401                # intake still needs its token
    assert http("POST", f"{url}/", json=card_event(1, 1_000), headers={"X-Edge-Token": "ingest-only"}).status_code == 202
    assert http("GET", f"{url}/api/overview").status_code == 200                                # console not protected: open
    gateway.cfg.console_token = "c"
    assert http("GET", f"{url}/api/overview", headers={"X-Edge-Token": "ingest-only"}).status_code == 401   # one token is not the other


def test_the_console_has_no_way_to_change_anything(served):
    gateway, url = served
    queue(gateway, card_event(1, 1_000))
    for method in ("POST", "PUT", "DELETE", "PATCH"):
        r = http(method, f"{url}/api/queue")
        assert r.status_code in (404, 405, 501)
    assert http("GET", f"{url}/api/queue/1/retry").status_code == 404
    assert gateway.outbox.stats()["pending"] == 1


def test_api_answers_over_http(served):
    gateway, url = served
    queue(gateway, card_event(1, 1_000, reader="<b>x</b>"))
    ov = http("GET", f"{url}/api/overview")
    assert ov.headers["content-type"] == "application/json" and ov.json()["store"]["pending"] == 1
    q = http("GET", f"{url}/api/queue", params={"status": "PENDING", "q": "<b>"}).json()
    assert q["total"] == 1 and q["rows"][0]["reader"] == "<b>x</b>"
    assert http("GET", f"{url}/api/nothing").status_code == 404


# ---- the connection check ---------------------------------------------------------------------------------
class Cloud:
    """A throwaway server standing in for the cloud."""

    def __init__(self, status=200, tls=None):
        outer = self

        class H(BaseHTTPRequestHandler):
            def do_GET(self):
                outer.hits += 1
                self.send_response(outer.status)
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"{}")

            def log_message(self, *a):
                pass

        self.status, self.hits = status, 0
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        if tls:
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(*tls)
            self.server.socket = ctx.wrap_socket(self.server.socket, server_side=True)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.port = self.server.server_address[1]

    def url(self, scheme="http"):
        return f"{scheme}://127.0.0.1:{self.port}/api/v1/ingestion/health"

    def close(self):
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def cloud():
    c = Cloud()
    yield c
    c.close()


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def test_probe_walks_the_path_and_reports_each_step(no_proxy, cloud):
    result = run_probe(cloud.url())
    assert result["ok"] is True and cloud.hits == 1
    assert [(s["name"], s["ok"]) for s in result["steps"]] == [("dns", True), ("tcp", True), ("tls", True), ("health", True)]
    assert next(s for s in result["steps"] if s["name"] == "tls")["skipped"] is True        # plain http: no handshake
    assert "reachable" in result["hint"] and all(isinstance(s["ms"], int) for s in result["steps"])


def test_probe_says_when_the_cloud_is_up_but_unhealthy(no_proxy, cloud):
    cloud.status = 503
    result = run_probe(cloud.url())
    assert result["ok"] is False and result["healthHttp"] == 503
    health = result["steps"][-1]
    assert health["name"] == "health" and health["ok"] is False and "503" in health["error"]
    assert "503" in result["hint"] and "nothing is lost" in result["hint"]


def test_probe_stops_at_the_first_step_that_fails(no_proxy):
    result = run_probe(f"http://127.0.0.1:{free_port()}/api/v1/ingestion/health")
    names = {s["name"]: s["ok"] for s in result["steps"]}
    assert names == {"dns": True, "tcp": False, "tls": None, "health": None} or names == {"dns": True, "tcp": False, "health": None}
    assert result["ok"] is False and "connection" in result["hint"]


def test_probe_names_an_unknown_host(no_proxy):
    result = run_probe("http://no-such-host.invalid/api/v1/ingestion/health", timeout=3)
    assert result["steps"][0]["name"] == "dns" and result["steps"][0]["ok"] is False
    assert "address" in result["hint"]


@pytest.mark.skipif(shutil.which("openssl") is None, reason="openssl is needed to make a throwaway certificate")
def test_probe_reports_a_failed_secure_handshake(no_proxy, tmp_path):
    key, crt = str(tmp_path / "k.pem"), str(tmp_path / "c.pem")
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", crt, "-subj", "/CN=localhost",
                    "-days", "1"], check=True, capture_output=True)
    cloud = Cloud(tls=(crt, key))
    try:
        result = run_probe(cloud.url("https"))
    finally:
        cloud.close()
    steps = {s["name"]: s["ok"] for s in result["steps"]}
    assert steps["tcp"] is True and steps["tls"] is False and steps["health"] is None
    assert "handshake" in result["hint"] and "date and time" in result["hint"]


def test_probe_goes_through_a_proxy_only_when_the_forwarder_would(monkeypatch, cloud):
    monkeypatch.setattr(edge_console, "getproxies", lambda: {"http": "http://proxy.test:3128"})
    monkeypatch.setattr(edge_console, "proxy_bypass", lambda host: host == "127.0.0.1")
    assert run_probe(cloud.url())["viaProxy"] is False                                       # NO_PROXY covers the host
    monkeypatch.setattr(edge_console, "proxy_bypass", lambda host: False)
    result = run_probe(cloud.url(), timeout=1)
    assert result["viaProxy"] is True and result["steps"][0]["name"] == "proxy" and result["steps"][0]["skipped"] is True


def test_probe_only_ever_asks_the_configured_cloud_and_is_rate_limited(no_proxy, tmp_path, cloud):
    gw = make_gateway(tmp_path, cloud_base_url=f"http://127.0.0.1:{cloud.port}/api/v1")
    clock = Clock(1000.0)
    api = ConsoleApi(gw, clock)
    status, result = api.handle("POST", "/api/probe?url=http://169.254.169.254/", {})          # a caller cannot pick the target
    assert status == 200 and result["url"] == f"http://127.0.0.1:{cloud.port}/api/v1/ingestion/health" and cloud.hits == 1
    status, body = api.handle("POST", "/api/probe", {})
    assert status == 429 and body["retryInS"] >= 1 and cloud.hits == 1
    clock.now += 3
    assert api.handle("POST", "/api/probe", {})[0] == 200 and cloud.hits == 2


def test_probe_does_not_touch_the_queue_or_the_link_state(no_proxy, tmp_path, cloud):
    gw = make_gateway(tmp_path, cloud_base_url=f"http://127.0.0.1:{cloud.port}/api/v1")
    queue(gw, card_event(1, 1_000))
    before = (gw.wan_state, gw.outbox.stats(), dict(gw.counters), gw.journal.snapshot()["recent"])
    ConsoleApi(gw).probe()
    assert (gw.wan_state, gw.outbox.stats(), dict(gw.counters), gw.journal.snapshot()["recent"]) == before


# ---- packaging --------------------------------------------------------------------------------------------
def test_the_image_ships_the_console():
    dockerfile = (HERE / "Dockerfile").read_text()
    assert "edge_console.py" in dockerfile and "console.html" in dockerfile


def test_a_surprise_inside_the_console_answers_500_and_not_a_dropped_connection(served, monkeypatch):
    gateway, url = served
    monkeypatch.setattr(ConsoleApi, "overview", lambda self: 1 / 0)
    r = http("GET", f"{url}/api/overview")
    assert r.status_code == 500 and "gateway log" in r.json()["error"] and r.headers["cache-control"] == "no-store"
    assert http("GET", f"{url}/health").json() == {"status": "UP"}               # the rest of the gateway is untouched


def test_console_shows_the_card_of_a_mapped_usb_swipe_and_how_many_numbers_are_mapped(tmp_path):
    gw = make_gateway(tmp_path, local_credential_map="38=044E6602AD1490,36=042FAC02AD1490")
    gw.accept_local("DrC0F8", "stdin", "38")
    api = ConsoleApi(gw)
    row = api.queue({})["rows"][0]
    assert (row["kind"], row["card"], row["credential"], row["reader"]) == ("usb", "044E6602AD1490", "38", "DrC0F8")
    assert api.overview()["local"]["mappedCredentials"] == 2
    assert api.queue({"q": "044E66"})["total"] == 1 and api.queue({"q": "38"})["total"] == 1     # find it by card or by number


# ---- the MQTT reader feed ---------------------------------------------------------------------------------
def feed(source):
    return (source.snapshot() if isinstance(source, SyncJournal) else source.journal.snapshot())["mqtt"]


class FakeSubscribe:
    """What paho's client.subscribe does as far as the gateway is concerned: remember the request, return (result, id)."""

    def __init__(self):
        self.calls = []

    def __call__(self, topic, qos=0):
        self.calls.append((topic, qos))
        return 0, len(self.calls)


def mqtt_gateway(tmp_path, **cfg):
    base = dict(mqtt_url="mqtt://broker.test:1883", mqtt_topics="a/1, b/2", mqtt_qos=1)
    base.update(cfg)
    return make_gateway(tmp_path, **base)


def test_journal_the_feed_is_unused_until_mqtt_is_configured_then_connecting():
    j = SyncJournal(Clock())
    assert feed(j)["state"] == "NOT_CONFIGURED" and feed(j)["topics"] == []
    j.mqtt_setup(["a/1"])
    assert feed(j)["state"] == "CONNECTING" and feed(j)["topics"] == [{"topic": "a/1", "state": "pending", "qos": None}]


def test_journal_follows_connect_subscribe_message_and_loss():
    clock = Clock(1000.0)
    j = SyncJournal(clock)
    j.mqtt_setup(["a/1", "b/2"])
    clock.now += 5
    j.mqtt_up()
    j.mqtt_subscribed("a/1", 1)
    j.mqtt_subscribed("b/2", 128)
    f = feed(j)
    assert f["state"] == "CONNECTED" and f["connects"] == 1 and f["sinceAgeS"] == 0
    assert f["topics"] == [{"topic": "a/1", "state": "granted", "qos": 1}, {"topic": "b/2", "state": "refused", "qos": None}]
    clock.now += 10
    j.mqtt_message(True)
    j.mqtt_message(False)
    clock.now += 7
    f = feed(j)
    assert (f["messages"], f["events"], f["lastMessageAgeS"]) == (2, 1, 7)
    j.mqtt_down(7, "gone")
    f = feed(j)
    assert f["state"] == "DISCONNECTED" and f["why"] == "gone" and f["code"] == 7 and f["drops5min"] == 1
    j.mqtt_up()                                                  # back: every subscription is pending again until the broker confirms
    assert feed(j)["connects"] == 2 and all(t["state"] == "pending" for t in feed(j)["topics"])
    texts = [e["text"] for e in j.snapshot()["recent"] if e["kind"] == "FEED"]
    assert texts[:2] == ["Reader feed connected", "Reader feed lost: gone"]


def test_journal_the_same_refusal_again_keeps_its_start_time_and_folds_even_between_cloud_retries():
    clock = Clock(1000.0)
    j = SyncJournal(clock)
    j.mqtt_setup(["a/1"])
    for i in range(4):
        clock.now += 2
        j.mqtt_refused(4, "The broker refused the user name or password.", "Check MQTT_PASSWORD")
        j.retry("The cloud cannot be reached.")                  # the cloud's own failures must not keep the lines apart
    f = feed(j)
    assert f["state"] == "REFUSED" and f["attempts"] == 4 and f["sinceAgeS"] == 6 and f["hint"] == "Check MQTT_PASSWORD"
    lines = [e for e in j.snapshot()["recent"] if e["kind"] == "FEED"]
    assert len(lines) == 1 and lines[0]["repeat"] == 4 and lines[0]["tone"] == "bad"


def test_journal_a_failed_attempt_is_a_try_and_after_a_refusal_it_means_nobody_answers_any_more():
    j = SyncJournal(Clock())
    j.mqtt_setup(["a/1"])
    j.mqtt_attempt_failed()
    j.mqtt_attempt_failed()
    assert feed(j)["state"] == "CONNECTING" and feed(j)["attempts"] == 2 and feed(j)["why"] == "No answer from the broker."
    j.mqtt_refused(4, "refused")
    j.mqtt_attempt_failed()
    assert feed(j)["state"] == "DISCONNECTED" and feed(j)["why"] == "No answer from the broker."
    j.mqtt_up()
    j.mqtt_attempt_failed()                                      # a live connection is not a failed attempt
    assert feed(j)["state"] == "CONNECTED"


def test_journal_three_losses_within_five_minutes_count_as_flapping():
    clock = Clock(1000.0)
    j = SyncJournal(clock)
    j.mqtt_setup(["a/1"])
    for _ in range(3):
        j.mqtt_up()
        clock.now += 20
        j.mqtt_down(7, "lost")
    assert feed(j)["flapping"] is True and feed(j)["drops5min"] == 3
    clock.now += 400
    assert feed(j)["flapping"] is False                          # the old losses age out


def test_gateway_login_and_subscription_follow_the_brokers_answers(tmp_path):
    gw, sub = mqtt_gateway(tmp_path), FakeSubscribe()
    assert gw.mqtt_connected is False and feed(gw)["state"] == "CONNECTING"
    gw.mqtt_on_connect(0, sub)
    assert gw.mqtt_connected is True and feed(gw)["state"] == "CONNECTED"
    assert sub.calls == [("a/1", 1), ("b/2", 1)]
    gw.mqtt_on_subscribe(1, [1], sub)
    gw.mqtt_on_subscribe(2, [1], sub)
    assert [(t["topic"], t["state"], t["qos"]) for t in feed(gw)["topics"]] == [("a/1", "granted", 1), ("b/2", "granted", 1)]


def test_gateway_a_refused_login_says_why_and_is_not_then_reported_as_a_lost_feed(tmp_path):
    """Found against a real client: after a refused login paho calls on_disconnect too, which must not read as 'lost'."""
    gw, sub = mqtt_gateway(tmp_path), FakeSubscribe()
    gw.mqtt_on_connect(4, sub)
    assert gw.mqtt_connected is False and sub.calls == []
    gw.mqtt_on_disconnect(5)
    f = feed(gw)
    assert f["state"] == "REFUSED" and f["code"] == 4 and "user name or password" in f["why"] and "MQTT_PASSWORD" in f["hint"]
    assert f["drops5min"] == 0 and f["flapping"] is False


def test_gateway_a_lost_connection_is_reported_and_releases_held_usb_swipes(tmp_path):
    gw, sub = mqtt_gateway(tmp_path), FakeSubscribe()
    gw.mqtt_on_connect(0, sub)
    assert gw.accept_local("DrC0F8", "usb0", "38").state == "HELD"
    gw.mqtt_on_disconnect(7)
    f = feed(gw)
    assert f["state"] == "DISCONNECTED" and "lost" in f["why"] and f["code"] == 7
    assert gw.outbox.stats()["held"] == 0 and gw.outbox.stats()["pending"] == 1
    assert gw.accept_local("DrC0F8", "usb0", "35").state == "QUEUED"        # nothing to wait for while the feed is down


def test_gateway_qos_fallback_asks_again_for_that_topic_and_only_a_final_refusal_is_reported(tmp_path):
    gw, sub = mqtt_gateway(tmp_path), FakeSubscribe()
    gw.mqtt_on_connect(0, sub)                                   # requests 1 and 2: a/1 and b/2 at QoS 1
    gw.mqtt_on_subscribe(1, [128], sub)                          # a/1 refused at QoS 1: ask again at QoS 0, say nothing yet
    assert sub.calls[2:] == [("a/1", 0)]
    assert [t["state"] for t in feed(gw)["topics"]] == ["pending", "pending"]
    gw.mqtt_on_subscribe(3, [0], sub)
    assert feed(gw)["topics"][0] == {"topic": "a/1", "state": "granted", "qos": 0}
    gw.mqtt_on_subscribe(2, [128], sub)                          # b/2 refused at QoS 1 too
    gw.mqtt_on_subscribe(4, [128], sub)                          # and refused at QoS 0: final. It must not be asked for again.
    assert len(sub.calls) == 4
    assert feed(gw)["topics"][1] == {"topic": "b/2", "state": "refused", "qos": None}
    warning = ConsoleApi(gw).overview()["mqtt"]["warning"]
    assert "b/2" in warning and "refused" in warning


def test_gateway_counts_mqtt_messages_and_reader_events_apart(tmp_path):
    gw = mqtt_gateway(tmp_path)
    gw.handle_mqtt_message("a/1", json.dumps(card_event(1, int(time.time() * 1000))).encode())
    gw.handle_mqtt_message("token/x", b"expires in 3 days")
    f = feed(gw)
    assert (f["messages"], f["events"], f["lastMessageAgeS"]) == (2, 1, 0)
    assert gw.counters["ignored"] == 1 and gw.counters["received"] == 1


def test_overview_reader_feed_when_mqtt_is_not_used(gw):
    m = ConsoleApi(gw).overview()["mqtt"]
    assert (m["configured"], m["connected"], m["state"], m["url"], m["clientId"], m["topics"]) == (False, False, "NOT_CONFIGURED", None, None, [])
    assert m["holding"] is False and m["warning"] is None


def test_overview_reader_feed_connected_is_what_decides_whether_usb_swipes_wait(tmp_path):
    gw, sub = mqtt_gateway(tmp_path, mqtt_url="wss://u:p4ss@broker.test:443/mqtt", mqtt_client_id="Web_Edge_barn1"), FakeSubscribe()
    api = ConsoleApi(gw)
    m = api.overview()["mqtt"]
    assert m["state"] == "CONNECTING" and m["holding"] is False
    gw.mqtt_on_connect(0, sub)
    m = api.overview()["mqtt"]
    assert m["state"] == "CONNECTED" and m["connected"] is True and m["holding"] is True and m["holdMs"] == 6000
    assert m["url"] == "wss://broker.test:443/mqtt" and m["clientId"] == "Web_Edge_barn1" and m["broker"] == "broker.test"
    assert "p4ss" not in json.dumps(api.overview())
    gw.cfg.local_hold_ms = 0
    assert api.overview()["mqtt"]["holding"] is False             # LOCAL_HOLD_MS=0: never waits, even while connected


def test_overview_reader_feed_warns_when_nothing_is_subscribed(tmp_path):
    gw = mqtt_gateway(tmp_path, mqtt_topics="")
    gw.mqtt_on_connect(0, FakeSubscribe())
    m = ConsoleApi(gw).overview()["mqtt"]
    assert m["state"] == "CONNECTED" and m["topics"] == [] and "MQTT_TOPICS is empty" in m["warning"]


def test_overview_reader_feed_warns_about_a_client_id_that_keeps_being_taken_over(tmp_path):
    gw, sub = mqtt_gateway(tmp_path), FakeSubscribe()
    for _ in range(3):
        gw.mqtt_on_connect(0, sub)
        gw.mqtt_on_disconnect(7)
    gw.mqtt_on_connect(0, sub)
    m = ConsoleApi(gw).overview()["mqtt"]
    assert m["state"] == "CONNECTED" and "MQTT_CLIENT_ID" in m["warning"] and m["drops5min"] == 3


def test_overview_reader_feed_state_follows_the_gateways_own_flag(tmp_path):
    gw = mqtt_gateway(tmp_path)
    gw.mqtt_connected = True                                      # the flag is what the gateway acts on, so it is what the page shows
    assert ConsoleApi(gw).overview()["mqtt"]["state"] == "CONNECTED"
    gw.mqtt_connected = False
    gw.journal.mqtt_up()                                          # the journal says connected but the flag does not: not connected
    assert ConsoleApi(gw).overview()["mqtt"]["state"] == "DISCONNECTED"


def test_connection_reasons_read_in_words():
    assert "user name or password" in edge_console.connack_reason(4)[0] and "MQTT_PASSWORD" in edge_console.connack_reason(4)[1]
    assert "MQTT_CLIENT_ID" in edge_console.connack_reason(2)[1]
    assert edge_console.connack_reason(99)[0] == "The broker refused the connection (code 99)."
    assert "lost" in edge_console.disconnect_reason(7) and "closed" in edge_console.disconnect_reason(0)
    assert edge_console.clean_broker_url("wss://u:p@h.test:443/mqtt") == "wss://h.test:443/mqtt"
    assert edge_console.clean_broker_url("") is None


# ---- checking the path to the broker ------------------------------------------------------------------------
class FakeServer:
    """A throwaway TCP server. `answer` gets each connection; `seen` keeps what the first client sent."""

    def __init__(self, answer):
        self.seen = b""
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(4)
        self.port = self.sock.getsockname()[1]

        def serve():
            while True:
                try:
                    conn, _ = self.sock.accept()
                except OSError:
                    return
                try:
                    conn.settimeout(3)
                    data = b""
                    while b"\r\n\r\n" not in data:
                        chunk = conn.recv(1024)
                        if not chunk:
                            break
                        data += chunk
                    self.seen = self.seen or data
                    answer(conn)
                except OSError:
                    pass
                finally:
                    conn.close()

        threading.Thread(target=serve, daemon=True).start()

    def close(self):
        self.sock.close()


def test_broker_check_for_a_plain_mqtt_address_opens_the_connection_and_says_it_cannot_log_in():
    server = FakeServer(lambda c: None)
    try:
        r = edge_console.run_broker_probe(f"mqtt://u:secret@127.0.0.1:{server.port}")
    finally:
        server.close()
    assert [(s["name"], s["ok"]) for s in r["steps"]] == [("dns", True), ("tcp", True), ("tls", True), ("login", None)]
    assert r["steps"][2]["skipped"] is True and r["ok"] is True and "secret" not in json.dumps(r)
    assert r["url"] == f"mqtt://127.0.0.1:{server.port}" and "reachable" in r["hint"]


def test_broker_check_stops_at_a_closed_port_and_names_the_firewall():
    r = edge_console.run_broker_probe(f"mqtt://127.0.0.1:{free_port()}", timeout=2)
    assert r["ok"] is False and [(s["name"], s["ok"]) for s in r["steps"]][:2] == [("dns", True), ("tcp", False)]
    assert "firewall" in r["hint"]


def test_broker_check_for_a_websocket_address_asks_for_the_upgrade_on_the_given_path():
    server = FakeServer(lambda c: c.sendall(b"HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\n\r\n"))
    try:
        r = edge_console.run_broker_probe(f"ws://127.0.0.1:{server.port}/mqtt")
    finally:
        server.close()
    assert [s["name"] for s in r["steps"]] == ["dns", "tcp", "tls", "ws", "login"] and r["ok"] is True
    assert r["steps"][3]["detail"] == "HTTP 101 on /mqtt"
    assert server.seen.startswith(b"GET /mqtt HTTP/1.1") and b"Sec-WebSocket-Protocol: mqtt" in server.seen and b"Upgrade: websocket" in server.seen


def test_broker_check_names_the_path_when_the_server_does_not_upgrade():
    server = FakeServer(lambda c: c.sendall(b"HTTP/1.1 404 Not Found\r\n\r\n"))
    try:
        r = edge_console.run_broker_probe(f"ws://127.0.0.1:{server.port}/wrong")
    finally:
        server.close()
    ws = next(s for s in r["steps"] if s["name"] == "ws")
    assert r["ok"] is False and ws["ok"] is False and "404" in ws["error"] and "/mqtt" in r["hint"]


@pytest.mark.skipif(shutil.which("openssl") is None, reason="openssl is needed to make a throwaway certificate")
def test_broker_check_follows_mqtt_tls_insecure(tmp_path):
    key, crt = str(tmp_path / "k.pem"), str(tmp_path / "c.pem")
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", crt, "-subj", "/CN=localhost",
                    "-days", "1"], check=True, capture_output=True)
    server = Cloud(tls=(crt, key))
    try:
        lax = edge_console.run_broker_probe(f"mqtts://127.0.0.1:{server.port}", tls_insecure=True)
        strict = edge_console.run_broker_probe(f"mqtts://127.0.0.1:{server.port}", tls_insecure=False)
    finally:
        server.close()
    assert lax["ok"] is True and "not checked" in lax["steps"][2]["detail"]
    assert strict["ok"] is False and strict["steps"][2]["ok"] is False and "handshake" in strict["hint"]


def test_the_broker_check_goes_through_the_api_with_the_same_limits(tmp_path):
    server = FakeServer(lambda c: None)
    try:
        gw = make_gateway(tmp_path, mqtt_url=f"mqtt://127.0.0.1:{server.port}")
        clock = Clock(1000.0)
        api = ConsoleApi(gw, clock)
        status, body = api.handle("POST", "/api/probe?target=mqtt&url=mqtt://169.254.169.254", {})   # a caller cannot pick the target
        assert status == 200 and body["target"] == "mqtt" and body["ok"] is True and body["url"] == f"mqtt://127.0.0.1:{server.port}"
        assert api.handle("POST", "/api/probe", {})[0] == 429                                           # one limit for both checks
        clock.now += 3
        assert api.handle("POST", "/api/probe?target=mqtt", {})[0] == 200                             # after the pause it runs again
    finally:
        server.close()


def test_the_broker_check_refuses_an_unknown_target_and_a_gateway_without_mqtt(gw):
    api = ConsoleApi(gw)
    status, body = api.handle("POST", "/api/probe?target=anything", {})
    assert status == 400 and "cloud or mqtt" in body["error"]
    status, body = api.handle("POST", "/api/probe?target=mqtt", {})
    assert status == 400 and "MQTT_URL" in body["error"]


def test_the_page_has_one_card_for_the_cloud_and_one_for_the_reader_feed():
    html = (HERE / "console.html").read_text(encoding="utf-8")
    for needle in ('id="conn-cloud"', 'id="conn-mqtt"', 'id="cloud-pill"', 'id="mqtt-pill"', 'id="probe-btn"', 'id="mqtt-probe-btn"',
                   "/api/probe?target=", "renderConnections"):
        assert needle in html, needle
