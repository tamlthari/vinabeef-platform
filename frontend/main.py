from datetime import date, datetime, timezone
from zoneinfo import ZoneInfo
from urllib.parse import quote

from fastapi import FastAPI, Request, Response, Form, Depends, HTTPException, Query, status
from fastapi.responses import HTMLResponse, RedirectResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.middleware.sessions import SessionMiddleware
from starlette.exceptions import HTTPException as StarletteHTTPException
import httpx
import json
import math
import os
import re

app = FastAPI(title="VinaBeef Enterprise Portal")
app.add_middleware(
    SessionMiddleware,
    secret_key="vinabeef-platform-session-secret-2026",
    same_site="lax",
    https_only=False
)
app.mount("/static", StaticFiles(directory="static"), name="static")
templates = Jinja2Templates(directory="templates")

BACKEND_BASE_URL = os.getenv("BACKEND_BASE_URL", "http://127.0.0.1:8080/api/v1")
DISPLAY_TIMEZONE = os.getenv("VINABEEF_TIMEZONE", "Asia/Ho_Chi_Minh")   # same zone the backend uses for "today"


def localtime(value, fmt: str = "%Y-%m-%d %H:%M:%S") -> str:
    """Instants arrive from the backend in UTC; show them as farm-local time. Anything unparseable is shown as it is."""
    if value is None or value == "":
        return ""
    try:
        if isinstance(value, (int, float)):
            moment = datetime.fromtimestamp(value / 1000 if value > 1e11 else value, tz=timezone.utc)
        else:
            moment = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
            if moment.tzinfo is None:
                moment = moment.replace(tzinfo=timezone.utc)
        return moment.astimezone(ZoneInfo(DISPLAY_TIMEZONE)).strftime(fmt)
    except Exception:
        return str(value).replace("T", " ")[:19]


templates.env.filters["localtime"] = localtime

FARM_TO_BARN_PREFIX = {
    "FARM-DAK-LAK": "BARN-DL-",
    "FARM-GIA-LAI": "BARN-GL-",
    "FARM-DONG-NAI": "BARN-DN-",
}


# --- Herd Count & Display ---

def build_page_range(current_page: int, total_pages: int) -> list:
    if total_pages <= 7:
        return list(range(1, total_pages + 1))
    if current_page <= 4:
        return [1, 2, 3, 4, 5, "...", total_pages]
    if current_page >= total_pages - 3:
        return [1, "...", total_pages - 4, total_pages - 3, total_pages - 2, total_pages - 1, total_pages]
    return [1, "...", current_page - 1, current_page, current_page + 1, "...", total_pages]


# Rows per page on the Anomaly Alerts and Gate Readers lists. The one place to change it (the Herd list pages by 100).
PAGE_SIZE = 25
PAGING_KEYS = ("total", "page", "per_page", "total_pages", "start_item", "end_item", "page_range")


def page_numbers(total: int, page: int, size: int = PAGE_SIZE) -> dict:
    """What a pager needs to know for `total` rows shown `size` at a time, with `page` kept inside 1..last."""
    total_pages = max(1, math.ceil(total / size))
    page = min(max(1, page), total_pages)
    return {
        "total": total,
        "page": page,
        "per_page": size,
        "total_pages": total_pages,
        "start_item": (page - 1) * size + 1 if total > 0 else 0,
        "end_item": min(page * size, total),
        "page_range": build_page_range(page, total_pages),
    }


def whole_number(value, default: int = 1) -> int:
    """A page number out of a query string or form; anything that is not a number is `default`."""
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return default


# The column filters of the list pages. The form field and the page's query string use these names; the backend gets
# camelCase for facility and barn (farmId, barnId). A blank filter is not sent.
HERD_FILTERS = ("farm", "barn", "health", "presence", "tags", "vaccination", "passes")
READER_FILTERS = ("farm", "barn", "direction", "pairing", "activity", "passes")
BACKEND_FILTER_NAMES = {"farm": "farmId", "barn": "barnId"}


def pick_filters(names, source) -> dict:
    """The filters of a list page out of a query string or a form; a missing one is empty text.
    Words (health, presence, ...) are made upper case so a typed link still matches the drop-downs; facility and barn are ids and keep their case."""
    out = {}
    for n in names:
        v = str(source.get(n) or "").strip()
        out[n] = v if n in ("farm", "barn") else v.upper()
    return out


def backend_filters(filters: dict) -> dict:
    """What the backend is asked for: its own names for facility and barn, and no blank filter."""
    return {BACKEND_FILTER_NAMES.get(k, k): v for k, v in (filters or {}).items() if v}


async def fetch_paginated_cattle(client: httpx.AsyncClient, headers: dict, q: str = "", page: int = 1, size: int = 100,
                                 sort: str = "", dir: str = "", filters: dict | None = None) -> dict:
    page = max(1, page)
    size = 100
    sort_key, sort_dir = sort, dir
    filters = {n: (filters or {}).get(n, "") for n in HERD_FILTERS}
    try:
        res = await client.get(
            f"{BACKEND_BASE_URL}/cattle",
            params={"q": q, "page": page, "size": size, "sort": sort, "dir": dir, **backend_filters(filters)},
            headers=headers
        )
        if res.status_code == 200:
            cattle = res.json()
            total_cattle = int(res.headers.get("X-Total-Count", len(cattle)))
            # the server says which order it really applied (an unknown key falls back to newest first)
            sort_key = res.headers.get("X-Sort-Key", sort_key)
            sort_dir = res.headers.get("X-Sort-Dir", sort_dir)
        else:
            print(f"[ERROR] Spring Boot /cattle returned {res.status_code}: {res.text}")
            cattle = []
            total_cattle = 0
    except Exception as e:
        print(f"[ERROR] FastAPI httpx request failed: {e}")
        cattle = []
        total_cattle = 0

    total_pages = max(1, math.ceil(total_cattle / size))
    page = min(page, total_pages)
    start_item = ((page - 1) * size + 1) if total_cattle > 0 else 0
    end_item = min(page * size, total_cattle)

    return {
        "cattle_list": cattle,
        "total_cattle": total_cattle,
        "page": page,
        "size": size,
        "total_pages": total_pages,
        "start_item": start_item,
        "end_item": end_item,
        "page_range": build_page_range(page, total_pages),
        "q": q,
        "sort": sort_key,
        "dir": sort_dir,
        "filters": filters,
        "filters_active": any(filters.values()),
    }


# --- Authentication & Session Helper ---

def get_current_user(request: Request):
    return request.session.get("user")


def get_auth_headers(user: dict = Depends(get_current_user)) -> dict:
    if not user:
        return {}
    return {"Authorization": f"Bearer {user['token']}"}


def get_allowed_farm_ids(user: dict) -> set:
    allowed = set(user.get("farm_scopes") or [])
    if user.get("farmId") and user["farmId"] != "ALL":
        allowed.add(user["farmId"])
    return allowed


def get_allowed_barn_ids(user: dict) -> set:
    raw = user.get("assignedBarns") or []
    result = set()
    for item in raw:
        if isinstance(item, dict):
            bid = item.get("barnId") or item.get("barn_id")
            if bid:
                result.add(str(bid))
        elif item:
            result.add(str(item))
    return result


def filter_farms_by_scope(farms: list, user: dict) -> list:
    if not user or not isinstance(farms, list):
        return []
    role = user.get("role", "")
    if role in ("SYSTEM_ADMIN", "EXECUTIVE"):
        return farms

    if role == "FARM_MANAGER":
        allowed_farms = get_allowed_farm_ids(user)
        if not allowed_farms:
            return farms   # session without scope info (old login): the backend already returned only this manager's farm
        return [f for f in farms if f.get("farmId") in allowed_farms]

    if role == "BARN_SUPERVISOR":
        allowed_barns = get_allowed_barn_ids(user)
        if not allowed_barns:
            return farms   # same: the backend limits a supervisor to the assigned barns
        scoped_farms = []
        for f in farms:
            matching_barns = [b for b in f.get("barns", []) if b.get("barnId") in allowed_barns]
            if matching_barns:
                f_copy = dict(f)
                f_copy["barns"] = matching_barns
                f_copy["totalCapacity"] = sum(b.get("capacity", 0) for b in matching_barns)
                f_copy["totalHeadcount"] = sum(b.get("currentHeadcount", 0) for b in matching_barns)
                f_copy["totalInCount"] = sum(b.get("inCount", 0) for b in matching_barns)
                f_copy["totalOutCount"] = sum(b.get("outCount", 0) for b in matching_barns)
                f_copy["totalOutPasture"] = sum(b.get("outPasture", 0) for b in matching_barns)
                f_copy["totalNotSeen"] = sum(b.get("notSeen", 0) for b in matching_barns)
                cap = f_copy["totalCapacity"]
                f_copy["utilizationRate"] = round((f_copy["totalHeadcount"] * 100.0) / cap, 1) if cap else 0
                f_copy["totalAnimals"] = sum(b.get("assignedAnimals", 0) for b in matching_barns)
                f_copy["vaccinatedAnimals"] = sum(b.get("vaccinatedAnimals", 0) for b in matching_barns)
                herd = f_copy["totalAnimals"]
                f_copy["vaccinationRate"] = round((f_copy["vaccinatedAnimals"] * 100.0) / herd, 1) if herd else 0
                scoped_farms.append(f_copy)
        return scoped_farms

    return []


def filter_barns_by_scope(barns: list, user: dict) -> list:
    if not user or not isinstance(barns, list):
        return []
    role = user.get("role", "")
    if role in ("SYSTEM_ADMIN", "EXECUTIVE"):
        return barns

    if role == "FARM_MANAGER":
        allowed_farms = get_allowed_farm_ids(user)
        if not allowed_farms:
            return barns
        prefixes = tuple(FARM_TO_BARN_PREFIX[f] for f in allowed_farms if f in FARM_TO_BARN_PREFIX)
        scoped = []
        for b in barns:
            farm_id = str(b.get("farmId") or b.get("farm_id") or "")
            barn_id = str(b.get("barnId") or b.get("barn_id") or "")
            if farm_id in allowed_farms or (prefixes and barn_id.startswith(prefixes)):
                scoped.append(b)
        return scoped

    if role == "BARN_SUPERVISOR":
        allowed_barns = get_allowed_barn_ids(user)
        if not allowed_barns:
            return barns
        return [
            b for b in barns
            if str(b.get("barnId") or b.get("barn_id") or "") in allowed_barns
        ]

    return []


def filter_readers_by_scope(readers: list, user: dict) -> list:
    if not user or not isinstance(readers, list):
        return []
    role = user.get("role", "")
    if role in ("SYSTEM_ADMIN", "EXECUTIVE"):
        return readers

    if role == "FARM_MANAGER":
        allowed_farms = get_allowed_farm_ids(user)
        if not allowed_farms:
            return readers
        prefixes = tuple(FARM_TO_BARN_PREFIX[f] for f in allowed_farms if f in FARM_TO_BARN_PREFIX)
        scoped = []
        for r in readers:
            farm_id = str(r.get("farm_id") or r.get("farmId") or "")
            barn_id = str(r.get("barn_id") or r.get("barnId") or "")
            if farm_id in allowed_farms or (prefixes and barn_id.startswith(prefixes)):
                scoped.append(r)
        return scoped

    if role == "BARN_SUPERVISOR":
        allowed_barns = get_allowed_barn_ids(user)
        if not allowed_barns:
            return readers
        return [
            r for r in readers
            if str(r.get("barn_id") or r.get("barnId") or "") in allowed_barns
        ]

    return []


# --- Role Based Access & 403 Security Banner Helpers ---

def extract_backend_error(res: httpx.Response, fallback: str = "") -> str:
    try:
        data = res.json()
        if isinstance(data, dict):
            return str(data.get("error") or data.get("detail") or data.get("message") or fallback)
    except Exception:
        pass
    return (res.text or fallback).strip()


def flash_header(message: str, kind: str = "ok") -> dict:
    """HX-Trigger header: base.html shows the message in the flash box after an HTMX swap."""
    return {"HX-Trigger": json.dumps({"flash": {"message": message, "kind": kind}})}


def seconds_to_ms(text, label: str):
    """A form field in seconds ("31.7", "60") becomes whole milliseconds. Empty or missing means "not sent" (None).

    Anything that is not a finite number raises ValueError with a message that can be shown to the person. The
    backend checks the allowed range and says what it is.
    """
    if text is None or not str(text).strip():
        return None
    try:
        value = float(str(text).strip().replace(",", "."))
    except ValueError:
        raise ValueError(f"{label} must be a number of seconds (you entered '{str(text).strip()[:20]}').")
    if not math.isfinite(value):
        raise ValueError(f"{label} must be a number of seconds.")
    return int(round(value * 1000))


def redirect_with(path: str, notice: str = "", problem: str = "") -> RedirectResponse:
    """Redirect after a form post; base.html shows `notice` (green) or `problem` (red) above the page."""
    params = []
    if notice:
        params.append("notice=" + quote(notice))
    if problem:
        params.append("problem=" + quote(problem))
    return RedirectResponse(url=path + ("?" + "&".join(params) if params else ""),
                            status_code=status.HTTP_303_SEE_OTHER)


def format_403_security_message(user: dict, detail: str, path: str) -> str:
    username = user.get("username", "unknown") if user else "unknown"
    clean_detail = (detail or "").strip()

    if clean_detail.startswith("Access Denied (403):"):
        if "Security violation logged in Audit ledger." not in clean_detail:
            return f"{clean_detail.rstrip('.')}. Security violation logged in Audit ledger."
        return clean_detail

    if "is not authorized to " in clean_detail:
        idx = clean_detail.index("is not authorized to ")
        action_clause = clean_detail[idx + len("is not authorized to "):].rstrip(".")
        return f"Access Denied (403): User '{username}' is not authorized to {action_clause}. Security violation logged in Audit ledger."

    lower = clean_detail.lower()
    if "reporting" in lower or path.startswith("/reports"):
        action_desc = "access Reporting & Regulatory Compliance"
    elif "audit" in lower or path.startswith("/audit"):
        action_desc = "access the Enterprise Audit Ledger"
    elif "surveillance" in lower or path.startswith("/surveillance"):
        action_desc = "access Disease Surveillance"
    elif path.startswith("/users"):
        action_desc = "manage Operators & Role Assignments"
    elif clean_detail:
        stripped = (
            clean_detail
            .removeprefix("Access denied:")
            .removeprefix("Access Denied:")
            .strip()
            .rstrip(".")
        )
        action_desc = stripped if stripped.startswith(("mutate ", "transfer ", "update ", "create ", "modify ", "record ", "access ")) else f"perform action ({stripped})"
    else:
        action_desc = f"access '{path}'"

    return f"Access Denied (403): User '{username}' is not authorized to {action_desc}. Security violation logged in Audit ledger."


def render_403_alert_html(message: str) -> str:
    return f"""
    <div class="mb-4 px-5 py-3.5 rounded-lg bg-rose-50 border border-rose-200 text-rose-800 text-xs font-semibold flex items-center space-x-2.5 shadow-xs">
        <svg class="w-4 h-4 text-rose-600 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
        </svg>
        <span>{message}</span>
    </div>
    """


async def log_security_violation_best_effort(user: dict, path: str, formatted_msg: str):
    try:
        async with httpx.AsyncClient(timeout=2.0) as client:
            await client.post(
                f"{BACKEND_BASE_URL}/audit/security-violation",
                json={
                    "username": user.get("username"),
                    "role": user.get("role"),
                    "path": path,
                    "reason": formatted_msg,
                },
                headers=get_auth_headers(user),
            )
    except Exception:
        pass


@app.exception_handler(StarletteHTTPException)
async def custom_http_exception_handler(request: Request, exc: StarletteHTTPException):
    if exc.status_code == 403:
        user = request.session.get("user") if "session" in request.scope else None
        if not user:
            return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)

        formatted_msg = format_403_security_message(user, str(exc.detail or ""), request.url.path)
        await log_security_violation_best_effort(user, request.url.path, formatted_msg)

        if "application/json" in request.headers.get("accept", ""):
            return JSONResponse(status_code=403, content={"detail": formatted_msg})

        if request.headers.get("hx-request") == "true":
            return HTMLResponse(content=render_403_alert_html(formatted_msg), status_code=403)

        return templates.TemplateResponse(
            "access_denied.html",
            {
                "request": request,
                "user": user,
                "access_denied_message": formatted_msg,
                "attempted_path": request.url.path,
            },
            status_code=403,
        )

    return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})


# --- Page Routes ---

@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request):
    user = get_current_user(request)
    if user:
        return RedirectResponse(url="/", status_code=status.HTTP_302_FOUND)
    return templates.TemplateResponse("login.html", {"request": request, "error": None})


async def assigned_barn_codes(client: httpx.AsyncClient, login: dict) -> list:
    """The codes of the barns a supervisor is assigned to. The login answer carries their keys, which are not what the rest of
    the portal shows; a barn whose code cannot be found keeps its key, and a failure leaves the list empty (the header then falls back)."""
    ids = [i for i in (login.get("assignedBarns") or []) if isinstance(i, str)]
    if not ids or not login.get("token"):
        return []
    try:
        res = await client.get(f"{BACKEND_BASE_URL}/barns", headers={"Authorization": f"Bearer {login['token']}"})
        if res.status_code != 200:
            return []
        code_of = {b.get("barnId"): b.get("code") for b in res.json() if isinstance(b, dict)}
    except (httpx.HTTPError, ValueError):
        return []
    return [code_of.get(i) or i for i in ids]


@app.post("/login")
async def login_submit(request: Request, username: str = Form(...), password: str = Form(...)):
    async with httpx.AsyncClient(timeout=5.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/auth/login",
            json={"username": username, "password": password}
        )
        if res.status_code != 200:
            return templates.TemplateResponse(
                "login.html",
                {"request": request, "error": "Invalid username or password"}
            )

        data = res.json()
        farm_id = data.get("farmId", "ALL")
        farm_scopes = data.get("farm_scopes") or ([farm_id] if farm_id and farm_id != "ALL" else [])
        barn_codes = await assigned_barn_codes(client, data)

        request.session["user"] = {
            "username": data.get("username", username),
            "fullName": data.get("fullName", username),
            "role": data.get("role", "EXECUTIVE"),
            "farmId": farm_id,
            "farm_scopes": farm_scopes,
            "assignedBarns": data.get("assignedBarns", []),
            "assignedBarnCodes": barn_codes,
            "token": data.get("token")
        }
    return RedirectResponse(url="/", status_code=status.HTTP_303_SEE_OTHER)


@app.get("/logout")
async def logout(request: Request):
    request.session.clear()
    return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)


# 1. Live Gate Monitor (Home)
@app.get("/", response_class=HTMLResponse)
async def home_page(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=6.0) as client:
        try:
            dist_res = await client.get(f"{BACKEND_BASE_URL}/facilities/distribution", headers=headers)
            farms = dist_res.json() if dist_res.status_code == 200 else []
        except Exception:
            farms = []

        try:
            mov_res = await client.get(f"{BACKEND_BASE_URL}/movements/recent", headers=headers)
            movements = mov_res.json() if mov_res.status_code == 200 else []
        except Exception:
            movements = []

    farms = filter_farms_by_scope(farms, user)

    return templates.TemplateResponse("index.html", {
        "request": request,
        "user": user,
        "farms": farms,
        "movements": movements,
        "current_date": datetime.now()
    })


# 2. Audit Trail Viewer (Restricted to SYSTEM_ADMIN)
@app.get("/audit", response_class=HTMLResponse)
async def audit_page(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")
    if user["role"] != "SYSTEM_ADMIN":
        raise HTTPException(status_code=403, detail="Access denied: SYSTEM_ADMIN role required.")

    return templates.TemplateResponse("audit.html", {"request": request, "user": user})


# 3. Reports & Analytics (Restricted to EXECUTIVE, FARM_MANAGER, SYSTEM_ADMIN)
@app.get("/reports", response_class=HTMLResponse)
async def reports_page(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")
    if user["role"] not in ["EXECUTIVE", "FARM_MANAGER", "SYSTEM_ADMIN"]:
        raise HTTPException(status_code=403, detail="Access denied: Insufficient privileges for reporting.")

    # facilities and barns for the filter drop-downs (the backend already limits them to what this user may see)
    farms = []
    async with httpx.AsyncClient(timeout=6.0) as client:
        try:
            res = await client.get(f"{BACKEND_BASE_URL}/facilities/distribution", headers=get_auth_headers(user))
            if res.status_code == 200:
                farms = filter_farms_by_scope(res.json(), user)
        except Exception:
            farms = []

    return templates.TemplateResponse("reports.html", {"request": request, "user": user, "farms": farms})


# 4. Cattle Transfer Workbench
@app.get("/transfers", response_class=HTMLResponse)
async def transfers_page(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")
    if user["role"] not in ["FARM_MANAGER", "SYSTEM_ADMIN"]:
        raise HTTPException(status_code=403, detail="Access denied: Only Farm Managers can approve transfers.")

    return templates.TemplateResponse("transfers.html", {"request": request, "user": user})


# --- HTMX Dynamic Partial Endpoints ---

@app.get("/htmx/barns", response_class=HTMLResponse)
async def htmx_barns(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return HTMLResponse("Session expired. Please sign in again.", status_code=401)

    headers = get_auth_headers(user)
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            res = await client.get(f"{BACKEND_BASE_URL}/facilities/distribution", headers=headers)
            res.raise_for_status()
            farms = filter_farms_by_scope(res.json(), user)
    except (httpx.HTTPError, ValueError) as exc:
        return templates.TemplateResponse(
            "partials/headcount_cards.html",
            {"request": request, "farms": [], "dashboard_error": str(exc)},
            status_code=502
        )

    return templates.TemplateResponse(
        "partials/headcount_cards.html",
        {"request": request, "farms": farms, "dashboard_error": None}
    )


@app.get("/htmx/movements", response_class=HTMLResponse)
async def htmx_movements(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return HTMLResponse("Session expired.", status_code=401)

    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            res = await client.get(
                f"{BACKEND_BASE_URL}/movements/recent",
                headers=get_auth_headers(user)
            )
            res.raise_for_status()
            movements = res.json()
    except (httpx.HTTPError, ValueError) as exc:
        return templates.TemplateResponse(
            "partials/movement_rows.html",
            {"request": request, "movements": [], "error": str(exc)},
            status_code=502
        )

    return templates.TemplateResponse(
        "partials/movement_rows.html",
        {"request": request, "movements": movements}
    )


# The dashboard's animal-health panel. Each tab is one list; the numbers beside the tab names are the events of the last days.
HEALTH_TABS = (
    ("now", "In quarantine now"),
    ("quarantine", "Quarantine events"),
    ("treatment", "Treatments"),
    ("vaccination", "Vaccinations"),
    ("all", "All health events"),
)
HEALTH_TAB_COUNT = {"now": ("now", "quarantineNow"), "quarantine": ("recent", "quarantineEvents"),
                    "treatment": ("recent", "treatments"), "vaccination": ("recent", "vaccinations"), "all": ("recent", "events")}


def pick_health_tab(tab: str, summary: dict) -> str:
    """The tab to show. 'auto' (the first load) opens on the animals in quarantine when there are any, else on all events."""
    names = [t for t, _ in HEALTH_TABS]
    if tab in names:
        return tab
    return "now" if (summary.get("now") or {}).get("quarantineNow", 0) else "all"


@app.get("/htmx/health-panel", response_class=HTMLResponse)
async def htmx_health_panel(request: Request, tab: str = "auto", user: dict = Depends(get_current_user)):
    """Dashboard: animals in quarantine / under treatment now, and the newest vaccination, treatment and quarantine events."""
    if not user:
        return HTMLResponse("<div class='text-rose-500 p-4 text-xs'>Session expired. Please sign in again.</div>", status_code=401)

    headers = get_auth_headers(user)
    ctx = {"request": request, "user": user, "tabs": HEALTH_TABS, "tab": "all", "summary": {}, "rows": [], "error": "",
           "tab_counts": {}, "is_global": user.get("role") in ("SYSTEM_ADMIN", "EXECUTIVE"),
           "can_report": user.get("role") in ("SYSTEM_ADMIN", "EXECUTIVE", "FARM_MANAGER")}
    try:
        async with httpx.AsyncClient(timeout=6.0) as client:
            res = await client.get(f"{BACKEND_BASE_URL}/health/summary", headers=headers)
            res.raise_for_status()
            summary = res.json()
            tab = pick_health_tab(tab, summary)
            if tab == "now":
                res = await client.get(f"{BACKEND_BASE_URL}/health/quarantine", params={"status": "QUARANTINE", "limit": 12}, headers=headers)
            else:
                kind = {"quarantine": "QUARANTINE", "treatment": "TREATMENT", "vaccination": "VACCINATION", "all": "ALL"}[tab]
                res = await client.get(f"{BACKEND_BASE_URL}/health/events", params={"kind": kind, "limit": 12}, headers=headers)
            res.raise_for_status()
            rows = res.json()
    except (httpx.HTTPError, ValueError) as exc:
        ctx["error"] = f"Animal health could not be loaded: {exc}"
        return templates.TemplateResponse("partials/health_panel.html", ctx)     # 200: htmx does not show an error status, and the next poll retries

    ctx.update({"tab": tab, "summary": summary, "rows": rows,
                "tab_counts": {name: (summary.get(group) or {}).get(key, 0) for name, (group, key) in HEALTH_TAB_COUNT.items()}})
    return templates.TemplateResponse("partials/health_panel.html", ctx)


def summarize_audit(entry: dict) -> dict:
    """Input / output columns of the audit table, made from the stored `details` JSON (see AuditLoggingAspect).
    Only the request is stored, so the output column states the outcome and, for a failure, the error."""
    raw = entry.get("details")
    try:
        d = json.loads(raw) if isinstance(raw, str) else (raw or {})
    except ValueError:
        d = {}
    args = d.get("arguments") if isinstance(d, dict) else None
    parts = []
    for a in args or []:
        if isinstance(a, dict):
            parts.append(", ".join(f"{k}={'' if v is None else v}" for k, v in list(a.items())[:6]))
        else:
            parts.append(str(a))
    input_summary = " | ".join(p for p in parts if p) or "(no arguments)"
    if len(input_summary) > 220:
        input_summary = input_summary[:220] + "..."
    ok = entry.get("outcome") == "SUCCESS"
    ms = d.get("executionTimeMs") if isinstance(d, dict) else None
    if ok:
        output_summary = "Completed" + (f" in {ms} ms" if ms is not None else "")
    else:
        output_summary = "Failed: " + str((d.get("error") if isinstance(d, dict) else "") or "no error text recorded")
    entry = dict(entry)
    entry["input_summary"] = input_summary
    entry["output_summary"] = output_summary
    entry["pretty"] = json.dumps(d, indent=2, ensure_ascii=False) if d else ""
    return entry


@app.get("/htmx/audit-table", response_class=HTMLResponse)
async def htmx_audit_table(
    request: Request,
    actor: str = "", action: str = "", target: str = "", outcome: str = "", preset: str = "7d",
    date_from: str = Query("", alias="from"), date_to: str = Query("", alias="to"), page: int = 1,
    user: dict = Depends(get_current_user)
):
    if not user or user["role"] != "SYSTEM_ADMIN":
        msg = format_403_security_message(user or {}, "access the Enterprise Audit Ledger", request.url.path)
        return HTMLResponse(render_403_alert_html(msg), status_code=403)

    size = 25
    page = max(1, page)
    ctx = {"request": request, "logs": [], "total": 0, "page": 1, "total_pages": 1, "page_range": [1], "start_item": 0, "end_item": 0,
           "actions": [], "targets": [], "sel_action": action, "sel_target": target, "period_label": "", "error": ""}
    async with httpx.AsyncClient(timeout=8.0) as client:
        try:
            res = await client.get(f"{BACKEND_BASE_URL}/management/audit-logs", headers=get_auth_headers(user), params={
                "actor": actor.strip(), "action": action, "target": target, "outcome": outcome, "preset": preset or "7d",
                "from": date_from, "to": date_to, "page": page, "size": size})
        except httpx.HTTPError as exc:
            ctx["error"] = f"The audit trail could not be loaded: {exc}"
            return templates.TemplateResponse("partials/audit_table.html", ctx, status_code=502)

    if res.status_code != 200:
        ctx["error"] = extract_backend_error(res, "The audit trail could not be loaded.")
        return templates.TemplateResponse("partials/audit_table.html", ctx)

    body = res.json()
    total = int(body.get("total", 0))
    total_pages = max(1, math.ceil(total / size))
    page = min(page, total_pages)
    ctx.update({
        "logs": [summarize_audit(e) for e in body.get("items", [])], "total": total, "page": page, "total_pages": total_pages,
        "page_range": build_page_range(page, total_pages), "start_item": ((page - 1) * size + 1) if total else 0,
        "end_item": min(page * size, total), "actions": body.get("actions", []), "targets": body.get("targets", []),
        "period_label": body.get("periodLabel", ""),
    })
    return templates.TemplateResponse("partials/audit_table.html", ctx)


@app.get("/htmx/ingestion-status", response_class=HTMLResponse)
async def htmx_ingestion_status(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return HTMLResponse("<div class='p-4 text-rose-400'>Session expired.</div>", status_code=401)

    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            res = await client.get(
                f"{BACKEND_BASE_URL}/ingestion/failover-status",
                headers=get_auth_headers(user)
            )
            res.raise_for_status()
            ingestion_status = res.json()
    except (httpx.HTTPError, ValueError) as exc:
        return templates.TemplateResponse(
            "partials/ingestion_status.html",
            {"request": request, "error": str(exc)}
        )

    return templates.TemplateResponse(
        "partials/ingestion_status.html",
        {"request": request, "status": ingestion_status, "error": None}
    )


REPORT_FILTERS = ("farm_id", "barn_id", "reader", "animal", "direction", "method", "event", "result", "preset",
                  "text", "outcome", "record_type", "current_status", "presence")

# The health reports, by the word that ends their address on the backend.
HEALTH_REPORTS = ("vaccinations", "treatments", "history", "quarantine")


def report_params(type: str, filters: dict, date_from: str, date_to: str, sort: str, dir: str, page: int, size: int) -> tuple:
    """Backend path and query string of one report; only the filters that belong to that report are sent."""
    common = {"farmId": filters["farm_id"], "preset": filters["preset"] or "today", "from": date_from, "to": date_to}
    if type == "passages":
        return "/reports/passages", {**common, "barnId": filters["barn_id"], "reader": filters["reader"], "animal": filters["animal"],
                                     "direction": filters["direction"], "method": filters["method"],
                                     "sort": sort, "dir": dir, "page": page, "size": size}
    if type == "reads":
        return "/reports/reads", {**common, "barnId": filters["barn_id"], "reader": filters["reader"], "animal": filters["animal"],
                                  "event": filters["event"], "result": filters["result"],
                                  "sort": sort, "dir": dir, "page": page, "size": size}
    if type == "quarantine":
        # who is in quarantine (or under treatment) now: a status, so there is no period
        return "/reports/health/quarantine", {"farmId": filters["farm_id"], "barnId": filters["barn_id"], "animal": filters["animal"],
                                              "status": filters["current_status"], "presence": filters["presence"],
                                              "sort": sort, "dir": dir, "page": page, "size": size}
    if type in HEALTH_REPORTS:
        return f"/reports/health/{type}", {"farmId": filters["farm_id"], "barnId": filters["barn_id"], "animal": filters["animal"],
                                           "text": filters["text"], "status": filters["outcome"],
                                           "recordType": filters["record_type"] if type == "history" else "",
                                           "preset": filters["preset"] or "30d", "from": date_from, "to": date_to,
                                           "sort": sort, "dir": dir, "page": page, "size": size}
    return "/reports/herd-balance", common


@app.get("/htmx/report-data", response_class=HTMLResponse)
async def htmx_report_data(
    request: Request,
    type: str = "herd",
    farm_id: str = "", barn_id: str = "", reader: str = "", animal: str = "",
    direction: str = "", method: str = "", event: str = "", result: str = "", preset: str = "today",
    text: str = "", outcome: str = "", record_type: str = "", current_status: str = "", presence: str = "",
    date_from: str = Query("", alias="from"), date_to: str = Query("", alias="to"),
    sort: str = "", dir: str = "", page: int = 1,
    user: dict = Depends(get_current_user)
):
    if not user:
        return HTMLResponse("<div class='text-rose-500 p-4 text-xs'>Session expired. Please sign in again.</div>", status_code=401)
    if user.get("role") not in ["EXECUTIVE", "FARM_MANAGER", "SYSTEM_ADMIN"]:
        msg = format_403_security_message(user, "access Reporting & Regulatory Compliance", request.url.path)
        return HTMLResponse(render_403_alert_html(msg), status_code=403)

    type = type if type in ("herd", "passages", "reads", *HEALTH_REPORTS) else "herd"
    page = max(1, page)
    size = 25
    filters = {"farm_id": farm_id, "barn_id": barn_id, "reader": reader.strip(), "animal": animal.strip(), "direction": direction,
               "method": method, "event": event.strip(), "result": result, "preset": preset,
               "text": text.strip(), "outcome": outcome, "record_type": record_type, "current_status": current_status, "presence": presence}
    path, params = report_params(type, filters, date_from, date_to, sort, dir, page, size)

    ctx = {"request": request, "report_type": type, "tz": DISPLAY_TIMEZONE, "error": "", "period_label": "", "export_url": ""}
    async with httpx.AsyncClient(timeout=20.0) as client:
        try:
            res = await client.get(f"{BACKEND_BASE_URL}{path}", params=params, headers=get_auth_headers(user))
        except httpx.HTTPError as exc:
            ctx["error"] = f"The report could not be loaded: {exc}"
            return templates.TemplateResponse("partials/report_content.html", ctx, status_code=502)

    if res.status_code == 403:
        msg = format_403_security_message(user, extract_backend_error(res, "access this report"), request.url.path)
        return HTMLResponse(render_403_alert_html(msg), status_code=403)
    if res.status_code != 200:
        ctx["error"] = extract_backend_error(res, "The report could not be loaded.")
        return templates.TemplateResponse("partials/report_content.html", ctx, status_code=200)

    body = res.json()
    ctx["period_label"] = body.get("periodLabel", "")
    if type == "herd":
        ctx.update({"rows": body.get("rows", []), "totals": body.get("totals", {})})
    else:
        total = int(body.get("total", 0))
        total_pages = max(1, math.ceil(total / size))
        page = min(page, total_pages)
        ctx.update({
            "items": body.get("items", []), "total": total, "in_count": int(body.get("inCount", 0)),
            "out_count": int(body.get("outCount", 0)), "with_movement": int(body.get("withMovement", 0)),
            "animals": int(body.get("animals", 0)), "quarantine_count": int(body.get("quarantineCount", 0)),
            "treatment_count": int(body.get("treatmentCount", 0)), "at_pasture_count": int(body.get("atPastureCount", 0)),
            "page": page, "total_pages": total_pages, "page_range": build_page_range(page, total_pages),
            "start_item": ((page - 1) * size + 1) if total else 0, "end_item": min(page * size, total),
            "sort": body.get("sort", ""), "dir": body.get("dir", ""),
        })
        if type == "passages" or type in HEALTH_REPORTS:
            qs = {k: v for k, v in {**filters, "type": type, "from": date_from, "to": date_to}.items() if v}
            ctx["export_url"] = "/reports/export?" + "&".join(f"{k}={quote(str(v))}" for k, v in qs.items())
    return templates.TemplateResponse("partials/report_content.html", ctx)


@app.get("/reports/export")
async def reports_export(
    type: str = "passages",
    farm_id: str = "", barn_id: str = "", reader: str = "", animal: str = "", direction: str = "", method: str = "",
    text: str = "", outcome: str = "", record_type: str = "", current_status: str = "", presence: str = "",
    preset: str = "today", date_from: str = Query("", alias="from"), date_to: str = Query("", alias="to"),
    user: dict = Depends(get_current_user)
):
    """CSV of the current filters of the gate passages or of a health report (the file the backend builds; nothing is rebuilt here)."""
    if not user:
        return RedirectResponse(url="/login")
    if user.get("role") not in ["EXECUTIVE", "FARM_MANAGER", "SYSTEM_ADMIN"]:
        raise HTTPException(status_code=403, detail="Access denied: Insufficient privileges for reporting.")
    if type in HEALTH_REPORTS:
        filters = {"farm_id": farm_id, "barn_id": barn_id, "reader": "", "animal": animal, "direction": "", "method": "", "event": "",
                   "result": "", "preset": preset, "text": text, "outcome": outcome, "record_type": record_type,
                   "current_status": current_status, "presence": presence}
        path, params = report_params(type, filters, date_from, date_to, "", "", 1, 25)
        for paging in ("sort", "dir", "page", "size"):
            params.pop(paging, None)
        url, fallback = f"{BACKEND_BASE_URL}{path}/export", f'attachment; filename="vinabeef_health_{type}.csv"'
    else:
        params = {"farmId": farm_id, "barnId": barn_id, "reader": reader, "animal": animal, "direction": direction,
                  "method": method, "preset": preset or "today", "from": date_from, "to": date_to}
        url, fallback = f"{BACKEND_BASE_URL}/reports/passages/export", 'attachment; filename="vinabeef_gate_passages.csv"'
    async with httpx.AsyncClient(timeout=60.0) as client:
        res = await client.get(url, headers=get_auth_headers(user), params=params)
    if res.status_code != 200:
        raise HTTPException(status_code=res.status_code, detail=extract_backend_error(res, "The export failed."))
    return Response(content=res.content, media_type="text/csv; charset=utf-8",
                    headers={"Content-Disposition": res.headers.get("content-disposition", fallback)})


@app.post("/htmx/transfer-action", response_class=HTMLResponse)
async def htmx_transfer_action(
    request: Request,
    farm_id: str = Form(...),
    animal_id: str = Form(...),
    target_barn_id: str = Form(...),
    reason: str = Form(...),
    user: dict = Depends(get_current_user)
):
    if not user:
        return "<div class='p-4 text-rose-400 bg-rose-950/40 rounded-lg'>Session expired.</div>"

    payload = {
        "animalId": animal_id,
        "targetBarnId": target_barn_id,
        "reason": reason
    }

    async with httpx.AsyncClient(timeout=4.0) as client:
        try:
            res = await client.post(
                f"{BACKEND_BASE_URL}/management/farms/{farm_id}/transfers",
                json=payload,
                headers=get_auth_headers(user)
            )

            if res.status_code == 200:
                data = res.json()
                return f"""
                <div class="p-4 bg-emerald-50 border border-emerald-200 text-emerald-800 rounded-lg text-xs font-semibold">
                    <strong>Mutation Approved:</strong> Cattle {data.get('animalId')} transfer registered in {data.get('farmId')} by {data.get('approvedBy')}.
                </div>
                """
            elif res.status_code == 403:
                msg = format_403_security_message(user, f"mutate Farm '{farm_id}'", request.url.path)
                await log_security_violation_best_effort(user, request.url.path, msg)
                return render_403_alert_html(msg)
            else:
                return f"""
                <div class="p-4 bg-amber-50 border border-amber-200 text-amber-800 rounded-lg text-xs">
                    <strong>Backend Error ({res.status_code}):</strong> {extract_backend_error(res, res.text)}
                </div>
                """
        except Exception as e:
            return f"""
            <div class="p-4 bg-rose-50 border border-rose-200 text-rose-800 rounded-lg text-xs">
                <strong>Failure:</strong> {str(e)}
            </div>
            """


# ================= CATTLE & RFID TAG MANAGEMENT =================

async def fetch_scoped_farms(client: httpx.AsyncClient, headers: dict, user: dict) -> list:
    """Facilities with their barns, limited to what this person may see (for the facility / barn filters)."""
    try:
        res = await client.get(f"{BACKEND_BASE_URL}/facilities/distribution", headers=headers)
        return filter_farms_by_scope(res.json(), user) if res.status_code == 200 else []
    except Exception:
        return []


@app.get("/herd", response_class=HTMLResponse)
async def herd_page(request: Request, page: int = 1, q: str = "", sort: str = "", dir: str = "", user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    filters = pick_filters(HERD_FILTERS, request.query_params)      # a bookmark or a dashboard link can open the list already filtered
    async with httpx.AsyncClient(timeout=10.0) as client:
        pagination = await fetch_paginated_cattle(client, headers, q=q, page=page, size=100, sort=sort, dir=dir, filters=filters)
        try:
            barns_res = await client.get(f"{BACKEND_BASE_URL}/barns", headers=headers)
            barns = filter_barns_by_scope(barns_res.json() if barns_res.status_code == 200 else [], user)
        except Exception:
            barns = []
        farms = await fetch_scoped_farms(client, headers, user)

    return templates.TemplateResponse("herd.html", {
        "request": request,
        "user": user,
        "barns": barns,
        "farms": farms,
        **pagination
    })


@app.get("/htmx/herd-list", response_class=HTMLResponse)
async def htmx_herd_list(request: Request, page: int = 1, q: str = "", sort: str = "", dir: str = "", user: dict = Depends(get_current_user)):
    headers = get_auth_headers(user) if user else {}
    filters = pick_filters(HERD_FILTERS, request.query_params)
    async with httpx.AsyncClient(timeout=10.0) as client:
        pagination = await fetch_paginated_cattle(client, headers, q=q, page=page, size=100, sort=sort, dir=dir, filters=filters)

    return templates.TemplateResponse("partials/herd_table_content.html", {
        "request": request,
        "user": user,
        "oob_update": True,
        **pagination
    })


async def herd_view_from_form(request: Request) -> dict:
    """The search, order and filters the herd list had on screen, sent along with a create or remove so the list keeps its view."""
    try:
        form = await request.form()
    except Exception:
        form = {}
    return {"q": str(form.get("q") or ""), "sort": str(form.get("sort") or ""), "dir": str(form.get("dir") or ""),
            "filters": pick_filters(HERD_FILTERS, form)}


@app.post("/htmx/herd/create", response_class=HTMLResponse)
async def htmx_herd_create(
    request: Request,
    animal_id: str = Form(...),
    barn_id: str = Form(...),
    breed: str = Form("Brahman Cross"),
    weight_kg: float = Form(420.0),
    tag_epc: str = Form(""),
    user: dict = Depends(get_current_user)
):
    if not user:
        return "<div class='p-4 text-red-600 text-xs'>Session expired.</div>"

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=10.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/cattle",
            json={
                "animalId": animal_id,
                "barnId": barn_id,
                "breed": breed,
                "weightKg": weight_kg,
                "tagEpc": tag_epc
            },
            headers=headers
        )
        if res.status_code == 403:
            err = extract_backend_error(res, f"register cattle in Barn '{barn_id}'")
            msg = format_403_security_message(user, err, request.url.path)
            await log_security_violation_best_effort(user, request.url.path, msg)
            return render_403_alert_html(msg)
        if res.status_code != 200:
            err = extract_backend_error(res, "Failed to register cattle")
            return f"<div class='p-4 rounded bg-red-50 border border-red-200 text-red-700 text-xs'>{err}</div>"

        view = await herd_view_from_form(request)
        pagination = await fetch_paginated_cattle(client, headers, page=1, size=100, **view)

    return templates.TemplateResponse("partials/herd_table_content.html", {
        "request": request,
        "user": user,
        "oob_update": True,
        **pagination
    })


@app.post("/htmx/herd/{animal_id}/delete", response_class=HTMLResponse)
async def htmx_herd_delete(request: Request, animal_id: str, user: dict = Depends(get_current_user)):
    """Delete or archive from the herd list. The backend decides which one and says so."""
    if not user:
        return "<div class='p-4 text-red-600 text-xs'>Session expired.</div>"

    headers = get_auth_headers(user)
    message, kind = "", "ok"
    async with httpx.AsyncClient(timeout=10.0) as client:
        res = await client.delete(f"{BACKEND_BASE_URL}/cattle/{animal_id}", headers=headers)
        if res.status_code == 403:
            err = extract_backend_error(res, f"remove Cattle '{animal_id}'")
            message = format_403_security_message(user, err, request.url.path)
            await log_security_violation_best_effort(user, request.url.path, message)
            kind = "error"
        elif res.status_code == 404:
            message, kind = f"Cattle '{animal_id}' no longer exists.", "error"
        elif res.status_code != 200:
            message, kind = extract_backend_error(res, "Failed to remove cattle"), "error"
        else:
            message = describe_removal(res, "Cattle", animal_id)
        view = await herd_view_from_form(request)
        pagination = await fetch_paginated_cattle(client, headers, page=1, size=100, **view)

    return templates.TemplateResponse("partials/herd_table_content.html", {
        "request": request,
        "user": user,
        "oob_update": True,
        **pagination
    }, headers=flash_header(message, kind))


def backend_note(res: httpx.Response) -> str:
    """Optional explanation the backend adds to a successful answer (for example a reader that is in two roles)."""
    try:
        data = res.json()
    except Exception:
        return ""
    note = data.get("note") if isinstance(data, dict) else None
    return note if isinstance(note, str) else ""


def describe_removal(res: httpx.Response, noun: str, ident: str) -> str:
    """Message for a delete that the backend answered with DELETED, ARCHIVED or RETIRED."""
    try:
        data = res.json()
    except Exception:
        data = {}
    outcome = data.get("status") if isinstance(data, dict) else None
    if outcome == "ARCHIVED":
        return f"{noun} '{ident}' has recorded history, so it was archived instead of deleted. Its history is kept."
    if outcome == "RETIRED":
        return f"{noun} '{ident}' has recorded passages, so it was retired instead of deleted. Its history is kept."
    return f"{noun} '{ident}' was deleted."


@app.get("/herd/{animal_id}", response_class=HTMLResponse)
async def cattle_detail_page(request: Request, animal_id: str, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.get(f"{BACKEND_BASE_URL}/cattle/{animal_id}", headers=headers)
        if res.status_code == 403:
            raise HTTPException(status_code=403, detail=extract_backend_error(res, f"view Cattle '{animal_id}'"))
        if res.status_code != 200:
            raise HTTPException(status_code=404, detail="Animal not found")
        detail = res.json()

        try:
            barns_res = await client.get(f"{BACKEND_BASE_URL}/barns", headers=headers)
            barns = filter_barns_by_scope(barns_res.json() if barns_res.status_code == 200 else [], user)
        except Exception:
            barns = []

    return templates.TemplateResponse("cattle_detail.html", {
        "request": request,
        "user": user,
        "animal": detail["profile"],
        "tags": detail["tags"],
        "vaccinations": detail["vaccinations"],
        "movements": detail["movements"],
        "transfers": detail.get("transfers", []),
        "barns": barns
    })


@app.post("/herd/{animal_id}/assign-tag")
async def cattle_assign_tag(
    request: Request,
    animal_id: str,
    tag_epc: str = Form(...),
    credential_identifier: str = Form("EPC-26"),
    user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}/tags",
            json={"tagEpc": tag_epc, "credentialIdentifier": credential_identifier},
            headers=get_auth_headers(user)
        )
    if res.status_code == 403:
        err = extract_backend_error(res, f"mutate RFID tag for Cattle '{animal_id}'")
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(url=f"/herd/{animal_id}?error={quote(msg)}", status_code=status.HTTP_303_SEE_OTHER)

    return RedirectResponse(url=f"/herd/{animal_id}", status_code=status.HTTP_303_SEE_OTHER)


@app.post("/herd/{animal_id}/update")
async def cattle_update_action(
    request: Request,
    animal_id: str,
    breed: str = Form(""),
    weight_kg: str = Form(""),
    user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    payload = {}
    if breed.strip():
        payload["breed"] = breed.strip()
    if weight_kg.strip():
        try:
            payload["weightKg"] = float(weight_kg)
        except ValueError:
            return redirect_with(f"/herd/{animal_id}", problem="Weight must be a number.")

    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.put(f"{BACKEND_BASE_URL}/cattle/{animal_id}", json=payload, headers=get_auth_headers(user))
    if res.status_code == 403:
        err = extract_backend_error(res, f"edit Cattle '{animal_id}'")
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(url=f"/herd/{animal_id}?error={quote(msg)}", status_code=status.HTTP_303_SEE_OTHER)
    if res.status_code != 200:
        return redirect_with(f"/herd/{animal_id}", problem=extract_backend_error(res, "Could not update the animal."))
    return redirect_with(f"/herd/{animal_id}", notice="Animal details updated.")


@app.post("/herd/{animal_id}/delete")
async def cattle_delete_action(
    request: Request,
    animal_id: str,
    reason: str = Form(""),
    user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.delete(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}",
            params={"reason": reason.strip()} if reason.strip() else None,
            headers=get_auth_headers(user)
        )
    if res.status_code == 403:
        err = extract_backend_error(res, f"remove Cattle '{animal_id}'")
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(url=f"/herd/{animal_id}?error={quote(msg)}", status_code=status.HTTP_303_SEE_OTHER)
    if res.status_code == 404:
        return redirect_with("/herd", problem=f"Cattle '{animal_id}' no longer exists.")
    if res.status_code != 200:
        return redirect_with(f"/herd/{animal_id}", problem=extract_backend_error(res, "Could not remove the animal."))
    return redirect_with("/herd", notice=describe_removal(res, "Cattle", animal_id))


@app.post("/herd/{animal_id}/vaccinate")
async def cattle_record_vaccination(
    request: Request,
    animal_id: str,
    vaccine_name: str = Form(...),
    batch_number: str = Form(...),
    notes: str = Form(""),
    user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}/vaccinations",
            json={"vaccineName": vaccine_name, "batchNumber": batch_number, "notes": notes},
            headers=get_auth_headers(user)
        )
    if res.status_code == 403:
        err = extract_backend_error(res, f"record vaccination for Cattle '{animal_id}'")
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(url=f"/herd/{animal_id}?error={quote(msg)}", status_code=status.HTTP_303_SEE_OTHER)

    return RedirectResponse(url=f"/herd/{animal_id}", status_code=status.HTTP_303_SEE_OTHER)


@app.post("/herd/{animal_id}/health")
async def cattle_record_health(
    request: Request,
    animal_id: str,
    record_type: str = Form(...),
    health_status: str = Form("HEALTHY"),
    title: str = Form(...),
    medication: str = Form(""),
    notes: str = Form(""),
    user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}/health",
            json={
                "recordType": record_type,
                "healthStatus": health_status,
                "title": title,
                "medication": medication,
                "notes": notes
            },
            headers=get_auth_headers(user)
        )
    if res.status_code == 403:
        err = extract_backend_error(res, f"mutate health status for Cattle '{animal_id}'")
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(url=f"/herd/{animal_id}?error={quote(msg)}", status_code=status.HTTP_303_SEE_OTHER)

    return RedirectResponse(url=f"/herd/{animal_id}", status_code=status.HTTP_303_SEE_OTHER)


@app.post("/herd/{animal_id}/transfer")
async def cattle_transfer_action(
    request: Request,
    animal_id: str,
    to_barn_id: str = Form(...),
    reason: str = Form(""),
    user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}/transfer",
            json={"toBarnId": to_barn_id, "reason": reason},
            headers=get_auth_headers(user)
        )
    if res.status_code == 403:
        err = extract_backend_error(res, f"transfer Cattle '{animal_id}' to Barn '{to_barn_id}'")
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(url=f"/herd/{animal_id}?error={quote(msg)}", status_code=status.HTTP_303_SEE_OTHER)

    return RedirectResponse(url=f"/herd/{animal_id}", status_code=status.HTTP_303_SEE_OTHER)


@app.get("/herd/{animal_id}/dossier")
async def cattle_download_dossier(animal_id: str, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.get(f"{BACKEND_BASE_URL}/cattle/{animal_id}/dossier", headers=get_auth_headers(user))
        if res.status_code == 403:
            raise HTTPException(status_code=403, detail=extract_backend_error(res, f"export dossier for Cattle '{animal_id}'"))
        return Response(
            content=res.content,
            media_type="application/json",
            headers={"Content-Disposition": f'attachment; filename="dossier_{animal_id.replace(" ", "_")}.json"'}
        )


# ================= GATE READER MANAGEMENT =================

async def fetch_readers(client: httpx.AsyncClient, headers: dict, user: dict, q: str = "", sort: str = "", dir: str = "",
                        filters: dict | None = None, page: int = 1) -> dict:
    """One page of readers in the order the server applied (newest first by default), with the order for the table headers
    and the numbers for the pager. A page past the end (readers were removed meanwhile) becomes the last page."""
    readers, sort_key, sort_dir, total = [], sort, dir, 0
    filters = {n: (filters or {}).get(n, "") for n in READER_FILTERS}
    page = max(1, page)
    for _ in range(3):
        res = await client.get(f"{BACKEND_BASE_URL}/readers",
                               params={"q": q, "sort": sort, "dir": dir, "page": page, "size": PAGE_SIZE, **backend_filters(filters)},
                               headers=headers)
        if res.status_code == 401:
            raise HTTPException(status_code=401, detail="Session expired")
        if res.status_code == 403:
            raise HTTPException(status_code=403, detail=extract_backend_error(res, "access Gate Readers"))
        if res.status_code != 200:
            break
        rows = res.json()
        readers = filter_readers_by_scope(rows, user)
        total = whole_number(res.headers.get("X-Total-Count"), len(readers))
        sort_key = res.headers.get("X-Sort-Key", sort_key)
        sort_dir = res.headers.get("X-Sort-Dir", sort_dir)
        if isinstance(rows, list) and len(readers) != len(rows):
            # this role sees fewer readers here than the backend counted (the check above is the portal's own and
            # stricter), so the backend's total would promise rows that are not shown: count what is shown
            return {"readers": readers, "sort": sort_key, "dir": sort_dir, "q": q, "filters": filters,
                    "filters_active": any(filters.values()), **page_numbers(len(readers), 1)}
        last = page_numbers(total, page)["total_pages"]
        if page <= last:
            break
        page = last
    return {"readers": readers, "sort": sort_key, "dir": sort_dir, "q": q,
            "filters": filters, "filters_active": any(filters.values()), **page_numbers(total, page)}


@app.get("/readers", response_class=HTMLResponse)
async def readers_page(request: Request, q: str = "", sort: str = "", dir: str = "", page: int = 1, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    filters = pick_filters(READER_FILTERS, request.query_params)
    async with httpx.AsyncClient(timeout=6.0) as client:
        try:
            listing = await fetch_readers(client, headers, user, q, sort, dir, filters, page)
        except HTTPException as exc:
            if exc.status_code == 401:
                return RedirectResponse(url="/login")
            raise
        except Exception:
            listing = {"readers": [], "sort": sort, "dir": dir, "q": q, "filters": filters, "filters_active": any(filters.values()),
                       **page_numbers(0, 1)}

        try:
            barns_res = await client.get(f"{BACKEND_BASE_URL}/barns", headers=headers)
            barns = filter_barns_by_scope(barns_res.json() if barns_res.status_code == 200 else [], user)
        except Exception:
            barns = []
        farms = await fetch_scoped_farms(client, headers, user)

    return templates.TemplateResponse("readers.html", {
        "request": request,
        "user": user,
        "barns": barns,
        "farms": farms,
        **listing
    })


@app.get("/htmx/readers-list", response_class=HTMLResponse)
async def htmx_readers_list(request: Request, q: str = "", sort: str = "", dir: str = "", page: int = 1, user: dict = Depends(get_current_user)):
    headers = get_auth_headers(user) if user else {}
    filters = pick_filters(READER_FILTERS, request.query_params)
    async with httpx.AsyncClient(timeout=6.0) as client:
        try:
            listing = await fetch_readers(client, headers, user, q, sort, dir, filters, page)
        except Exception:
            listing = {"readers": [], "sort": sort, "dir": dir, "q": q, "filters": filters, "filters_active": any(filters.values()),
                       **page_numbers(0, 1)}
    return templates.TemplateResponse("partials/readers_table.html", {"request": request, "user": user, "oob_update": True, **listing})


@app.post("/htmx/readers/create", response_class=HTMLResponse)
async def htmx_readers_create(
    request: Request,
    reader_name: str = Form(...),
    label: str = Form(...),
    barn_id: str = Form(...),
    direction_mode: str = Form("BIDIRECTIONAL"),
    outer_reader_name: str = Form(""),
    firmware_version: str = Form("1.58.1998"),
    user: dict = Depends(get_current_user)
):
    if not user:
        return ""

    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/readers",
            json={
                "readerName": reader_name,
                "outerReaderName": outer_reader_name,
                "label": label,
                "barnId": barn_id,
                "directionMode": direction_mode,
                "firmwareVersion": firmware_version
            },
            headers=get_auth_headers(user)
        )
        message, kind = f"Reader '{reader_name.strip()}' registered ({direction_mode}).", "ok"
        if res.status_code == 200:
            message = f"{message} {backend_note(res)}".strip()
        if res.status_code == 403:
            err = extract_backend_error(res, f"provision reader in Barn '{barn_id}'")
            message = format_403_security_message(user, err, request.url.path)
            await log_security_violation_best_effort(user, request.url.path, message)
            kind = "error"
        elif res.status_code != 200:
            message, kind = extract_backend_error(res, "Failed to register the reader"), "error"

        # newest first, so the reader just added is the first row (the filters on screen still apply)
        try:
            form = await request.form()
        except Exception:
            form = {}
        listing = await fetch_readers(client, get_auth_headers(user), user, filters=pick_filters(READER_FILTERS, form))
    return templates.TemplateResponse("partials/readers_table.html", {"request": request, "user": user, "oob_update": True, **listing},
                                      headers=flash_header(message, kind))


@app.post("/htmx/readers/{gate_id}/delete", response_class=HTMLResponse)
async def htmx_readers_delete(request: Request, gate_id: str, q: str = Form(""), sort: str = Form(""), dir: str = Form(""),
                              page: int = Form(1), user: dict = Depends(get_current_user)):
    """Delete or retire from the readers list. The backend decides which one and says so."""
    if not user:
        return ""

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.delete(f"{BACKEND_BASE_URL}/readers/{gate_id}", headers=headers)
        message, kind = "", "ok"
        if res.status_code == 403:
            err = extract_backend_error(res, f"remove Gate Reader '{gate_id}'")
            message = format_403_security_message(user, err, request.url.path)
            await log_security_violation_best_effort(user, request.url.path, message)
            kind = "error"
        elif res.status_code == 404:
            message, kind = f"Gate reader '{gate_id}' no longer exists.", "error"
        elif res.status_code != 200:
            message, kind = extract_backend_error(res, "Failed to remove the reader"), "error"
        else:
            message = describe_removal(res, "Reader", gate_id)

        try:
            form = await request.form()
        except Exception:
            form = {}
        # the same page the person was on; if that was the last row of the last page, the last page that is left
        listing = await fetch_readers(client, headers, user, q, sort, dir, pick_filters(READER_FILTERS, form), page)
    return templates.TemplateResponse("partials/readers_table.html", {"request": request, "user": user, "oob_update": True, **listing},
                                      headers=flash_header(message, kind))


@app.get("/readers/{gate_id}", response_class=HTMLResponse)
async def reader_detail_page(request: Request, gate_id: str, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.get(f"{BACKEND_BASE_URL}/readers/{gate_id}", headers=headers)
        if res.status_code == 403:
            raise HTTPException(status_code=403, detail=extract_backend_error(res, f"access Gate Reader '{gate_id}'"))
        if res.status_code != 200:
            raise HTTPException(status_code=404, detail="Gate reader not found")
        detail = res.json()
        if not filter_readers_by_scope([detail["reader"]], user):
            raise HTTPException(status_code=403, detail=f"access Gate Reader '{gate_id}'")

        barns_res = await client.get(f"{BACKEND_BASE_URL}/barns", headers=headers)
        barns = filter_barns_by_scope(barns_res.json() if barns_res.status_code == 200 else [], user)

    return templates.TemplateResponse("reader_detail.html", {
        "request": request,
        "user": user,
        "reader": detail["reader"],
        "transactions": detail["transactions"],
        "barns": barns
    })


@app.post("/readers/{gate_id}/update")
async def reader_update_action(
    request: Request,
    gate_id: str,
    barn_id: str = Form(...),
    label: str = Form(...),
    direction_mode: str = Form(...),
    reader_name: str = Form(""),
    firmware_version: str = Form("1.58.1998"),
    user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    # FastAPI turns an empty form value into "missing", but here the difference matters: a form that sends an EMPTY
    # outer reader removes it, a form without the field leaves it alone.
    form = await request.form()
    outer_reader_name = form.get("outer_reader_name")

    # the repeat-read time is typed in seconds; the backend stores milliseconds
    try:
        repeat_debounce_ms = seconds_to_ms(form.get("repeat_debounce_s"), "The repeat-read time")
    except ValueError as exc:
        return redirect_with(f"/readers/{gate_id}", problem=str(exc))

    payload = {
        "barnId": barn_id,
        "label": label,
        "directionMode": direction_mode,
        "firmwareVersion": firmware_version
    }
    if repeat_debounce_ms is not None:
        payload["repeatDebounceMs"] = repeat_debounce_ms
    if reader_name.strip():
        payload["readerName"] = reader_name.strip()
    if outer_reader_name is not None:
        # an empty value removes the outer reader (needed to switch a paired gate to IN_ONLY / OUT_ONLY)
        payload["outerReaderName"] = outer_reader_name.strip()

    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.put(f"{BACKEND_BASE_URL}/readers/{gate_id}", json=payload, headers=get_auth_headers(user))
    if res.status_code == 403:
        err = extract_backend_error(res, f"mutate Gate Reader '{gate_id}' in Barn '{barn_id}'")
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(url=f"/readers/{gate_id}?error={quote(msg)}", status_code=status.HTTP_303_SEE_OTHER)
    if res.status_code != 200:
        return redirect_with(f"/readers/{gate_id}", problem=extract_backend_error(res, "Could not save the reader settings."))

    return redirect_with(f"/readers/{gate_id}", notice=f"Reader settings saved. {backend_note(res)}".strip())


@app.post("/readers/{gate_id}/delete")
async def reader_delete_action(request: Request, gate_id: str, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.delete(f"{BACKEND_BASE_URL}/readers/{gate_id}", headers=get_auth_headers(user))
    if res.status_code == 403:
        err = extract_backend_error(res, f"remove Gate Reader '{gate_id}'")
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(url=f"/readers/{gate_id}?error={quote(msg)}", status_code=status.HTTP_303_SEE_OTHER)
    if res.status_code == 404:
        return redirect_with("/readers", problem=f"Gate reader '{gate_id}' no longer exists.")
    if res.status_code != 200:
        return redirect_with(f"/readers/{gate_id}", problem=extract_backend_error(res, "Could not remove the reader."))
    return redirect_with("/readers", notice=describe_removal(res, "Reader", gate_id))


# ================= ABNORMALITY DETECTION & ALERTING =================

NO_ALERT_STATS = {"openCount": 0, "acknowledgedCount": 0, "missingCount": 0, "unexpectedCount": 0, "wrongBarnCount": 0}


async def fetch_alerts_data(client: httpx.AsyncClient, headers: dict, state: str = "OPEN", alert_type: str = "ALL", q: str = "",
                            page: int = 1, size: int = PAGE_SIZE) -> dict:
    """One page of alerts, the counters for the top of the page, and the numbers for the pager.
    A page past the end (alerts were resolved meanwhile) becomes the last page."""
    page = max(1, page)
    for _ in range(3):
        try:
            res = await client.get(
                f"{BACKEND_BASE_URL}/alerts",
                params={"state": state, "type": alert_type, "q": q, "page": page, "size": size},
                headers=headers
            )
        except Exception as e:
            print(f"[ERROR] Failed to fetch alerts: {e}")
            break
        if res.status_code != 200:
            break
        data = res.json()
        alerts = data.get("alerts", [])
        total = whole_number(data.get("total"), len(alerts))
        last = page_numbers(total, page, size)["total_pages"]
        if page > last:
            page = last
            continue
        return {"alerts": alerts, "stats": data.get("stats", dict(NO_ALERT_STATS)), **page_numbers(total, page, size)}

    return {"alerts": [], "stats": dict(NO_ALERT_STATS), **page_numbers(0, 1, size)}


def alert_context(request: Request, user: dict, data: dict, state: str, type: str, q: str, **extra) -> dict:
    """What alert_table_content.html needs: the rows, the counters, the filters on screen and the pager."""
    return {
        "request": request,
        "user": user,
        "alerts": data["alerts"],
        "stats": data["stats"],
        "current_state": state,
        "current_type": type,
        "q": q,
        **{k: data[k] for k in PAGING_KEYS},
        **extra,
    }


async def alert_view(request: Request) -> dict:
    """The state, type, search text and page the list had on screen when a button was pressed: sent with the request
    (the buttons include the filter fields), so acknowledging or resolving keeps the view instead of jumping back to page 1."""
    sent = dict(request.query_params)
    try:
        form = await request.form()
        sent.update({k: str(v) for k, v in form.items()})
    except Exception:
        pass
    return {
        "state": (sent.get("state") or "OPEN").strip() or "OPEN",
        "type": (sent.get("type") or "ALL").strip() or "ALL",
        "q": (sent.get("q") or "").strip(),
        "page": whole_number(sent.get("page")),
    }


@app.get("/alerts", response_class=HTMLResponse)
async def alerts_page(request: Request, state: str = "OPEN", type: str = "ALL", q: str = "", page: int = 1, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=8.0) as client:
        alert_data = await fetch_alerts_data(client, headers, state=state, alert_type=type, q=q, page=page)
        try:
            barns_res = await client.get(f"{BACKEND_BASE_URL}/barns", headers=headers)
            barns = filter_barns_by_scope(barns_res.json() if barns_res.status_code == 200 else [], user)
        except Exception:
            barns = []

    return templates.TemplateResponse("alerts.html", alert_context(request, user, alert_data, state, type, q, barns=barns))


@app.get("/htmx/alerts-list", response_class=HTMLResponse)
async def htmx_alerts_list(request: Request, state: str = "OPEN", type: str = "ALL", q: str = "", page: int = 1, user: dict = Depends(get_current_user)):
    headers = get_auth_headers(user) if user else {}
    async with httpx.AsyncClient(timeout=8.0) as client:
        alert_data = await fetch_alerts_data(client, headers, state=state, alert_type=type, q=q, page=page)

    return templates.TemplateResponse("partials/alert_table_content.html",
                                      alert_context(request, user, alert_data, state, type, q, oob_update=True))


@app.post("/htmx/alerts/reconcile", response_class=HTMLResponse)
async def htmx_alerts_reconcile(
    request: Request,
    barn_id: str = Form(...),
    threshold_minutes: int = Form(30),
    user: dict = Depends(get_current_user)
):
    if not user:
        return "<div class='p-4 text-red-600 text-xs'>Session expired.</div>"

    headers = get_auth_headers(user)
    banner_msg = ""
    async with httpx.AsyncClient(timeout=10.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/alerts/reconcile-missing",
            json={"barnId": barn_id, "thresholdMinutes": threshold_minutes},
            headers=headers
        )
        if res.status_code == 403:
            err = extract_backend_error(res, f"reconcile Barn '{barn_id}'")
            msg = format_403_security_message(user, err, request.url.path)
            await log_security_violation_best_effort(user, request.url.path, msg)
            return HTMLResponse(content=render_403_alert_html(msg), status_code=403)
        if res.status_code == 200:
            body = res.json()
            flagged = body.get("flaggedCount", 0)
            banner_msg = f"Roll-call completed for {barn_id}: {flagged} missing cattle flagged."
        # the new alerts are open, newest first: show the open list from its first page
        alert_data = await fetch_alerts_data(client, headers, state="OPEN", alert_type="ALL", q="")

    return templates.TemplateResponse("partials/alert_table_content.html",
                                      alert_context(request, user, alert_data, "OPEN", "ALL", "", oob_update=True, banner_msg=banner_msg))


@app.post("/htmx/alerts/{alert_id}/acknowledge", response_class=HTMLResponse)
async def htmx_alert_acknowledge(request: Request, alert_id: str, user: dict = Depends(get_current_user)):
    headers = get_auth_headers(user) if user else {}
    view = await alert_view(request)
    async with httpx.AsyncClient(timeout=8.0) as client:
        res = await client.post(f"{BACKEND_BASE_URL}/alerts/{alert_id}/acknowledge", headers=headers)
        if res.status_code == 403:
            err = extract_backend_error(res, f"acknowledge Alert '{alert_id}'")
            msg = format_403_security_message(user or {}, err, request.url.path)
            await log_security_violation_best_effort(user or {}, request.url.path, msg)
            return HTMLResponse(content=render_403_alert_html(msg), status_code=403)
        alert_data = await fetch_alerts_data(client, headers, state=view["state"], alert_type=view["type"], q=view["q"], page=view["page"])

    return templates.TemplateResponse("partials/alert_table_content.html",
                                      alert_context(request, user, alert_data, view["state"], view["type"], view["q"], oob_update=True))


@app.post("/htmx/alerts/{alert_id}/resolve", response_class=HTMLResponse)
async def htmx_alert_resolve(
    request: Request,
    alert_id: str,
    note: str = Form("Verified and cleared by operator"),
    user: dict = Depends(get_current_user)
):
    headers = get_auth_headers(user) if user else {}
    view = await alert_view(request)
    async with httpx.AsyncClient(timeout=8.0) as client:
        res = await client.post(f"{BACKEND_BASE_URL}/alerts/{alert_id}/resolve", json={"note": note}, headers=headers)
        if res.status_code == 403:
            err = extract_backend_error(res, f"resolve Alert '{alert_id}'")
            msg = format_403_security_message(user or {}, err, request.url.path)
            await log_security_violation_best_effort(user or {}, request.url.path, msg)
            return HTMLResponse(content=render_403_alert_html(msg), status_code=403)
        alert_data = await fetch_alerts_data(client, headers, state=view["state"], alert_type=view["type"], q=view["q"], page=view["page"])

    return templates.TemplateResponse("partials/alert_table_content.html",
                                      alert_context(request, user, alert_data, view["state"], view["type"], view["q"], oob_update=True))


@app.get("/htmx/alerts/badge", response_class=HTMLResponse)
async def htmx_alerts_badge(user: dict = Depends(get_current_user)):
    if not user:
        return ""
    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=3.0) as client:
        data = await fetch_alerts_data(client, headers, state="OPEN", alert_type="ALL", q="", size=1)   # only the counter is used
    count = data["stats"].get("openCount", 0)
    if count > 0:
        return f'<span class="px-2 py-0.5 text-[10px] font-bold rounded-full bg-rose-500 text-white">{count}</span>'
    return ""


# ================= SURVEILLANCE REPORTING & EXTERNAL API TRANSMISSION =================

@app.get("/surveillance", response_class=HTMLResponse)
async def surveillance_page(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")
    if user.get("role") not in ["SYSTEM_ADMIN", "FARM_MANAGER", "EXECUTIVE"]:
        raise HTTPException(status_code=403, detail="Access denied: Insufficient privileges for disease surveillance.")

    headers = get_auth_headers(user)
    farms = []
    async with httpx.AsyncClient(timeout=15.0) as client:
        try:
            res = await client.get(f"{BACKEND_BASE_URL}/surveillance/overview", headers=headers)
            if res.status_code == 403:
                raise HTTPException(status_code=403, detail=extract_backend_error(res, "access Disease Surveillance"))
            data = res.json() if res.status_code == 200 else {}
        except HTTPException:
            raise
        except Exception:
            data = {}
        try:
            dist = await client.get(f"{BACKEND_BASE_URL}/facilities/distribution", headers=headers)
            if dist.status_code == 200:
                farms = filter_farms_by_scope(dist.json(), user)
        except Exception:
            farms = []

    return templates.TemplateResponse("surveillance.html", {
        "request": request,
        "user": user,
        "farms": farms,
        "metrics": data.get("metrics", {
            "totalHerd": 0, "vaccinatedHerd": 0, "vaccinationCoveragePct": 0,
            "underTreatment": 0, "quarantined": 0, "totalTransfers": 0
        }),
        "nadcs": data.get("nadcs", {"mode": "SIMULATION", "endpoint": "", "apiKeyConfigured": False}),
        "data_checks": data.get("dataChecks", []),
        "vaccinated_definition": data.get("vaccinatedDefinition", ""),
        "transmissions": data.get("transmissions", [])
    })


@app.get("/surveillance/export")
async def surveillance_export_proxy(
    report_type: str = "OUTBREAK_SURVEILLANCE",
    farm_id: str = "ALL",
    format: str = "CSV",
    user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")
    if user.get("role") not in ["SYSTEM_ADMIN", "FARM_MANAGER", "EXECUTIVE"]:
        raise HTTPException(status_code=403, detail="Access denied: Insufficient privileges for surveillance export.")

    async with httpx.AsyncClient(timeout=15.0) as client:
        res = await client.get(
            f"{BACKEND_BASE_URL}/surveillance/export",
            params={"reportType": report_type, "farmId": farm_id, "format": format},
            headers=get_auth_headers(user)
        )
        if res.status_code == 403:
            raise HTTPException(status_code=403, detail=extract_backend_error(res, f"export surveillance data for '{farm_id}'"))
        content_disp = res.headers.get("Content-Disposition", f'attachment; filename="report.{format.lower()}"')
        media_type = res.headers.get("Content-Type", "application/octet-stream")
        return Response(content=res.content, media_type=media_type, headers={"Content-Disposition": content_disp})


@app.post("/htmx/surveillance/transmit", response_class=HTMLResponse)
async def htmx_surveillance_transmit(
    request: Request,
    target_system: str = Form("National Animal Disease Control System (NADCS)"),
    report_type: str = Form("OUTBREAK_SURVEILLANCE"),
    farm_scope: str = Form("ALL"),
    payload_format: str = Form("JSON"),
    user: dict = Depends(get_current_user)
):
    """The destination and key are server settings; this form can only choose the report, the scope and the format."""
    if not user:
        return "<div class='p-4 text-red-600 text-xs'>Session expired.</div>"

    headers = get_auth_headers(user)
    banner_msg, banner_kind = "", "ok"
    # a sleeping free-tier test server needs about a minute to wake (the backend waits for it), so wait longer than usual here
    async with httpx.AsyncClient(timeout=150.0) as client:
        tx_res = await client.post(
            f"{BACKEND_BASE_URL}/surveillance/transmit",
            json={"targetSystem": target_system, "reportType": report_type, "farmScope": farm_scope, "payloadFormat": payload_format},
            headers=headers
        )
        if tx_res.status_code == 403:
            err = extract_backend_error(tx_res, f"transmit surveillance data for Farm '{farm_scope}'")
            msg = format_403_security_message(user, err, request.url.path)
            await log_security_violation_best_effort(user, request.url.path, msg)
            return HTMLResponse(content=render_403_alert_html(msg), status_code=403)
        if tx_res.status_code == 200:
            body = tx_res.json()
            banner_msg = body.get("responseSummary", "Transmission recorded.")
            banner_kind = {"DELIVERED": "ok", "SIMULATED": "warn"}.get(body.get("status"), "error")
            if banner_kind == "ok" and "via test server" in banner_msg:
                banner_kind = "info"          # accepted by the test server: not a delivery to the agency
        else:
            banner_msg, banner_kind = extract_backend_error(tx_res, "The transmission failed."), "error"

        ov_res = await client.get(f"{BACKEND_BASE_URL}/surveillance/overview", headers=headers)
        transmissions = ov_res.json().get("transmissions", []) if ov_res.status_code == 200 else []

    return templates.TemplateResponse("partials/transmission_rows.html", {
        "request": request,
        "transmissions": transmissions,
        "banner_msg": banner_msg,
        "banner_kind": banner_kind
    })


TX_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")


def human_bytes(n) -> str:
    if n is None:
        return ""
    n = int(n)
    if n < 1024:
        return f"{n} B"
    if n < 1024 * 1024:
        return f"{n / 1024:.1f} KB"
    return f"{n / (1024 * 1024):.1f} MB"


def build_transmission_view(d: dict) -> dict:
    """What the detail window shows, worked out from the backend's answer (pure, so it can be tested without a browser)."""
    raw = d.get("bodyExcerpt") or ""
    pretty = None
    # a pretty-printed copy only when the text shown is the whole body: a cut-off JSON document does not parse
    if d.get("payloadFormat") == "JSON" and raw and not d.get("excerptTruncated") and not d.get("storedTruncated"):
        try:
            pretty = json.dumps(json.loads(raw), indent=2, ensure_ascii=False)
        except ValueError:
            pretty = None
    summary = d.get("responseSummary") or ""
    host = re.search(r"via test server ([^\s:]+)", summary)
    status = d.get("deliveryStatus") or "SIMULATED"
    return {
        "raw": raw,
        "pretty": pretty,
        "has_body": bool(d.get("bodyVisible")) and bool(raw or d.get("storedChars")),
        "size": human_bytes(d.get("payloadBytes")),
        "shown_chars": len(raw),
        "stored_chars": d.get("storedChars") or 0,
        "simulated": status == "SIMULATED",
        "via_test": "via test server" in summary,
        "test_host": host.group(1) if host else "",
        "sent_note": ("Nothing was sent: this is the body that would have been sent." if status == "SIMULATED"
                      else "UNREACHABLE: the request was sent but no answer came back." if status == "UNREACHABLE" else ""),
        "can_link_test_server": str(d.get("testServerUi") or "").startswith(("http://", "https://")) and "via test server" in summary,
    }


@app.get("/htmx/surveillance/transmissions/{tx_id}", response_class=HTMLResponse)
async def htmx_transmission_detail(request: Request, tx_id: str, user: dict = Depends(get_current_user)):
    """The window opened by clicking a row of the transmission log: what was sent, and what came back."""
    if not user:
        return HTMLResponse("<div class='p-6 text-xs text-rose-700'>Session expired. Sign in again.</div>")
    if user.get("role") not in ["SYSTEM_ADMIN", "FARM_MANAGER", "EXECUTIVE"]:
        return HTMLResponse("<div class='p-6 text-xs text-rose-700'>Your role cannot view surveillance transmissions.</div>", status_code=403)
    if not TX_ID_RE.match(tx_id):
        return HTMLResponse("<div class='p-6 text-xs text-rose-700'>That is not a transmission id.</div>")
    def failed(reason: str):
        # 200 on purpose: HTMX does not swap an error status into the window, and the reason belongs there
        return templates.TemplateResponse("partials/transmission_detail.html", {
            "request": request, "d": None, "view": None, "tx_id": tx_id, "error": reason})

    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            res = await client.get(f"{BACKEND_BASE_URL}/surveillance/transmissions/{tx_id}", params={"maxChars": 120000}, headers=get_auth_headers(user))
    except httpx.HTTPError as exc:
        return failed(f"The backend could not be reached: {exc.__class__.__name__}.")
    if res.status_code != 200:
        return failed(extract_backend_error(res, "The transmission could not be loaded."))
    try:
        d = res.json()
    except ValueError:
        return failed("The backend answered with something that is not a transmission.")
    return templates.TemplateResponse("partials/transmission_detail.html", {
        "request": request, "d": d, "view": build_transmission_view(d), "tx_id": tx_id, "error": ""})


@app.get("/surveillance/transmissions/{tx_id}/body")
async def surveillance_transmission_body(tx_id: str, user: dict = Depends(get_current_user)):
    """The whole stored body of one transmission as a download."""
    if not user:
        return RedirectResponse(url="/login")
    if user.get("role") not in ["SYSTEM_ADMIN", "FARM_MANAGER", "EXECUTIVE"]:
        raise HTTPException(status_code=403, detail="Access denied: Insufficient privileges for surveillance transmissions.")
    if not TX_ID_RE.match(tx_id):
        raise HTTPException(status_code=404, detail="That is not a transmission id.")
    async with httpx.AsyncClient(timeout=60.0) as client:
        res = await client.get(f"{BACKEND_BASE_URL}/surveillance/transmissions/{tx_id}/body", headers=get_auth_headers(user))
    if res.status_code != 200:
        raise HTTPException(status_code=res.status_code if res.status_code in (403, 404) else 502,
                            detail=extract_backend_error(res, "The body could not be loaded."))
    headers = {"Content-Disposition": res.headers.get("Content-Disposition", f'attachment; filename="{tx_id}.txt"'),
               "X-Content-Type-Options": "nosniff"}
    for h in ("X-Payload-Sha256", "X-Payload-Truncated"):
        if h in res.headers:
            headers[h] = res.headers[h]
    return Response(content=res.content, media_type=res.headers.get("Content-Type", "application/octet-stream"), headers=headers)


@app.post("/htmx/barns/{barn_id}/verify-return", response_class=HTMLResponse)
async def htmx_verify_barn_return(request: Request, barn_id: str, user: dict = Depends(get_current_user)):
    headers = get_auth_headers(user) if user else {}
    verify_banner = ""
    async with httpx.AsyncClient(timeout=6.0) as client:
        v_res = await client.post(f"{BACKEND_BASE_URL}/barns/{barn_id}/verify-return", headers=headers)
        if v_res.status_code == 403:
            err = extract_backend_error(v_res, f"mutate Barn '{barn_id}'")
            msg = format_403_security_message(user or {}, err, request.url.path)
            await log_security_violation_best_effort(user or {}, request.url.path, msg)
            return HTMLResponse(content=render_403_alert_html(msg), status_code=403)
        res = await client.get(f"{BACKEND_BASE_URL}/facilities/distribution", headers=headers)
        farms = filter_farms_by_scope(res.json() if res.status_code == 200 else [], user)
        if v_res.status_code == 200:
            body = v_res.json()
            # people know the barn by its code (the key in the address is not shown)
            barn_name = next((b.get("code") for f in farms for b in f.get("barns", []) if b.get("barnId") == barn_id and b.get("code")), barn_id)
            verify_banner = (
                f"Return check for {barn_name}: today {body.get('exitedCount', 0)} passed OUT and "
                f"{body.get('returnedCount', 0)} passed IN; {body.get('deficit', 0)} are at pasture now; "
                f"{body.get('newlyFlaggedAlerts', 0)} missing-cattle alert(s) raised."
            )

    return templates.TemplateResponse("partials/headcount_cards.html", {
        "request": request,
        "farms": farms,
        "verify_banner": verify_banner
    })


# ================= USER & ROLE ADMINISTRATION =================

@app.get("/users", response_class=HTMLResponse)
async def users_page(request: Request, error: str = "", success: str = "", user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")
    if user.get("role") not in ("SYSTEM_ADMIN", "FARM_MANAGER"):
        raise HTTPException(status_code=403, detail="Access denied: Insufficient privileges for user & role administration.")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=6.0) as client:
        users_res = await client.get(f"{BACKEND_BASE_URL}/users", headers=headers)
        if users_res.status_code == 403:
            raise HTTPException(status_code=403, detail=extract_backend_error(users_res, "access User & Role Administration"))
        users_list = users_res.json() if users_res.status_code == 200 else []

        dist_res = await client.get(f"{BACKEND_BASE_URL}/facilities/distribution", headers=headers)
        farms = filter_farms_by_scope(dist_res.json() if dist_res.status_code == 200 else [], user)

    return templates.TemplateResponse("users.html", {
        "request": request,
        "user": user,
        "users_list": users_list,
        "farms": farms,
        "error": error,
        "success": success
    })


@app.post("/users/create")
async def create_user_submit(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)
    if user.get("role") not in ("SYSTEM_ADMIN", "FARM_MANAGER"):
        raise HTTPException(status_code=403, detail="Access denied: Insufficient privileges to create users.")

    form = await request.form()
    payload = {
        "username": form.get("username", ""),
        "fullName": form.get("fullName", ""),
        "password": form.get("password", ""),
        "role": form.get("role", "BARN_SUPERVISOR"),
        "farmId": form.get("farmId", ""),
        "barnIds": form.getlist("barnIds")
    }

    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.post(f"{BACKEND_BASE_URL}/users", json=payload, headers=get_auth_headers(user))

    if res.status_code == 403:
        err_detail = extract_backend_error(res, f"create user '{payload['username']}' in Farm '{payload['farmId']}'")
        msg = format_403_security_message(user, err_detail, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(url=f"/users?error={quote(msg)}", status_code=status.HTTP_303_SEE_OTHER)

    if res.status_code != 200:
        err_msg = extract_backend_error(res, "Failed to create user")
        return RedirectResponse(url=f"/users?error={quote(err_msg)}", status_code=status.HTTP_303_SEE_OTHER)

    success_msg = f"Created user {payload['username']} with role {payload['role']}"
    return RedirectResponse(
        url=f"/users?success={quote(success_msg)}",
        status_code=status.HTTP_303_SEE_OTHER
    )


@app.post("/users/{target_username}/update")
async def update_user_submit(target_username: str, request: Request, user: dict = Depends(get_current_user)):
    """Edit an account: name, role, farm, EVERY ticked barn, enabled flag and an optional new password."""
    if not user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)
    if user.get("role") not in ("SYSTEM_ADMIN", "FARM_MANAGER"):
        raise HTTPException(status_code=403, detail="Access denied: Insufficient privileges to edit users.")

    form = await request.form()
    role = form.get("role", "BARN_SUPERVISOR")
    farm_id = form.get("farmId", "") if role in ("FARM_MANAGER", "BARN_SUPERVISOR") else ""
    payload = {
        "fullName": form.get("fullName", ""),
        "role": role,
        "farmId": farm_id,
        "barnIds": form.getlist("barnIds") if role == "BARN_SUPERVISOR" else [],
        "enabled": form.get("enabled") == "true",          # an unticked box sends nothing = disabled
        "newPassword": form.get("newPassword", ""),
    }
    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.put(f"{BACKEND_BASE_URL}/users/{quote(target_username)}", json=payload, headers=get_auth_headers(user))

    if res.status_code == 403:
        msg = format_403_security_message(user, extract_backend_error(res, f"edit user '{target_username}'"), request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(url=f"/users?error={quote(msg)}", status_code=status.HTTP_303_SEE_OTHER)
    if res.status_code != 200:
        return RedirectResponse(url=f"/users?error={quote(extract_backend_error(res, 'Failed to update the account'))}",
                                status_code=status.HTTP_303_SEE_OTHER)

    note = " Password changed." if (res.json() or {}).get("passwordChanged") else ""
    return RedirectResponse(url=f"/users?success={quote(f'Saved {target_username} ({role}).' + note)}", status_code=status.HTTP_303_SEE_OTHER)


@app.post("/users/{target_username}/delete")
async def delete_user_submit(target_username: str, request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)
    if user.get("role") not in ("SYSTEM_ADMIN", "FARM_MANAGER"):
        raise HTTPException(status_code=403, detail="Access denied: Insufficient privileges to delete users.")

    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.delete(f"{BACKEND_BASE_URL}/users/{quote(target_username)}", headers=get_auth_headers(user))

    if res.status_code == 403:
        msg = format_403_security_message(user, extract_backend_error(res, f"delete user '{target_username}'"), request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(url=f"/users?error={quote(msg)}", status_code=status.HTTP_303_SEE_OTHER)
    if res.status_code != 200:
        return RedirectResponse(url=f"/users?error={quote(extract_backend_error(res, 'Failed to delete the account'))}",
                                status_code=status.HTTP_303_SEE_OTHER)
    return RedirectResponse(url=f"/users?success={quote(f'Deleted the account {target_username}.')}", status_code=status.HTTP_303_SEE_OTHER)
