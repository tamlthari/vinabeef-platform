"""Tests for USB swipes on the gateway: durable negative keys, hold-and-confirm, one-to-one matching with the reader's
own copy, restart survival, and the HTTP / MQTT paths. Run with:  python -m pytest -q edge_gateway"""
import json
import os
import sqlite3
import sys
import threading
import urllib.error
import urllib.request

import httpx
import pytest

sys.path.insert(0, os.path.dirname(__file__))
import barn_edge_gateway as edge  # noqa: E402
from local_swipes import KEY_SPACE, LocalSwipes  # noqa: E402

BASE = "http://backend.test/api/v1"
T = 1_795_000_000_000


def make(tmp_path, name="q.db", **overrides):
    settings = dict(db_path=str(tmp_path / name), cloud_base_url=BASE, local_readers="DrC0F8,DrC1C4",
                    local_hold_ms=6000, local_match_skew_ms=10_000)
    settings.update(overrides)
    return edge.Gateway(edge.Config(**settings))


@pytest.fixture
def gw(tmp_path):
    g = make(tmp_path)
    g.mqtt_connected = True            # the Safetrust broker is reachable: swipes are held for their twin
    return g


def reader_event(key, hw, credential=38, reader="DrC0F8", card="044E6602AD1490"):
    return {"EVENT_KEY": key, "TIMESTAMP": hw, "CARD_UUID": card, "CREDENTIAL_IDENTIFIER": credential,
            "READER_NAME": reader, "COMPANY_KEY": 475}


def feed(gw, event, arrived=None):
    """A Safetrust read arriving on the gateway at `arrived` (gateway clock)."""
    gw.local.clock = lambda: event["TIMESTAMP"] if arrived is None else arrived
    return gw.accept_event(event, json.dumps(event))


def states(gw):
    return [r[0] for r in gw.outbox.db.execute("SELECT state FROM swipe_log WHERE route='LOCAL_USB' ORDER BY id")]


def queue_rows(gw):
    return gw.outbox.db.execute("SELECT event_key, status, is_card, payload_json FROM edge_queue ORDER BY seq_id").fetchall()


def swipe(gw, text="38", reader="DrC0F8", t=T, device="kbd-event5"):
    return gw.accept_local(reader, device, text, t)


# ---- the event a USB swipe becomes -----------------------------------------------------------------------
def test_a_swipe_becomes_an_event_with_a_negative_key_the_gateway_clock_and_its_origin(tmp_path):
    g = make(tmp_path, local_company_key=475)
    result = swipe(g, " 0038 ", t=T)
    assert result.state == "QUEUED"
    key, status, is_card, payload = queue_rows(g)[0]
    event = json.loads(payload)
    assert key == result.event_key < 0 and status == "PENDING" and is_card == 1
    assert event["EVENT_KEY"] == key and event["TIMESTAMP"] == T
    assert event["READER_NAME"] == "DrC0F8" and event["CREDENTIAL_IDENTIFIER"] == "38" and event["COMPANY_KEY"] == 475
    assert (event["EDGE_SOURCE"], event["EDGE_DEVICE"], event["EDGE_TIME_SOURCE"]) == ("LOCAL_USB", "kbd-event5", "GATEWAY_CLOCK")
    assert json.loads(event["rawPayload"]) == {"input": " 0038 ", "device": "kbd-event5", "reader": "DrC0F8"}
    assert "CARD_UUID" not in event                       # the card is the cloud's to resolve, never guessed here


def test_a_credential_only_read_is_protected_from_queue_pruning_like_a_card_read(tmp_path):
    g = make(tmp_path, max_queue_rows=3)
    g.accept_event({"EVENT_KEY": 9_000, "TIMESTAMP": T, "CREDENTIAL_IDENTIFIER": 38, "READER_NAME": "DrC0F8"}, "{}")   # no CARD_UUID
    for i in range(500):                                  # telemetry without card or credential
        g.accept_event({"EVENT_KEY": 10_000 + i, "TIMESTAMP": T + i}, "{}")
    swipe(g, "38", t=T + 1)
    g.outbox._prune()
    keys = [r[0] for r in queue_rows(g)]
    assert len(keys) == 3 and 9_000 in keys and any(k < 0 for k in keys)    # the telemetry went first, both reads stayed


def test_keys_are_negative_unique_in_their_own_gateway_namespace_and_never_reused(tmp_path):
    g = make(tmp_path, gateway_no=7)
    keys = [swipe(g, "38", t=T).event_key for _ in range(5)]            # five swipes in the SAME millisecond
    assert len(set(keys)) == 5 and all(-(8 * KEY_SPACE) < k <= -(7 * KEY_SPACE) for k in keys)
    other = make(tmp_path, "other.db", gateway_no=8)
    assert -(9 * KEY_SPACE) < swipe(other, "38", t=T).event_key <= -(8 * KEY_SPACE)   # a second gateway cannot collide
    g.outbox.db.close()
    again = edge.Gateway(edge.Config(db_path=str(tmp_path / "q.db"), local_readers="DrC0F8", gateway_no=7))
    assert swipe(again, "38", t=T).event_key not in keys                           # restart continues the counter


def test_a_wiped_gateway_database_cannot_reuse_a_key_it_gave_out_before(tmp_path):
    first = make(tmp_path, "a.db")
    used = [swipe(first, "38", t=T + i).event_key for i in range(3)]
    fresh = make(tmp_path, "b.db")                                                  # disk replaced, counter gone
    assert swipe(fresh, "38", t=T + 60_000).event_key not in used                   # the clock moved on: new key


def test_a_gateway_number_outside_1_to_99_is_refused(tmp_path):
    for bad in (0, 100, -1):
        with pytest.raises(ValueError):
            make(tmp_path, f"x{bad}.db", gateway_no=bad)


# ---- what is refused -------------------------------------------------------------------------------------
@pytest.mark.parametrize("text", ["abc", "", "3 8", "12345678901234", "38?"])
def test_text_that_is_not_a_credential_number_is_refused_and_counted(gw, text):
    with pytest.raises(ValueError):
        swipe(gw, text)
    assert gw.counters["localRejected"] == 1 and queue_rows(gw) == []


def test_a_reader_this_gateway_does_not_serve_is_refused(gw):
    with pytest.raises(ValueError):
        swipe(gw, "38", reader="DrZZZZ")
    assert gw.counters["localRejected"] == 1 and queue_rows(gw) == []


# ---- hold only while MQTT is up ---------------------------------------------------------------------------
def test_with_mqtt_down_the_swipe_is_queued_at_once_because_no_copy_can_reach_this_gateway(tmp_path):
    g = make(tmp_path)
    assert g.mqtt_connected is False
    assert swipe(g).state == "QUEUED" and queue_rows(g)[0][1] == "PENDING" and g.counters["localQueued"] == 1


def test_a_hold_of_zero_never_waits(tmp_path):
    g = make(tmp_path)
    g.mqtt_connected = True
    g.cfg.local_hold_ms = 0
    assert swipe(g).state == "QUEUED"


def test_with_mqtt_up_the_swipe_is_held_and_not_offered_to_the_forwarder(gw):
    assert swipe(gw).state == "HELD"
    assert queue_rows(gw)[0][1] == "HELD" and gw.outbox.next_batch(10) == []
    assert gw.outbox.stats()["held"] == 1 and gw.outbox.stats()["pending"] == 0


def test_a_swipe_nobody_confirms_is_queued_when_the_hold_runs_out_and_not_before(gw):
    swipe(gw, t=T)
    assert gw.local.release_due(T + 5_999) == 0 and gw.outbox.next_batch(10) == []
    assert gw.local.release_due(T + 6_000) == 1
    assert [r[1] for r in queue_rows(gw)] == ["PENDING"] and len(gw.outbox.next_batch(10)) == 1
    assert states(gw) == ["QUEUED"] and gw.local.release_due(T + 60_000) == 0


def test_losing_mqtt_releases_every_held_swipe_at_once(gw):
    swipe(gw, "38", t=T); swipe(gw, "1005", t=T + 1)
    assert gw.local.release_all_held() == 2
    assert len(gw.outbox.next_batch(10)) == 2 and states(gw) == ["QUEUED", "QUEUED"]


# ---- the reader's own copy ---------------------------------------------------------------------------------
def test_the_readers_copy_arriving_during_the_hold_makes_the_swipe_a_shadow_that_is_never_forwarded(gw):
    held = swipe(gw, "38", t=T)
    feed(gw, reader_event(5001, T - 200), arrived=T + 800)            # reader stamped the tap 200 ms earlier
    assert states(gw) == ["SHADOW"]
    keys = [r[0] for r in queue_rows(gw)]
    assert keys == [5001] and held.event_key not in keys               # only the reader's own event is forwarded
    assert gw.local.release_due(T + 60_000) == 0


def test_the_readers_copy_arriving_before_the_swipe_makes_it_a_shadow_at_once(gw):
    feed(gw, reader_event(5001, T - 100), arrived=T + 300)
    result = swipe(gw, "38", t=T + 400)
    assert result.state == "SHADOW" and result.twin_key == 5001
    assert [r[0] for r in queue_rows(gw)] == [5001] and gw.counters["localShadow"] == 1


def test_a_swipe_and_a_reader_event_pair_one_to_one(gw):
    feed(gw, reader_event(5001, T), arrived=T)
    assert swipe(gw, "38", t=T + 10).state == "SHADOW"
    assert swipe(gw, "38", t=T + 20).state == "HELD"                   # the second swipe has no copy of its own
    feed(gw, reader_event(5002, T + 30), arrived=T + 30)
    assert states(gw) == ["SHADOW", "SHADOW"]
    feed(gw, reader_event(5003, T + 40), arrived=T + 40)               # a third read finds nothing waiting
    assert [r[0] for r in queue_rows(gw)] == [5001, 5002, 5003]


def test_one_reader_event_cannot_confirm_two_held_swipes(gw):
    swipe(gw, "38", t=T); swipe(gw, "38", t=T + 1000)
    feed(gw, reader_event(5001, T + 1000), arrived=T + 1100)
    assert sorted(states(gw)) == ["HELD", "SHADOW"]


def test_the_nearest_swipe_is_the_one_confirmed(gw):
    swipe(gw, "38", t=T + 1000); swipe(gw, "38", t=T + 3000)
    feed(gw, reader_event(5001, T + 2900), arrived=T + 3000)
    assert states(gw) == ["HELD", "SHADOW"]


@pytest.mark.parametrize("name,event", [
    ("another credential", reader_event(5001, T, credential=99)),
    ("another reader", reader_event(5001, T, reader="DrC1C4")),
    ("an event without a credential", {"EVENT_KEY": 5001, "TIMESTAMP": T, "CARD_UUID": "044E6602AD1490", "READER_NAME": "DrC0F8"}),
    ("an event without a reader", {"EVENT_KEY": 5001, "TIMESTAMP": T, "CREDENTIAL_IDENTIFIER": 38}),
])
def test_only_the_same_reader_and_the_same_credential_can_confirm_a_swipe(gw, name, event):
    swipe(gw, "38", t=T)
    feed(gw, event, arrived=T + 100)
    assert states(gw) == ["HELD"], name


def test_a_buffered_replay_of_an_old_tap_cannot_swallow_a_fresh_swipe(gw):
    """The reader was offline, buffered an old tap and delivers it now, just as someone swipes again: the old read must NOT
    confirm the new swipe, or a real tap would be lost. The reader's own timestamp gives it away."""
    swipe(gw, "38", t=T)
    feed(gw, reader_event(5001, T - 3_600_000), arrived=T + 500)       # tapped an hour ago, arrives now
    assert states(gw) == ["HELD"]
    assert gw.local.release_due(T + 6_000) == 1 and any(r[0] < 0 and r[1] == "PENDING" for r in queue_rows(gw))


def test_a_fresh_swipe_is_not_matched_to_a_read_that_is_only_old_on_the_gateway_clock(gw):
    feed(gw, reader_event(5001, T), arrived=T)
    assert swipe(gw, "38", t=T + 10_001).state == "HELD"               # one millisecond too far
    assert swipe(gw, "38", t=T + 10_000).state == "SHADOW"             # exactly at the limit still counts


def test_a_read_that_was_already_forwarded_cannot_confirm_a_swipe_that_was_already_released(gw):
    swipe(gw, "38", t=T)
    gw.local.release_due(T + 6_000)
    feed(gw, reader_event(5001, T), arrived=T + 7_000)                 # too late for the edge: the cloud matcher handles it
    assert states(gw) == ["QUEUED"]


def test_a_resent_reader_event_does_not_count_as_a_second_read(gw):
    feed(gw, reader_event(5001, T), arrived=T)
    feed(gw, reader_event(5001, T), arrived=T + 5)                     # same EVENT_KEY: duplicate
    assert swipe(gw, "38", t=T + 10).state == "SHADOW"
    assert swipe(gw, "38", t=T + 20).state == "HELD"                   # no phantom second read to confirm it


def test_the_credential_is_compared_the_way_the_reader_writes_it(gw):
    swipe(gw, "0038", t=T)
    feed(gw, reader_event(5001, T, credential="38"), arrived=T)
    assert states(gw) == ["SHADOW"]
    swipe(gw, "1005", t=T + 50_000)
    feed(gw, reader_event(5002, T + 50_000, credential=1005), arrived=T + 50_000)    # Safetrust sends a number
    assert states(gw)[-1] == "SHADOW"


# ---- restarts ----------------------------------------------------------------------------------------------
def test_a_held_swipe_survives_a_power_cut_and_is_queued_when_its_hold_has_run_out(tmp_path):
    g = make(tmp_path)
    g.mqtt_connected = True
    swipe(g, "38", t=T)
    g.outbox.db.close()                                                  # power cut: nothing but the file remains
    again = make(tmp_path)
    assert again.outbox.stats()["held"] == 1 and again.outbox.next_batch(10) == []
    assert again.local.release_due(T + 6_000) == 1 and len(again.outbox.next_batch(10)) == 1


def test_a_swipe_log_from_a_gateway_that_never_had_usb_readers_is_added_in_place(tmp_path):
    path = tmp_path / "old.db"
    db = sqlite3.connect(path)
    db.execute("CREATE TABLE edge_queue (seq_id INTEGER PRIMARY KEY AUTOINCREMENT, event_key INTEGER UNIQUE, hardware_ts INTEGER NOT NULL, "
               "payload_json TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING')")
    db.execute("INSERT INTO edge_queue (event_key, hardware_ts, payload_json) VALUES (1, 1000, '{\"EVENT_KEY\": 1}')")
    db.commit(); db.close()
    g = edge.Gateway(edge.Config(db_path=str(path), local_readers="DrC0F8"))
    assert g.outbox.stats()["pending"] == 1 and swipe(g).state == "QUEUED" and g.outbox.stats()["pending"] == 2


def test_the_swipe_log_is_trimmed_but_never_a_swipe_that_is_still_held(gw):
    swipe(gw, "38", t=T)
    feed(gw, reader_event(5001, T - 3_000_000_000), arrived=T - 3_000_000_000)    # an old read
    assert gw.local.prune(T + 200_000_000) == 1                                    # the old one goes; retention is a day
    assert states(gw) == ["HELD"]


# ---- EDGE_ fields belong to the gateway -------------------------------------------------------------------
def test_a_reader_event_or_lan_post_cannot_pose_as_a_usb_swipe(gw):
    spoof = reader_event(5001, T)
    spoof.update({"EDGE_SOURCE": "LOCAL_USB", "EDGE_DEVICE": "fake", "EDGE_TIME_SOURCE": "GATEWAY_CLOCK"})
    gw.handle_mqtt_message("Web/475/Monitoring/728589", json.dumps(spoof).encode())
    sent = json.loads(queue_rows(gw)[0][3])
    assert not [k for k in sent if k.startswith("EDGE_")] and sent["EVENT_KEY"] == 5001


# ---- forwarding ---------------------------------------------------------------------------------------------
def test_a_swipe_is_forwarded_with_its_origin_and_removed_only_after_the_backend_confirms(tmp_path):
    g = make(tmp_path)
    result = swipe(g, "38", t=T)
    seen = []

    def handler(request):
        if request.url.path.endswith("/ingestion/health"):
            return httpx.Response(200, json={"status": "UP"})
        seen.append(json.loads(request.content))
        return httpx.Response(207, json={"results": [{"eventKey": result.event_key, "status": "OK"}]})

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        assert g.forward_once(client) == "SENT"
    assert seen[0][0]["EVENT_KEY"] == result.event_key < 0 and seen[0][0]["EDGE_SOURCE"] == "LOCAL_USB"
    assert queue_rows(g) == [] and g.counters["forwarded"] == 1


def test_a_swipe_stays_queued_while_the_backend_is_unreachable(tmp_path):
    g = make(tmp_path)
    swipe(g, "38", t=T)

    def handler(request):
        raise httpx.ConnectError("no route")

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        assert g.forward_once(client) == "RETRY"
    assert g.outbox.stats()["pending"] == 1 and g.wan_state == "OFFLINE"


# ---- status and the clock readout ----------------------------------------------------------------------------
def test_status_reports_holds_shadows_and_what_is_needed_to_size_the_window(gw):
    swipe(gw, "38", t=T)
    feed(gw, reader_event(5001, T - 150), arrived=T + 500)
    feed(gw, reader_event(5002, T - 650, credential=7), arrived=T + 100)         # offsets: 650 ms and 750 ms
    status = gw.status()["local"]
    assert (status["held"], status["shadow"], status["queued"]) == (0, 1, 0)
    assert status["clockOffset"] == {"samples": 2, "medianMs": 700, "maxAbsMs": 750}
    assert status["holdMs"] == 6000 and status["matchSkewMs"] == 10_000 and gw.status()["queue"]["held"] == 0


def test_the_clock_readout_ignores_a_few_buffered_replays(gw):
    for i in range(9):
        feed(gw, reader_event(6000 + i, T + i * 1000, credential=500 + i), arrived=T + i * 1000 + 300)
    feed(gw, reader_event(6100, T - 7_200_000, credential=600), arrived=T)         # a two hour old replay
    offset = gw.status()["local"]["clockOffset"]
    assert offset["medianMs"] == 300 and offset["maxAbsMs"] == 7_200_000 and offset["samples"] == 10


# ---- MQTT and HTTP paths ---------------------------------------------------------------------------------------
def test_a_reader_event_over_mqtt_confirms_a_held_swipe(gw):
    now = int(__import__("time").time() * 1000)
    swipe(gw, "38", t=now)
    gw.handle_mqtt_message("Web/475/Monitoring/728589", json.dumps(reader_event(5001, now - 100)).encode())
    assert states(gw) == ["SHADOW"]


def serve(gw):
    server = gw.http_server()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, server.server_address[1]


def post(port, path, body, token=None):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=json.dumps(body).encode(), method="POST")
    if token:
        req.add_header("X-Edge-Token", token)
    return urllib.request.urlopen(req)


def test_the_http_swipe_endpoint_stamps_the_time_itself_and_answers_what_became_of_the_swipe(tmp_path):
    g = make(tmp_path, bind_host="127.0.0.1", bind_port=0, ingest_token="s3cret")
    g.mqtt_connected = True
    server, port = serve(g)
    try:
        with pytest.raises(urllib.error.HTTPError) as err:
            post(port, "/local-swipe", {"reader": "DrC0F8", "credential": "38"})
        assert err.value.code == 401
        reply = json.load(post(port, "/local-swipe", {"reader": "DrC0F8", "credential": "38", "device": "kiosk-1", "TIMESTAMP": 5}, "s3cret"))
        assert reply["state"] == "HELD" and reply["eventKey"] < 0
        event = json.loads(queue_rows(g)[0][3])
        assert event["EDGE_DEVICE"] == "kiosk-1" and event["TIMESTAMP"] > 1_700_000_000_000     # the caller cannot set the time
        for bad in ({"reader": "DrC0F8", "credential": "abc"}, {"reader": "Nope", "credential": "38"}, {"credential": "38"}):
            with pytest.raises(urllib.error.HTTPError) as err:
                post(port, "/local-swipe", bad, "s3cret")
            assert err.value.code == 400
        assert g.counters["localRejected"] == 2
        status = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/status"))
        assert status["local"]["held"] == 1 and status["counters"]["localHeld"] == 1
    finally:
        server.shutdown()


def test_readers_come_from_the_inputs_and_from_local_readers(tmp_path):
    cfg = edge.Config(db_path=str(tmp_path / "r.db"), local_inputs="kbd:/dev/input/event5=DrC0F8,stdin=DrC1C4", local_readers="DrExtra")
    assert edge.Gateway(cfg).local_readers == {"DrC0F8", "DrC1C4", "DrExtra"}


def test_the_hold_loop_releases_a_swipe_without_anyone_calling_it(tmp_path):
    g = make(tmp_path, local_hold_ms=100)
    g.mqtt_connected = True
    swipe(g, "38", t=int(__import__("time").time() * 1000))
    t = threading.Thread(target=g.local_loop, daemon=True)
    t.start()
    try:
        deadline = __import__("time").time() + 3
        while g.outbox.stats()["pending"] == 0 and __import__("time").time() < deadline:
            __import__("time").sleep(0.05)
        assert g.outbox.stats()["pending"] == 1 and g.outbox.stats()["held"] == 0
    finally:
        g.stop.set(); t.join(2)


# ---- LOCAL_CREDENTIAL_MAP: the card of a typed number ------------------------------------------------------
DEMO_MAP = "36=042FAC02AD1490,1005=04B86592C21290,35=04143802F32290,38=044E6602AD1490"


def test_credential_map_is_parsed_and_normalised():
    from usb_input import parse_credential_map
    assert parse_credential_map(DEMO_MAP) == {"36": "042FAC02AD1490", "1005": "04B86592C21290",
                                              "35": "04143802F32290", "38": "044E6602AD1490"}
    assert parse_credential_map(" 0038 = 044e6602ad1490 , ") == {"38": "044E6602AD1490"}      # zeros dropped, hex upper-cased
    assert parse_credential_map("") == {} and parse_credential_map(None) == {}


@pytest.mark.parametrize("bad", ["38", "38=", "=044E6602AD1490", "abc=044E6602AD1490", "38=XYZ", "38=0044",
                                 "38=044E6602AD1490,38=042FAC02AD1490", "38=044E6602AD1490,36=044E6602AD1490"])
def test_credential_map_refuses_what_could_put_the_wrong_card_on_a_movement(bad):
    from usb_input import parse_credential_map
    with pytest.raises(ValueError):
        parse_credential_map(bad)


def test_a_mapped_swipe_carries_its_card_and_an_unmapped_one_does_not(tmp_path):
    g = make(tmp_path, local_credential_map=DEMO_MAP)          # MQTT is down: swipes are queued at once
    for number in ("36", "1005", "35", "38", "99"):
        g.accept_local("DrC0F8", "stdin", number, T)
    rows = {json.loads(r[0])["CREDENTIAL_IDENTIFIER"]: json.loads(r[0])
            for r in g.outbox.db.execute("SELECT payload_json FROM edge_queue")}
    assert rows["36"]["CARD_UUID"] == "042FAC02AD1490" and rows["1005"]["CARD_UUID"] == "04B86592C21290"
    assert rows["35"]["CARD_UUID"] == "04143802F32290" and rows["38"]["CARD_UUID"] == "044E6602AD1490"
    assert "CARD_UUID" not in rows["99"]                       # not guessed: the cloud may still resolve it, or keep it unmapped
    assert rows["38"]["EDGE_SOURCE"] == "LOCAL_USB" and rows["38"]["READER_NAME"] == "DrC0F8"   # still a USB swipe at that reader
    assert g.credential_cards["1005"] == "04B86592C21290"


def test_the_gateway_refuses_to_start_with_a_bad_map(tmp_path):
    with pytest.raises(ValueError, match="LOCAL_CREDENTIAL_MAP"):
        make(tmp_path, local_credential_map="38=nonsense")


def test_the_map_reads_its_environment_variable(monkeypatch):
    monkeypatch.setenv("LOCAL_CREDENTIAL_MAP", DEMO_MAP)
    assert edge.Config.from_env().local_credential_map == DEMO_MAP
