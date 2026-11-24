"""Tests for the portal's pages. Run from this folder:  python -m pytest -q test_frontend.py
They need no backend: a small stand-in answers where a page asks the backend something."""
import json
import os
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from jinja2 import Environment, FileSystemLoader

HERE = Path(__file__).parent
os.chdir(HERE)                      # main.py reads templates/ and static/ relative to the working directory
import main  # noqa: E402

TX = {
    "transmissionId": "TX-1001", "targetSystem": "NADCS", "endpointUrl": "https://nadcs.example/api/receive",
    "reportType": "HERD_SUMMARY", "farmScope": "ALL", "recordCount": 3, "payloadFormat": "JSON",
    "contentType": "application/json", "httpStatus": 200, "deliveryStatus": "DELIVERED",
    "responseSummary": "Accepted via test server mock.example: receipt R-77", "transmittedBy": "admin",
    "transmittedAt": "2026-11-24T06:00:00Z", "bodyVisible": True, "legacy": False, "payloadBytes": 52,
    "payloadSha256": "ab" * 32, "storedTruncated": False, "storedChars": 25, "bodyExcerpt": '{"animals":[1,2,3]}',
    "excerptTruncated": False, "testServerUi": "https://mock.example/",
}


def render(**kw):
    env = main.templates.env
    d, error = kw.get("d"), kw.get("error", "")
    return env.get_template("partials/transmission_detail.html").render(
        d=d, view=main.build_transmission_view(d) if d else None, tx_id="TX-1001", error=error)


def test_every_template_compiles():
    """A template that does not compile is a 500 on every page that renders it: transmission_detail.html was committed
    cut off in the middle of a comment and every click on a transmission row failed with HTTP 500."""
    env = Environment(loader=FileSystemLoader(str(HERE / "templates")))
    env.filters["localtime"] = main.localtime
    names = sorted(str(p.relative_to(HERE / "templates")).replace(os.sep, "/") for p in (HERE / "templates").rglob("*.html"))
    assert len(names) > 20
    for name in names:
        env.get_template(name)                      # raises TemplateSyntaxError when one is broken


def test_transmission_window_shows_what_was_sent_and_what_came_back():
    html = render(d=TX)
    assert "TX-1001" in html and "NADCS" in html and "https://nadcs.example/api/receive" in html
    assert "DELIVERED" in html and "HTTP 200" in html and "test server" in html
    assert "receipt R-77" in html
    assert 'id="tx-pre-pretty"' in html and 'id="tx-pre-raw"' in html and "&#34;animals&#34;" in html
    assert 'data-tx-close' in html and 'data-tx-view="pretty"' in html and 'data-tx-copy' in html
    assert 'data-tx-copy-text="' + "ab" * 32 + '"' in html
    assert "/surveillance/transmissions/TX-1001/body" in html
    assert "Open its receipt history" in html and 'href="https://mock.example/#TX-1001"' in html     # opens that transmission there


def test_transmission_window_never_runs_text_from_the_body_or_the_answer():
    nasty = dict(TX, bodyExcerpt="<script>alert(1)</script>", payloadFormat="XML", responseSummary="<img src=x onerror=alert(2)>",
                 targetSystem="<b>x</b>")
    html = render(d=nasty)
    assert "<script>alert(1)" not in html and "<img src=x" not in html and "<b>x</b>" not in html
    assert "&lt;script&gt;alert(1)&lt;/script&gt;" in html


def test_transmission_window_says_why_there_is_no_body():
    assert "limited to one farm" in render(d=dict(TX, bodyVisible=False, bodyExcerpt=""))
    assert "before the full body was kept" in render(d=dict(TX, legacy=True, payloadBytes=None, storedChars=0, bodyExcerpt=""))
    assert "SIMULATED" in render(d=dict(TX, deliveryStatus="SIMULATED", httpStatus=None)) and "Nothing was sent" in render(d=dict(TX, deliveryStatus="SIMULATED"))


def test_transmission_window_says_when_the_body_shown_is_cut_off():
    html = render(d=dict(TX, excerptTruncated=True, storedChars=900000, bodyExcerpt="x" * 10))
    assert "Download the whole body" in html and "Showing the first 10 of 900000 characters" in html
    html = render(d=dict(TX, storedTruncated=True, storedChars=500000))
    assert "Only the first 500000 characters" in html


def test_transmission_window_shows_an_error_with_a_way_to_close():
    html = render(d=None, error="No transmission 'TX-9' is visible to this account.")
    assert "No transmission &#39;TX-9&#39; is visible" in html and "data-tx-close" in html and 'role="alert"' in html


# ---- the route, against a stand-in backend ---------------------------------------------------------------------------
class Backend(BaseHTTPRequestHandler):
    mode = "ok"

    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.startswith("/api/v1/surveillance/transmissions/TX-1001"):
            code, body = {"ok": (200, TX), "missing": (404, {"error": "No transmission 'TX-1001' is visible to this account."}),
                          "boom": (500, {"error": "database is down"}), "junk": (200, None)}[Backend.mode]
            raw = b"<html>not json" if body is None else json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        else:
            self.send_response(404)
            self.end_headers()


@pytest.fixture
def portal(monkeypatch):
    server = ThreadingHTTPServer(("127.0.0.1", 0), Backend)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    monkeypatch.setattr(main, "BACKEND_BASE_URL", f"http://127.0.0.1:{server.server_port}/api/v1")
    main.app.dependency_overrides[main.get_current_user] = lambda: {"username": "admin", "role": "SYSTEM_ADMIN", "token": "t"}
    Backend.mode = "ok"
    yield TestClient(main.app)
    main.app.dependency_overrides.clear()
    server.shutdown()


def test_route_opens_a_transmission(portal):
    r = portal.get("/htmx/surveillance/transmissions/TX-1001")
    assert r.status_code == 200 and "TX-1001" in r.text and "DELIVERED" in r.text and "tx-pre-pretty" in r.text


@pytest.mark.parametrize("mode,text", [("missing", "is visible to this account"), ("boom", "database is down"),
                                       ("junk", "not a transmission")])
def test_route_shows_the_reason_in_the_window_and_never_answers_500(portal, mode, text):
    Backend.mode = mode
    r = portal.get("/htmx/surveillance/transmissions/TX-1001")
    assert r.status_code == 200 and text in r.text and "data-tx-close" in r.text      # 200: HTMX swaps only successful answers


def test_route_says_so_when_the_backend_cannot_be_reached(portal, monkeypatch):
    monkeypatch.setattr(main, "BACKEND_BASE_URL", "http://127.0.0.1:9/api/v1")
    r = portal.get("/htmx/surveillance/transmissions/TX-1001")
    assert r.status_code == 200 and "could not be reached" in r.text


def test_route_refuses_roles_that_may_not_see_transmissions_and_bad_ids(portal):
    main.app.dependency_overrides[main.get_current_user] = lambda: {"username": "v", "role": "VETERINARIAN", "token": "t"}
    assert portal.get("/htmx/surveillance/transmissions/TX-1001").status_code == 403
    main.app.dependency_overrides[main.get_current_user] = lambda: {"username": "admin", "role": "SYSTEM_ADMIN", "token": "t"}
    assert "not a transmission id" in portal.get("/htmx/surveillance/transmissions/x$y").text


# ---- the sidebar ---------------------------------------------------------------------------------------------------
class _Req:
    """Just enough of a request for base.html: the path (for the highlighted entry) and the query string."""
    def __init__(self, path):
        self.url = type("U", (), {"path": path})()
        self.query_params = {}
        self.session = {}


def sidebar(role, path="/"):
    html = main.templates.env.get_template("base.html").render(request=_Req(path), user={"username": "u", "role": role, "token": "t"}, error="")
    nav = html[html.index('<nav class="px-3'):html.index("</nav>")]
    sections, current = {}, "(top)"
    for m in re.finditer(r'uppercase tracking-wider">([^<]+)</div>|<a href="(/[a-z]*)"', nav):
        if m.group(1):
            current = m.group(1).replace("&amp;", "&")
        else:
            sections.setdefault(current, []).append(m.group(2))
    return sections, nav


ALL_ROLES = ["SYSTEM_ADMIN", "FARM_MANAGER", "EXECUTIVE", "BARN_SUPERVISOR", "VETERINARIAN"]


def test_the_sidebar_groups_pages_into_fleet_reporting_and_system_for_an_administrator():
    sections, _ = sidebar("SYSTEM_ADMIN")
    assert list(sections) == ["(top)", "Fleet & Assets", "Reporting", "System"]
    assert sections["(top)"] == ["/"]
    assert sections["Fleet & Assets"] == ["/herd", "/readers", "/transfers", "/alerts"]
    assert sections["Reporting"] == ["/surveillance", "/reports", "/audit"]
    assert sections["System"] == ["/users"]


@pytest.mark.parametrize("role,fleet,reporting,system", [
    ("SYSTEM_ADMIN", ["/herd", "/readers", "/transfers", "/alerts"], ["/surveillance", "/reports", "/audit"], ["/users"]),
    ("FARM_MANAGER", ["/herd", "/readers", "/transfers", "/alerts"], ["/surveillance", "/reports"], ["/users"]),
    ("EXECUTIVE", ["/herd", "/readers", "/alerts"], ["/surveillance", "/reports"], None),
    ("BARN_SUPERVISOR", ["/herd", "/readers", "/transfers", "/alerts"], ["/reports"], None),
    ("VETERINARIAN", ["/herd", "/readers", "/alerts"], ["/reports"], None),
])
def test_each_role_sees_the_same_pages_as_before_in_their_new_sections(role, fleet, reporting, system):
    """Only the grouping changed: no role gained or lost a page, and a section with nothing in it for a role has no heading."""
    sections, _ = sidebar(role)
    assert sections["Fleet & Assets"] == fleet and sections["Reporting"] == reporting
    assert sections.get("System") == system


@pytest.mark.parametrize("path,entry", [("/", "/"), ("/herd", "/herd"), ("/transfers", "/transfers"), ("/alerts", "/alerts"),
                                        ("/surveillance", "/surveillance"), ("/reports", "/reports"), ("/audit", "/audit"), ("/users", "/users")])
def test_the_page_you_are_on_is_the_one_highlighted(path, entry):
    _, nav = sidebar("SYSTEM_ADMIN", path)
    lit = re.findall(r'<a href="(/[a-z]*)" class="[^"]*bg-\[#2f80ed\]', nav)
    assert lit == [entry]


def test_the_alerts_badge_is_still_polled_from_the_menu():
    _, nav = sidebar("BARN_SUPERVISOR", "/herd")
    assert 'hx-get="/htmx/alerts/badge"' in nav


# ---- the reader page: what became of each read ---------------------------------------------------------------------
# A cow was bound to tag 042FAC02AD1490 and walked through a gate with an inner reader (0ED4) and an outer reader
# (C1C4). The two reads were 31.7 s apart, the gate pairs reads within 15 s, and both rows said "Unregistered Tag Read".
READER = {"gate_id": "GATE-T-1", "barn_id": "BARN-DL-01", "farm_id": "FARM-1", "label": "Test pair", "reader_name": "0ED4",
          "outer_reader_name": "C1C4", "direction_mode": "BIDIRECTIONAL", "status": "ONLINE", "firmware_version": "1.0",
          "barn_code": "DL-01", "farm_name": "Dak Lak", "pair_window_ms": 15000, "repeat_debounce_ms": 3000,
          "in_count": 0, "out_count": 1, "today_in": 0, "today_out": 1, "passage_count": 1, "last_seen": "2026-11-24T11:50:41Z"}


def read(event_id, outcome, **kw):
    row = {"event_id": event_id, "event_key": None, "reader_name": "0ED4", "card_uuid": "042FAC02AD1490", "user_name": None,
           "gate_action": "ACCESS_GRANTED", "observed_at": "2026-11-24T11:50:10.130Z", "event_class": "CARD_READ",
           "read_source": "SAFETRUST", "source_device": None, "credential_identifier": None, "duplicate_of": None,
           "movement_id": None, "animal_id": "COW-T-1", "direction": None, "outcome": outcome, "half_side": None,
           "other_delta_ms": None}
    row.update(kw)
    return row


def reader_page(transactions, role="SYSTEM_ADMIN", **reader):
    return main.templates.env.get_template("reader_detail.html").render(
        request=_Req("/readers/GATE-T-1"), user={"username": "u", "role": role, "token": "t"}, error="",
        reader=dict(READER, **reader), transactions=transactions, barns=[{"barnId": "BARN-DL-01", "code": "DL-01", "farmId": "FARM-1"}])


def rows(html):
    """The transaction rows of the page as {event data-outcome: row text}, in page order."""
    body = html[html.index('id="tx-tbody"'):html.index("</tbody>")]
    return [(m.group(1), re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", m.group(2))).strip())
            for m in re.finditer(r'<tr class="tx-row[^"]*" data-outcome="([A-Z_]+)".*?>(.*?)</tr>', body, re.S)]


def test_a_bound_tag_whose_two_reads_did_not_pair_is_a_half_crossing_not_an_unregistered_tag():
    html = reader_page([
        read("A-out", "HALF_CROSSING", reader_name="C1C4", observed_at="2026-11-24T11:50:41.823Z", half_side="OUTER", other_delta_ms=-31693),
        read("A-in", "HALF_CROSSING", half_side="INNER", other_delta_ms=31693)])
    found = rows(html)
    assert [o for o, _ in found] == ["HALF_CROSSING", "HALF_CROSSING"]
    assert "Unregistered Tag Read" not in html and "Alert Raised" not in html
    inner_text, outer_text = found[1][1], found[0][1]
    assert "Half crossing, waiting for the outer reader" in inner_text and "The outer reader saw this tag 31.7 s later" in inner_text
    assert "Half crossing, waiting for the inner reader" in outer_text and "The inner reader saw this tag 31.7 s earlier" in outer_text
    assert "longer than the 15 s pairing window" in inner_text and "no movement was recorded" in inner_text
    assert "COW-T-1" in inner_text and "Tag: 042FAC02AD1490" in inner_text          # the cow is named, so the binding is visible
    assert "Waiting for the other reader" in inner_text


def test_the_hint_to_raise_the_window_is_only_for_people_who_may_change_it():
    half = [read("A-in", "HALF_CROSSING", half_side="INNER", other_delta_ms=31693)]
    assert "raise the window under" in reader_page(half, role="FARM_MANAGER")
    assert "raise the window under" in reader_page(half, role="SYSTEM_ADMIN")
    assert "raise the window under" not in reader_page(half, role="VETERINARIAN")


def test_a_gap_inside_the_window_is_not_blamed_on_the_window():
    # window widened after the reads: they are still unpaired, but "longer than the window" would be wrong now
    html = reader_page([read("A-in", "HALF_CROSSING", half_side="INNER", other_delta_ms=31693)], pair_window_ms=60000)
    assert "The outer reader saw this tag 31.7 s later" in html and "longer than" not in html


def test_a_read_that_is_still_waiting_says_which_reader_and_for_how_long():
    html = reader_page([read("A-in", "HALF_CROSSING", half_side="OUTER", other_delta_ms=None)], pair_window_ms=45000)
    assert "waiting for the inner reader" in html
    assert "has not seen this tag within the 45 s pairing window" in html


def test_both_reads_of_a_completed_crossing_are_counted_and_neither_is_flagged():
    # the earlier read is movements.first_event_id, the later one movements.source_event_id
    html = reader_page([
        read("B-out", "PASSAGE", reader_name="C1C4", movement_id="M-1", direction="OUT"),
        read("B-in", "CROSSING_FIRST", direction="OUT")])
    found = dict(rows(html))
    assert "Passage OUT" in found["PASSAGE"] and "Success" in found["PASSAGE"]
    assert "First read of a crossing (OUT)" in found["CROSSING_FIRST"] and "Counted with the passage" in found["CROSSING_FIRST"]
    assert "Unregistered" not in html and "Alert Raised" not in html


def test_a_tag_that_is_bound_to_no_animal_is_still_reported_as_unregistered():
    html = reader_page([read("D", "UNREGISTERED_TAG", animal_id=None, card_uuid="04FFFFFFFFFFFF")])
    text = dict(rows(html))["UNREGISTERED_TAG"]
    assert "Unregistered Tag Read" in text and "Tag: 04FFFFFFFFFFFF" in text and "Alert Raised" in text


def test_repeat_reads_copies_usb_reads_and_heartbeats_each_get_their_own_label():
    html = reader_page([
        read("C", "NO_MOVEMENT"),
        read("E", "COPY", duplicate_of="B-in", read_source="LOCAL_USB", source_device="usb-1"),
        read("F", "USB_UNMAPPED", card_uuid=None, animal_id=None, event_class="LOCAL_UNMAPPED", credential_identifier="318567",
             read_source="LOCAL_USB", source_device="usb-1"),
        read("G", "SYSTEM", card_uuid=None, animal_id=None, gate_action="READER_HEARTBEAT", event_class="READER_STATUS")])
    found = dict(rows(html))
    assert "Repeat read, no new movement" in found["NO_MOVEMENT"] and "Not counted" in found["NO_MOVEMENT"]
    assert "Second copy of a tap already counted" in found["COPY"] and "Copy, not counted" in found["COPY"] and "USB" in found["COPY"]
    assert "credential 318567 matches no card" in found["USB_UNMAPPED"] and "Alert Raised" in found["USB_UNMAPPED"]
    assert "READER_HEARTBEAT" in found["SYSTEM"] and "Success" in found["SYSTEM"]


def test_the_filter_still_sorts_rows_into_gate_events_and_system_events():
    html = reader_page([read("A", "HALF_CROSSING", half_side="INNER"), read("B", "PASSAGE", movement_id="M"), read("C", "COPY"),
                        read("D", "UNREGISTERED_TAG", animal_id=None), read("G", "SYSTEM", card_uuid=None, animal_id=None)])
    types = re.findall(r'data-outcome="([A-Z_]+)"\s+data-type="([A-Z]+)"', html)
    assert dict(types) == {"HALF_CROSSING": "PASSAGE", "PASSAGE": "PASSAGE", "COPY": "SYSTEM", "UNREGISTERED_TAG": "ANOMALY", "SYSTEM": "SYSTEM"}


def test_a_backend_that_does_not_send_the_outcome_never_calls_a_registered_tag_unregistered():
    old = lambda **kw: {k: v for k, v in read("X", None, **kw).items() if k not in ("outcome", "half_side", "other_delta_ms", "event_class", "duplicate_of")}
    html = reader_page([old(movement_id="M-1", direction="IN"), old(), old(card_uuid=None, animal_id=None, gate_action="READER_HEARTBEAT")],
                       pair_window_ms=None)
    found = rows(html)
    assert [o for o, _ in found] == ["PASSAGE", "READ", "SYSTEM"]
    assert "Passage IN" in found[0][1] and "Tag read" in found[1][1] and "READER_HEARTBEAT" in found[2][1]
    assert "Unregistered" not in html and "Alert Raised" not in html


def test_text_from_a_tag_or_a_reader_is_never_run():
    html = reader_page([read("A", "UNREGISTERED_TAG", animal_id=None, card_uuid="<script>alert(1)</script>")])
    assert "<script>alert(1)" not in html and "&lt;script&gt;alert(1)&lt;/script&gt;" in html


def test_the_header_and_the_form_show_the_pairing_window_in_seconds():
    html = reader_page([], pair_window_ms=31693, repeat_debounce_ms=2500)
    assert "pairs reads within 31.693 s" in html
    assert re.search(r'name="pair_window_s"[^>]*value="31.693"', html)
    assert re.search(r'name="repeat_debounce_s"[^>]*value="2.5"', html)
    default = reader_page([])
    assert "pairs reads within 15 s" in default and re.search(r'name="pair_window_s"[^>]*value="15"', default)
    assert re.search(r'name="repeat_debounce_s"[^>]*value="3"', default)
    # a backend that does not send the settings yet still gets the defaults, not an empty box
    assert re.search(r'name="pair_window_s"[^>]*value="15"', reader_page([], pair_window_ms=None, repeat_debounce_ms=None))


# ---- the seconds the form is typed in ------------------------------------------------------------------------------
@pytest.mark.parametrize("text,ms", [("15", 15000), ("31.7", 31700), ("0,5", 500), (" 60 ", 60000), ("1e1", 10000),
                                     ("0.0004", 0), ("", None), ("   ", None), (None, None)])
def test_seconds_become_milliseconds_and_empty_means_not_sent(text, ms):
    assert main.seconds_to_ms(text, "The pairing window") == ms


@pytest.mark.parametrize("text", ["abc", "nan", "inf", "-inf", "12 s"])
def test_a_value_that_is_not_a_number_is_refused_with_a_message(text):
    with pytest.raises(ValueError) as e:
        main.seconds_to_ms(text, "The pairing window")
    assert "The pairing window must be a number of seconds" in str(e.value)


class ReaderBackend(BaseHTTPRequestHandler):
    puts, page = [], {"reader": READER, "transactions": []}
    answer = (200, {"status": "UPDATED"})

    def log_message(self, *a):
        pass

    def _send(self, code, body):
        raw = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path.startswith("/api/v1/readers/GATE-T-1"):
            self._send(200, ReaderBackend.page)
        elif self.path.startswith("/api/v1/barns"):
            self._send(200, [{"barnId": "BARN-DL-01", "code": "DL-01", "farmId": "FARM-1"}])
        else:
            self._send(404, {})

    def do_PUT(self):
        ReaderBackend.puts.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
        self._send(*ReaderBackend.answer)


@pytest.fixture
def reader_portal(monkeypatch):
    server = ThreadingHTTPServer(("127.0.0.1", 0), ReaderBackend)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    monkeypatch.setattr(main, "BACKEND_BASE_URL", f"http://127.0.0.1:{server.server_port}/api/v1")
    main.app.dependency_overrides[main.get_current_user] = lambda: {"username": "admin", "role": "SYSTEM_ADMIN", "token": "t"}
    ReaderBackend.puts = []
    ReaderBackend.answer = (200, {"status": "UPDATED"})
    ReaderBackend.page = {"reader": READER, "transactions": [read("A-in", "HALF_CROSSING", half_side="INNER", other_delta_ms=31693)]}
    yield TestClient(main.app, follow_redirects=False)
    main.app.dependency_overrides.clear()
    server.shutdown()


FORM = {"barn_id": "BARN-DL-01", "label": "Test pair", "direction_mode": "BIDIRECTIONAL", "reader_name": "0ED4",
        "outer_reader_name": "C1C4", "firmware_version": "1.0"}


def test_the_reader_page_opens_and_explains_the_half_crossing(reader_portal):
    r = reader_portal.get("/readers/GATE-T-1")
    assert r.status_code == 200 and "Half crossing, waiting for the outer reader" in r.text and "Unregistered Tag Read" not in r.text


def test_saving_a_longer_window_sends_milliseconds(reader_portal):
    r = reader_portal.post("/readers/GATE-T-1/update", data=dict(FORM, pair_window_s="60", repeat_debounce_s="3"))
    assert r.status_code == 303 and "notice=" in r.headers["location"]
    assert ReaderBackend.puts == [{"barnId": "BARN-DL-01", "label": "Test pair", "directionMode": "BIDIRECTIONAL", "firmwareVersion": "1.0",
                                   "readerName": "0ED4", "outerReaderName": "C1C4", "pairWindowMs": 60000, "repeatDebounceMs": 3000}]


def test_a_form_without_the_timing_fields_leaves_them_alone(reader_portal):
    reader_portal.post("/readers/GATE-T-1/update", data=FORM)
    assert "pairWindowMs" not in ReaderBackend.puts[0] and "repeatDebounceMs" not in ReaderBackend.puts[0]


def test_a_window_that_is_not_a_number_is_refused_before_anything_is_sent(reader_portal):
    r = reader_portal.post("/readers/GATE-T-1/update", data=dict(FORM, pair_window_s="a minute"))
    assert r.status_code == 303 and "problem=" in r.headers["location"] and "number%20of%20seconds" in r.headers["location"]
    assert ReaderBackend.puts == []


def test_the_reason_a_window_is_refused_by_the_backend_is_shown(reader_portal):
    ReaderBackend.answer = (400, {"error": "The pairing window must be between 1 s and 300 s (it was 600 s)."})
    r = reader_portal.post("/readers/GATE-T-1/update", data=dict(FORM, pair_window_s="600"))
    assert r.status_code == 303 and "problem=" in r.headers["location"] and "between%201%20s%20and%20300%20s" in r.headers["location"]
