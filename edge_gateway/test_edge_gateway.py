"""Unit tests for the barn edge gateway: decoding, the durable queue and the forwarding rules.
Run with:  python -m pytest -q edge_gateway"""
import base64
import json
import os
import sqlite3
import sys
import threading
import urllib.request

import httpx
import pytest

sys.path.insert(0, os.path.dirname(__file__))
import barn_edge_gateway as edge  # noqa: E402

BASE = "http://backend.test/api/v1"


def card_event(key, ts, card="044E6602AD1490", reader="DrC0F8"):
    return {"EVENT_KEY": key, "TIMESTAMP": ts, "CARD_UUID": card, "READER_NAME": reader, "COMPANY_KEY": 475,
            "NOTES": {"ACTION": "OPEN_DOOR"}}


@pytest.fixture
def gw(tmp_path):
    cfg = edge.Config(db_path=str(tmp_path / "q.db"), cloud_base_url=BASE, max_attempts=3, batch_size=50)
    return edge.Gateway(cfg)


def queue(gw, *events):
    for e in events:
        gw.accept_event(e, json.dumps(e))


def client_for(handler):
    return httpx.Client(transport=httpx.MockTransport(handler))


# ---- decoding ---------------------------------------------------------------------------------------
def test_decode_plain_json():
    ev, raw = edge.decode_payload(b'{"EVENT_KEY": 1}')
    assert ev == {"EVENT_KEY": 1} and raw == '{"EVENT_KEY": 1}'


def test_decode_base64_and_urlsafe_without_padding():
    body = json.dumps({"EVENT_KEY": 580307087, "NOTES": {"A": "??>>"}}).encode()
    std = base64.b64encode(body)
    url = base64.urlsafe_b64encode(body).rstrip(b"=")
    assert edge.decode_payload(std)[0]["EVENT_KEY"] == 580307087
    assert edge.decode_payload(url)[0]["EVENT_KEY"] == 580307087
    assert edge.decode_payload(b'"' + std + b'"')[0]["EVENT_KEY"] == 580307087


@pytest.mark.parametrize("junk", [b"", b"not json at all!", b"[1,2]", b"eyJ4"])
def test_decode_rejects_what_is_not_an_event_object(junk):
    assert edge.decode_payload(junk) is None


def test_normalise_keeps_the_raw_payload_and_fixes_names():
    raw = '{"eventKey": 7, "cardUuid": " 044e66 ", "readerName": "DrC1C4", "timestamp": 1700000000000}'
    ev = edge.normalise(json.loads(raw), raw)
    assert ev["EVENT_KEY"] == 7 and ev["CARD_UUID"] == "044E66" and ev["READER_NAME"] == "DrC1C4"
    assert ev["TIMESTAMP"] == 1700000000000 and ev["rawPayload"] == raw


def test_a_missing_event_key_is_derived_from_the_content_so_a_resend_is_a_duplicate(gw):
    e = {"CARD_UUID": "AA", "READER_NAME": "DrC0F8", "TIMESTAMP": 5}
    assert gw.accept_event(e, json.dumps(e)) is True
    assert gw.accept_event(dict(e), json.dumps(e)) is False
    assert gw.outbox.stats()["pending"] == 1


# ---- queue ------------------------------------------------------------------------------------------
def test_duplicates_are_ignored_and_the_batch_is_in_hardware_time_order(gw):
    queue(gw, card_event(3, 3000), card_event(1, 1000), card_event(2, 2000), card_event(1, 1000))
    assert gw.counters["duplicates"] == 1
    keys = [r[1] for r in gw.outbox.next_batch(10)]
    assert keys == [1, 2, 3]


def test_events_survive_a_restart(tmp_path):
    cfg = edge.Config(db_path=str(tmp_path / "q.db"))
    first = edge.Gateway(cfg)
    queue(first, card_event(1, 1000), card_event(2, 2000))
    first.outbox.db.close()
    second = edge.Gateway(cfg)
    assert second.outbox.stats()["pending"] == 2


def test_an_outbox_from_the_first_version_is_upgraded_in_place(tmp_path):
    path = str(tmp_path / "old.db")
    db = sqlite3.connect(path)
    db.execute("CREATE TABLE edge_queue (seq_id INTEGER PRIMARY KEY AUTOINCREMENT, event_key INTEGER UNIQUE, "
               "hardware_ts INTEGER NOT NULL, payload_json TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING')")
    db.execute("INSERT INTO edge_queue (event_key, hardware_ts, payload_json) VALUES (9, 9000, '{\"EVENT_KEY\": 9}')")
    db.commit()
    db.close()
    gw = edge.Gateway(edge.Config(db_path=path))
    assert gw.outbox.stats()["pending"] == 1
    queue(gw, card_event(10, 10000))
    assert gw.outbox.stats()["pending"] == 2


def test_prune_drops_telemetry_before_card_reads(tmp_path):
    ob = edge.Outbox(str(tmp_path / "p.db"), max_rows=3)
    for i in range(3):
        ob.add({"EVENT_KEY": 100 + i, "TIMESTAMP": i, "CARD_UUID": "AA"})
    for i in range(3):
        ob.add({"EVENT_KEY": 200 + i, "TIMESTAMP": 10 + i})
    ob._prune()
    keys = sorted(r[1] for r in ob.next_batch(10))
    assert keys == [100, 101, 102]


# ---- forwarding -------------------------------------------------------------------------------------
def test_nothing_is_deleted_while_the_backend_is_unreachable(gw):
    queue(gw, card_event(1, 1000))

    def handler(request):
        raise httpx.ConnectError("no route")

    assert gw.forward_once(client_for(handler)) == "RETRY"
    assert gw.wan_state == "OFFLINE" and gw.outbox.stats()["pending"] == 1


def test_a_backend_that_cannot_reach_its_database_is_not_sent_events(gw):
    queue(gw, card_event(1, 1000))
    posted = []

    def handler(request):
        if request.url.path.endswith("/health"):
            return httpx.Response(503, json={"status": "DEGRADED"})
        posted.append(request)
        return httpx.Response(200, json={})

    assert gw.forward_once(client_for(handler)) == "RETRY"
    assert gw.wan_state == "DEGRADED" and not posted and gw.outbox.stats()["pending"] == 1


def test_events_are_deleted_only_after_the_backend_confirms_them(gw):
    queue(gw, card_event(2, 2000), card_event(1, 1000))
    seen = []

    def handler(request):
        if request.url.path.endswith("/health"):
            return httpx.Response(200)
        seen.append([e["EVENT_KEY"] for e in json.loads(request.content)])
        return httpx.Response(200, json={"status": "BATCH_COMPLETED"})

    assert gw.forward_once(client_for(handler)) == "SENT"
    assert seen == [[1, 2]] and gw.outbox.stats()["pending"] == 0
    assert gw.forward_once(client_for(handler)) == "IDLE"


def test_the_raw_payload_travels_with_the_event(gw):
    raw = '{"EVENT_KEY": 5, "TIMESTAMP": 5, "CARD_UUID": "AA", "EXTRA": 1}'
    gw.accept_event(json.loads(raw), raw)
    sent = []

    def handler(request):
        if not request.url.path.endswith("/health"):
            sent.extend(json.loads(request.content))
        return httpx.Response(200)

    gw.forward_once(client_for(handler))
    assert sent[0]["rawPayload"] == raw


def test_a_partial_result_deletes_only_the_confirmed_events_and_counts_the_failure(gw):
    queue(gw, card_event(1, 1000), card_event(2, 2000), card_event(3, 3000))

    def handler(request):
        if request.url.path.endswith("/health"):
            return httpx.Response(200)
        results = [{"eventKey": 1, "status": "OK"}, {"eventKey": 2, "status": "FAILED", "error": "db"},
                   {"eventKey": 3, "status": "OK"}]
        return httpx.Response(207, json={"status": "BATCH_PARTIAL", "results": results})

    assert gw.forward_once(client_for(handler)) == "RETRY"
    assert [r[1] for r in gw.outbox.next_batch(10)] == [2]
    attempts = gw.outbox.db.execute("SELECT attempts, last_error FROM edge_queue").fetchone()
    assert attempts == (1, "db")


def test_an_event_the_backend_keeps_rejecting_becomes_dead_and_stops_blocking(gw):
    queue(gw, card_event(1, 1000), card_event(2, 2000))

    def handler(request):
        if request.url.path.endswith("/health"):
            return httpx.Response(200)
        results = [{"eventKey": 1, "status": "FAILED", "error": "bad"}, {"eventKey": 2, "status": "OK"}]
        sent = [e["EVENT_KEY"] for e in json.loads(request.content)]
        return httpx.Response(207, json={"results": [r for r in results if r["eventKey"] in sent]})

    client = client_for(handler)
    for _ in range(3):
        gw.forward_once(client)
    stats = gw.outbox.stats()
    assert stats["pending"] == 0 and stats["dead"] == 1 and gw.counters["dead"] == 1


def test_one_unreadable_event_does_not_block_the_rest_of_the_batch(gw):
    queue(gw, card_event(1, 1000), card_event(2, 2000), card_event(3, 3000))

    def handler(request):
        if request.url.path.endswith("/health"):
            return httpx.Response(200)
        keys = [e["EVENT_KEY"] for e in json.loads(request.content)]
        return httpx.Response(400 if 2 in keys else 200, json={})

    assert gw.forward_once(client_for(handler)) == "RETRY"
    assert [r[1] for r in gw.outbox.next_batch(10)] == [2]


def test_a_server_error_keeps_everything(gw):
    queue(gw, card_event(1, 1000))

    def handler(request):
        return httpx.Response(200) if request.url.path.endswith("/health") else httpx.Response(500)

    assert gw.forward_once(client_for(handler)) == "RETRY"
    assert gw.outbox.stats()["pending"] == 1


def test_an_older_backend_that_answers_200_without_a_result_list_still_works(gw):
    queue(gw, card_event(1, 1000))
    assert gw.forward_once(client_for(lambda r: httpx.Response(200, json={"processed": 1}))) == "SENT"
    assert gw.outbox.stats()["pending"] == 0


# ---- MQTT intake and HTTP intake --------------------------------------------------------------------
def test_mqtt_messages_are_queued_and_other_topics_are_ignored(gw):
    body = base64.b64encode(json.dumps(card_event(11, 5000)).encode())
    gw.handle_mqtt_message("Web/475/Monitoring/728589", body)
    gw.handle_mqtt_message("token/abc", b"expires in 3 days")
    assert gw.outbox.stats()["pending"] == 1 and gw.counters["ignored"] == 1


def test_http_intake_requires_the_token_and_reports_status(tmp_path):
    cfg = edge.Config(db_path=str(tmp_path / "h.db"), bind_host="127.0.0.1", bind_port=0, ingest_token="s3cret")
    gw = edge.Gateway(cfg)
    server = gw.http_server()
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        body = json.dumps([card_event(1, 1000), card_event(2, 2000), card_event(1, 1000)]).encode()
        req = urllib.request.Request(f"http://127.0.0.1:{port}/", data=body, method="POST")
        with pytest.raises(urllib.error.HTTPError) as err:
            urllib.request.urlopen(req)
        assert err.value.code == 401
        req.add_header("X-Edge-Token", "s3cret")
        reply = json.load(urllib.request.urlopen(req))
        assert reply["queued"] == 2 and reply["duplicates"] == 1
        status = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/status"))
        assert status["queue"]["pending"] == 2
    finally:
        server.shutdown()
