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
