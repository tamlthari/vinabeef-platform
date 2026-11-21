import json
import math
import os
from datetime import date, datetime
from urllib.parse import quote

import httpx
from fastapi import Depends, FastAPI, Form, HTTPException, Request, Response, status
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.middleware.sessions import SessionMiddleware

app = FastAPI(title="VinaBeef Enterprise Portal")
app.add_middleware(
    SessionMiddleware,
    secret_key="vinabeef-platform-session-secret-2026",
    same_site="lax",
    https_only=False,
)
app.mount("/static", StaticFiles(directory="static"), name="static")
templates = Jinja2Templates(directory="templates")

BACKEND_BASE_URL = os.getenv("BACKEND_BASE_URL", "http://127.0.0.1:8080/api/v1")

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
        return [
            1,
            "...",
            total_pages - 4,
            total_pages - 3,
            total_pages - 2,
            total_pages - 1,
            total_pages,
        ]
    return [
        1,
        "...",
        current_page - 1,
        current_page,
        current_page + 1,
        "...",
        total_pages,
    ]


async def fetch_paginated_cattle(
    client: httpx.AsyncClient,
    headers: dict,
    q: str = "",
    page: int = 1,
    size: int = 100,
) -> dict:
    page = max(1, page)
    size = 100
    try:
        res = await client.get(
            f"{BACKEND_BASE_URL}/cattle",
            params={"q": q, "page": page, "size": size},
            headers=headers,
        )
        if res.status_code == 200:
            cattle = res.json()
            total_cattle = int(res.headers.get("X-Total-Count", len(cattle)))
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
        return [f for f in farms if f.get("farmId") in allowed_farms]

    if role == "BARN_SUPERVISOR":
        allowed_barns = get_allowed_barn_ids(user)
        scoped_farms = []
        for f in farms:
            matching_barns = [
                b for b in f.get("barns", []) if b.get("barnId") in allowed_barns
            ]
            if matching_barns:
                f_copy = dict(f)
                f_copy["barns"] = matching_barns
                f_copy["totalCapacity"] = sum(
                    b.get("capacity", 0) for b in matching_barns
                )
                f_copy["totalHeadcount"] = sum(
                    b.get("currentHeadcount", 0) for b in matching_barns
                )
                f_copy["totalInCount"] = sum(
                    b.get("inCount", 0) for b in matching_barns
                )
                f_copy["totalOutCount"] = sum(
                    b.get("outCount", 0) for b in matching_barns
                )
                f_copy["totalAnimals"] = sum(
                    b.get("assignedAnimals", 0) for b in matching_barns
                )
                f_copy["vaccinatedAnimals"] = sum(
                    b.get("vaccinatedAnimals", 0) for b in matching_barns
                )
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
        prefixes = tuple(
            FARM_TO_BARN_PREFIX[f] for f in allowed_farms if f in FARM_TO_BARN_PREFIX
        )
        scoped = []
        for b in barns:
            farm_id = str(b.get("farmId") or b.get("farm_id") or "")
            barn_id = str(b.get("barnId") or b.get("barn_id") or "")
            if farm_id in allowed_farms or (prefixes and barn_id.startswith(prefixes)):
                scoped.append(b)
        return scoped

    if role == "BARN_SUPERVISOR":
        allowed_barns = get_allowed_barn_ids(user)
        return [
            b
            for b in barns
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
        prefixes = tuple(
            FARM_TO_BARN_PREFIX[f] for f in allowed_farms if f in FARM_TO_BARN_PREFIX
        )
        scoped = []
        for r in readers:
            farm_id = str(r.get("farm_id") or r.get("farmId") or "")
            barn_id = str(r.get("barn_id") or r.get("barnId") or "")
            if farm_id in allowed_farms or (prefixes and barn_id.startswith(prefixes)):
                scoped.append(r)
        return scoped

    if role == "BARN_SUPERVISOR":
        allowed_barns = get_allowed_barn_ids(user)
        return [
            r
            for r in readers
            if str(r.get("barn_id") or r.get("barnId") or "") in allowed_barns
        ]

    return []


# --- Role Based Access & 403 Security Banner Helpers ---


def extract_backend_error(res: httpx.Response, fallback: str = "") -> str:
    try:
        data = res.json()
        if isinstance(data, dict):
            return str(
                data.get("error")
                or data.get("detail")
                or data.get("message")
                or fallback
            )
    except Exception:
        pass
    return (res.text or fallback).strip()


def flash_header(message: str, kind: str = "ok") -> dict:
    """HX-Trigger header: base.html shows the message in the flash box after an HTMX swap."""
    return {"HX-Trigger": json.dumps({"flash": {"message": message, "kind": kind}})}


def redirect_with(path: str, notice: str = "", problem: str = "") -> RedirectResponse:
    """Redirect after a form post; base.html shows `notice` (green) or `problem` (red) above the page."""
    params = []
    if notice:
        params.append("notice=" + quote(notice))
    if problem:
        params.append("problem=" + quote(problem))
    return RedirectResponse(
        url=path + ("?" + "&".join(params) if params else ""),
        status_code=status.HTTP_303_SEE_OTHER,
    )


def format_403_security_message(user: dict, detail: str, path: str) -> str:
    username = user.get("username", "unknown") if user else "unknown"
    clean_detail = (detail or "").strip()

    if clean_detail.startswith("Access Denied (403):"):
        if "Security violation logged in Audit ledger." not in clean_detail:
            return f"{clean_detail.rstrip('.')}. Security violation logged in Audit ledger."
        return clean_detail

    if "is not authorized to " in clean_detail:
        idx = clean_detail.index("is not authorized to ")
        action_clause = clean_detail[idx + len("is not authorized to ") :].rstrip(".")
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
            clean_detail.removeprefix("Access denied:")
            .removeprefix("Access Denied:")
            .strip()
            .rstrip(".")
        )
        action_desc = (
            stripped
            if stripped.startswith(
                (
                    "mutate ",
                    "transfer ",
                    "update ",
                    "create ",
                    "modify ",
                    "record ",
                    "access ",
                )
            )
            else f"perform action ({stripped})"
        )
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

        formatted_msg = format_403_security_message(
            user, str(exc.detail or ""), request.url.path
        )
        await log_security_violation_best_effort(user, request.url.path, formatted_msg)

        if "application/json" in request.headers.get("accept", ""):
            return JSONResponse(status_code=403, content={"detail": formatted_msg})

        if request.headers.get("hx-request") == "true":
            return HTMLResponse(
                content=render_403_alert_html(formatted_msg), status_code=403
            )

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


@app.post("/login")
async def login_submit(
    request: Request, username: str = Form(...), password: str = Form(...)
):
    async with httpx.AsyncClient(timeout=5.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/auth/login",
            json={"username": username, "password": password},
        )
        if res.status_code != 200:
            return templates.TemplateResponse(
                "login.html",
                {"request": request, "error": "Invalid username or password"},
            )

        data = res.json()
        farm_id = data.get("farmId", "ALL")
        farm_scopes = data.get("farm_scopes") or (
            [farm_id] if farm_id and farm_id != "ALL" else []
        )

        request.session["user"] = {
            "username": data.get("username", username),
            "fullName": data.get("fullName", username),
            "role": data.get("role", "EXECUTIVE"),
            "farmId": farm_id,
            "farm_scopes": farm_scopes,
            "assignedBarns": data.get("assignedBarns", []),
            "token": data.get("token"),
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
            dist_res = await client.get(
                f"{BACKEND_BASE_URL}/facilities/distribution", headers=headers
            )
            farms = dist_res.json() if dist_res.status_code == 200 else []
        except Exception:
            farms = []

        try:
            mov_res = await client.get(
                f"{BACKEND_BASE_URL}/movements/recent", headers=headers
            )
            movements = mov_res.json() if mov_res.status_code == 200 else []
        except Exception:
            movements = []

    farms = filter_farms_by_scope(farms, user)

    return templates.TemplateResponse(
        "index.html",
        {
            "request": request,
            "user": user,
            "farms": farms,
            "movements": movements,
            "current_date": datetime.now(),
        },
    )


# 2. Audit Trail Viewer (Restricted to SYSTEM_ADMIN)
@app.get("/audit", response_class=HTMLResponse)
async def audit_page(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")
    if user["role"] != "SYSTEM_ADMIN":
        raise HTTPException(
            status_code=403, detail="Access denied: SYSTEM_ADMIN role required."
        )

    return templates.TemplateResponse("audit.html", {"request": request, "user": user})


# 3. Reports & Analytics (Restricted to EXECUTIVE, FARM_MANAGER, SYSTEM_ADMIN)
@app.get("/reports", response_class=HTMLResponse)
async def reports_page(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")
    if user["role"] not in ["EXECUTIVE", "FARM_MANAGER", "SYSTEM_ADMIN"]:
        raise HTTPException(
            status_code=403,
            detail="Access denied: Insufficient privileges for reporting.",
        )

    return templates.TemplateResponse(
        "reports.html", {"request": request, "user": user}
    )


# 4. Cattle Transfer Workbench
@app.get("/transfers", response_class=HTMLResponse)
async def transfers_page(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")
    if user["role"] not in ["FARM_MANAGER", "SYSTEM_ADMIN"]:
        raise HTTPException(
            status_code=403,
            detail="Access denied: Only Farm Managers can approve transfers.",
        )

    return templates.TemplateResponse(
        "transfers.html", {"request": request, "user": user}
    )


# --- HTMX Dynamic Partial Endpoints ---


@app.get("/htmx/barns", response_class=HTMLResponse)
async def htmx_barns(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return HTMLResponse("Session expired. Please sign in again.", status_code=401)

    headers = get_auth_headers(user)
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            res = await client.get(
                f"{BACKEND_BASE_URL}/facilities/distribution", headers=headers
            )
            res.raise_for_status()
            farms = filter_farms_by_scope(res.json(), user)
    except (httpx.HTTPError, ValueError) as exc:
        return templates.TemplateResponse(
            "partials/headcount_cards.html",
            {"request": request, "farms": [], "dashboard_error": str(exc)},
            status_code=502,
        )

    return templates.TemplateResponse(
        "partials/headcount_cards.html",
        {"request": request, "farms": farms, "dashboard_error": None},
    )


@app.get("/htmx/movements", response_class=HTMLResponse)
async def htmx_movements(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return HTMLResponse("Session expired.", status_code=401)

    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            res = await client.get(
                f"{BACKEND_BASE_URL}/movements/recent", headers=get_auth_headers(user)
            )
            res.raise_for_status()
            movements = res.json()
    except (httpx.HTTPError, ValueError) as exc:
        return templates.TemplateResponse(
            "partials/movement_rows.html",
            {"request": request, "movements": [], "error": str(exc)},
            status_code=502,
        )

    return templates.TemplateResponse(
        "partials/movement_rows.html", {"request": request, "movements": movements}
    )


@app.get("/htmx/audit-table", response_class=HTMLResponse)
async def htmx_audit_table(request: Request, user: dict = Depends(get_current_user)):
    if not user or user["role"] != "SYSTEM_ADMIN":
        msg = format_403_security_message(
            user or {}, "access the Enterprise Audit Ledger", request.url.path
        )
        return HTMLResponse(render_403_alert_html(msg), status_code=403)

    async with httpx.AsyncClient(timeout=3.0) as client:
        try:
            res = await client.get(
                f"{BACKEND_BASE_URL}/management/audit-logs",
                headers=get_auth_headers(user),
            )
            logs = res.json() if res.status_code == 200 else []
        except Exception:
            logs = []

    return templates.TemplateResponse(
        "partials/audit_table.html", {"request": request, "logs": logs}
    )


@app.get("/htmx/ingestion-status", response_class=HTMLResponse)
async def htmx_ingestion_status(
    request: Request, user: dict = Depends(get_current_user)
):
    if not user:
        return HTMLResponse(
            "<div class='p-4 text-rose-400'>Session expired.</div>", status_code=401
        )

    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            res = await client.get(
                f"{BACKEND_BASE_URL}/ingestion/failover-status",
                headers=get_auth_headers(user),
            )
            res.raise_for_status()
            ingestion_status = res.json()
    except (httpx.HTTPError, ValueError) as exc:
        return templates.TemplateResponse(
            "partials/ingestion_status.html", {"request": request, "error": str(exc)}
        )

    return templates.TemplateResponse(
        "partials/ingestion_status.html",
        {"request": request, "status": ingestion_status, "error": None},
    )


@app.get("/htmx/report-data", response_class=HTMLResponse)
async def htmx_report_data(
    request: Request, farm_id: str = "ALL", user: dict = Depends(get_current_user)
):
    if not user:
        return "<div class='text-rose-400 p-4'>Unauthorized</div>"
    if user.get("role") not in ["EXECUTIVE", "FARM_MANAGER", "SYSTEM_ADMIN"]:
        msg = format_403_security_message(
            user, "access Reporting & Regulatory Compliance", request.url.path
        )
        return HTMLResponse(render_403_alert_html(msg), status_code=403)

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=4.0) as client:
        if farm_id == "ALL":
            try:
                res = await client.get(
                    f"{BACKEND_BASE_URL}/management/executive/summary", headers=headers
                )
                if res.status_code == 403:
                    msg = format_403_security_message(
                        user,
                        extract_backend_error(res, "access global executive reports"),
                        request.url.path,
                    )
                    return HTMLResponse(render_403_alert_html(msg), status_code=403)
                data = res.json() if res.status_code == 200 else []
                is_summary = True
            except Exception:
                data, is_summary = [], True
        else:
            try:
                res = await client.get(
                    f"{BACKEND_BASE_URL}/management/farms/{farm_id}/report",
                    headers=headers,
                )
                if res.status_code == 403:
                    msg = format_403_security_message(
                        user,
                        extract_backend_error(res, f"access Farm '{farm_id}' report"),
                        request.url.path,
                    )
                    return HTMLResponse(render_403_alert_html(msg), status_code=403)
                data = res.json().get("barnData", []) if res.status_code == 200 else []
                is_summary = False
            except Exception:
                data, is_summary = [], False

        try:
            mov_res = await client.get(
                f"{BACKEND_BASE_URL}/movements/recent", headers=headers
            )
            recent_movs = mov_res.json() if mov_res.status_code == 200 else []
        except Exception:
            recent_movs = []

    report_logs = []
    if recent_movs:
        for m in recent_movs:
            report_logs.append(
                {
                    "date_time": str(m.get("occurredAt", "")).replace("T", " ")[:19],
                    "person": m.get("animalId", "CI 356278"),
                    "sensor_name": (
                        "DrEBE6" if "GATE-DL" in m.get("gateId", "") else "DrC1C4"
                    ),
                    "device_access": "RFID Ear Tag",
                    "sensor_description": (
                        "North Gate Barn 01"
                        if "01A" in m.get("gateId", "")
                        else "South Feeding Gate"
                    ),
                    "event": f"Movement {m.get('direction', 'IN')}",
                    "result": "Success",
                }
            )
    else:
        report_logs = [
            {
                "date_time": "10/17/2026 03:01:28",
                "person": "CI 356278",
                "sensor_name": "DrEBE6",
                "device_access": "RFID Ear Tag",
                "sensor_description": "North Pasture Gate",
                "event": "Movement OUT",
                "result": "Success",
            },
            {
                "date_time": "10/17/2026 02:55:12",
                "person": "COW-0002",
                "sensor_name": "DrEBE6",
                "device_access": "RFID Ear Tag",
                "sensor_description": "North Pasture Gate",
                "event": "Movement IN",
                "result": "Success",
            },
            {
                "date_time": "10/17/2026 02:40:05",
                "person": "COW-0003",
                "sensor_name": "DrEBE7",
                "device_access": "RFID Ear Tag",
                "sensor_description": "South Feeding Gate",
                "event": "Movement OUT",
                "result": "Success",
            },
            {
                "date_time": "10/17/2026 02:15:30",
                "person": "an.nguyen@safetrust.com",
                "sensor_name": "DrC1C4",
                "device_access": "Phone",
                "sensor_description": "Door C1C4",
                "event": "Authentication",
                "result": "Success",
            },
        ]

    return templates.TemplateResponse(
        "partials/report_content.html",
        {
            "request": request,
            "farm_id": farm_id,
            "data": data,
            "logs": report_logs,
            "is_summary": is_summary,
        },
    )


@app.post("/htmx/transfer-action", response_class=HTMLResponse)
async def htmx_transfer_action(
    request: Request,
    farm_id: str = Form(...),
    animal_id: str = Form(...),
    target_barn_id: str = Form(...),
    reason: str = Form(...),
    user: dict = Depends(get_current_user),
):
    if not user:
        return "<div class='p-4 text-rose-400 bg-rose-950/40 rounded-lg'>Session expired.</div>"

    payload = {"animalId": animal_id, "targetBarnId": target_barn_id, "reason": reason}

    async with httpx.AsyncClient(timeout=4.0) as client:
        try:
            res = await client.post(
                f"{BACKEND_BASE_URL}/management/farms/{farm_id}/transfers",
                json=payload,
                headers=get_auth_headers(user),
            )

            if res.status_code == 200:
                data = res.json()
                return f"""
                <div class="p-4 bg-emerald-50 border border-emerald-200 text-emerald-800 rounded-lg text-xs font-semibold">
                    <strong>Mutation Approved:</strong> Cattle {data.get('animalId')} transfer registered in {data.get('farmId')} by {data.get('approvedBy')}.
                </div>
                """
            elif res.status_code == 403:
                msg = format_403_security_message(
                    user, f"mutate Farm '{farm_id}'", request.url.path
                )
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


@app.get("/herd", response_class=HTMLResponse)
async def herd_page(
    request: Request, page: int = 1, q: str = "", user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=10.0) as client:
        pagination = await fetch_paginated_cattle(
            client, headers, q=q, page=page, size=100
        )
        try:
            barns_res = await client.get(f"{BACKEND_BASE_URL}/barns", headers=headers)
            barns = filter_barns_by_scope(
                barns_res.json() if barns_res.status_code == 200 else [], user
            )
        except Exception:
            barns = []

    return templates.TemplateResponse(
        "herd.html", {"request": request, "user": user, "barns": barns, **pagination}
    )


@app.get("/htmx/herd-list", response_class=HTMLResponse)
async def htmx_herd_list(
    request: Request, page: int = 1, q: str = "", user: dict = Depends(get_current_user)
):
    headers = get_auth_headers(user) if user else {}
    async with httpx.AsyncClient(timeout=10.0) as client:
        pagination = await fetch_paginated_cattle(
            client, headers, q=q, page=page, size=100
        )

    return templates.TemplateResponse(
        "partials/herd_table_content.html",
        {"request": request, "user": user, "oob_update": True, **pagination},
    )


@app.post("/htmx/herd/create", response_class=HTMLResponse)
async def htmx_herd_create(
    request: Request,
    animal_id: str = Form(...),
    barn_id: str = Form(...),
    breed: str = Form("Brahman Cross"),
    weight_kg: float = Form(420.0),
    tag_epc: str = Form(""),
    user: dict = Depends(get_current_user),
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
                "tagEpc": tag_epc,
            },
            headers=headers,
        )
        if res.status_code == 403:
            err = extract_backend_error(res, f"register cattle in Barn '{barn_id}'")
            msg = format_403_security_message(user, err, request.url.path)
            await log_security_violation_best_effort(user, request.url.path, msg)
            return render_403_alert_html(msg)
        if res.status_code != 200:
            err = extract_backend_error(res, "Failed to register cattle")
            return f"<div class='p-4 rounded bg-red-50 border border-red-200 text-red-700 text-xs'>{err}</div>"

        pagination = await fetch_paginated_cattle(
            client, headers, q="", page=1, size=100
        )

    return templates.TemplateResponse(
        "partials/herd_table_content.html",
        {"request": request, "user": user, "oob_update": True, **pagination},
    )


@app.post("/htmx/herd/{animal_id}/delete", response_class=HTMLResponse)
async def htmx_herd_delete(
    request: Request, animal_id: str, user: dict = Depends(get_current_user)
):
    """Delete or archive from the herd list. The backend decides which one and says so."""
    if not user:
        return "<div class='p-4 text-red-600 text-xs'>Session expired.</div>"

    headers = get_auth_headers(user)
    message, kind = "", "ok"
    async with httpx.AsyncClient(timeout=10.0) as client:
        res = await client.delete(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}", headers=headers
        )
        if res.status_code == 403:
            err = extract_backend_error(res, f"remove Cattle '{animal_id}'")
            message = format_403_security_message(user, err, request.url.path)
            await log_security_violation_best_effort(user, request.url.path, message)
            kind = "error"
        elif res.status_code == 404:
            message, kind = f"Cattle '{animal_id}' no longer exists.", "error"
        elif res.status_code != 200:
            message, kind = (
                extract_backend_error(res, "Failed to remove cattle"),
                "error",
            )
        else:
            message = describe_removal(res, "Cattle", animal_id)
        pagination = await fetch_paginated_cattle(
            client, headers, q="", page=1, size=100
        )

    return templates.TemplateResponse(
        "partials/herd_table_content.html",
        {"request": request, "user": user, "oob_update": True, **pagination},
        headers=flash_header(message, kind),
    )


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
async def cattle_detail_page(
    request: Request, animal_id: str, user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.get(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}", headers=headers
        )
        if res.status_code == 403:
            raise HTTPException(
                status_code=403,
                detail=extract_backend_error(res, f"view Cattle '{animal_id}'"),
            )
        if res.status_code != 200:
            raise HTTPException(status_code=404, detail="Animal not found")
        detail = res.json()

        try:
            barns_res = await client.get(f"{BACKEND_BASE_URL}/barns", headers=headers)
            barns = filter_barns_by_scope(
                barns_res.json() if barns_res.status_code == 200 else [], user
            )
        except Exception:
            barns = []

    return templates.TemplateResponse(
        "cattle_detail.html",
        {
            "request": request,
            "user": user,
            "animal": detail["profile"],
            "tags": detail["tags"],
            "vaccinations": detail["vaccinations"],
            "movements": detail["movements"],
            "transfers": detail.get("transfers", []),
            "barns": barns,
        },
    )


@app.post("/herd/{animal_id}/assign-tag")
async def cattle_assign_tag(
    request: Request,
    animal_id: str,
    tag_epc: str = Form(...),
    credential_identifier: str = Form("EPC-26"),
    user: dict = Depends(get_current_user),
):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}/tags",
            json={"tagEpc": tag_epc, "credentialIdentifier": credential_identifier},
            headers=get_auth_headers(user),
        )
    if res.status_code == 403:
        err = extract_backend_error(res, f"mutate RFID tag for Cattle '{animal_id}'")
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(
            url=f"/herd/{animal_id}?error={quote(msg)}",
            status_code=status.HTTP_303_SEE_OTHER,
        )

    return RedirectResponse(
        url=f"/herd/{animal_id}", status_code=status.HTTP_303_SEE_OTHER
    )


@app.post("/herd/{animal_id}/update")
async def cattle_update_action(
    request: Request,
    animal_id: str,
    breed: str = Form(""),
    weight_kg: str = Form(""),
    user: dict = Depends(get_current_user),
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
            return redirect_with(
                f"/herd/{animal_id}", problem="Weight must be a number."
            )

    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.put(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}",
            json=payload,
            headers=get_auth_headers(user),
        )
    if res.status_code == 403:
        err = extract_backend_error(res, f"edit Cattle '{animal_id}'")
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(
            url=f"/herd/{animal_id}?error={quote(msg)}",
            status_code=status.HTTP_303_SEE_OTHER,
        )
    if res.status_code != 200:
        return redirect_with(
            f"/herd/{animal_id}",
            problem=extract_backend_error(res, "Could not update the animal."),
        )
    return redirect_with(f"/herd/{animal_id}", notice="Animal details updated.")


@app.post("/herd/{animal_id}/delete")
async def cattle_delete_action(
    request: Request,
    animal_id: str,
    reason: str = Form(""),
    user: dict = Depends(get_current_user),
):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.delete(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}",
            params={"reason": reason.strip()} if reason.strip() else None,
            headers=get_auth_headers(user),
        )
    if res.status_code == 403:
        err = extract_backend_error(res, f"remove Cattle '{animal_id}'")
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(
            url=f"/herd/{animal_id}?error={quote(msg)}",
            status_code=status.HTTP_303_SEE_OTHER,
        )
    if res.status_code == 404:
        return redirect_with("/herd", problem=f"Cattle '{animal_id}' no longer exists.")
    if res.status_code != 200:
        return redirect_with(
            f"/herd/{animal_id}",
            problem=extract_backend_error(res, "Could not remove the animal."),
        )
    return redirect_with("/herd", notice=describe_removal(res, "Cattle", animal_id))


@app.post("/herd/{animal_id}/vaccinate")
async def cattle_record_vaccination(
    request: Request,
    animal_id: str,
    vaccine_name: str = Form(...),
    batch_number: str = Form(...),
    notes: str = Form(""),
    user: dict = Depends(get_current_user),
):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}/vaccinations",
            json={
                "vaccineName": vaccine_name,
                "batchNumber": batch_number,
                "notes": notes,
            },
            headers=get_auth_headers(user),
        )
    if res.status_code == 403:
        err = extract_backend_error(res, f"record vaccination for Cattle '{animal_id}'")
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(
            url=f"/herd/{animal_id}?error={quote(msg)}",
            status_code=status.HTTP_303_SEE_OTHER,
        )

    return RedirectResponse(
        url=f"/herd/{animal_id}", status_code=status.HTTP_303_SEE_OTHER
    )


@app.post("/herd/{animal_id}/health")
async def cattle_record_health(
    request: Request,
    animal_id: str,
    record_type: str = Form(...),
    health_status: str = Form("HEALTHY"),
    title: str = Form(...),
    medication: str = Form(""),
    notes: str = Form(""),
    user: dict = Depends(get_current_user),
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
                "notes": notes,
            },
            headers=get_auth_headers(user),
        )
    if res.status_code == 403:
        err = extract_backend_error(
            res, f"mutate health status for Cattle '{animal_id}'"
        )
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(
            url=f"/herd/{animal_id}?error={quote(msg)}",
            status_code=status.HTTP_303_SEE_OTHER,
        )

    return RedirectResponse(
        url=f"/herd/{animal_id}", status_code=status.HTTP_303_SEE_OTHER
    )


@app.post("/herd/{animal_id}/transfer")
async def cattle_transfer_action(
    request: Request,
    animal_id: str,
    to_barn_id: str = Form(...),
    reason: str = Form(""),
    user: dict = Depends(get_current_user),
):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}/transfer",
            json={"toBarnId": to_barn_id, "reason": reason},
            headers=get_auth_headers(user),
        )
    if res.status_code == 403:
        err = extract_backend_error(
            res, f"transfer Cattle '{animal_id}' to Barn '{to_barn_id}'"
        )
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(
            url=f"/herd/{animal_id}?error={quote(msg)}",
            status_code=status.HTTP_303_SEE_OTHER,
        )

    return RedirectResponse(
        url=f"/herd/{animal_id}", status_code=status.HTTP_303_SEE_OTHER
    )


@app.get("/herd/{animal_id}/dossier")
async def cattle_download_dossier(
    animal_id: str, user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.get(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}/dossier",
            headers=get_auth_headers(user),
        )
        if res.status_code == 403:
            raise HTTPException(
                status_code=403,
                detail=extract_backend_error(
                    res, f"export dossier for Cattle '{animal_id}'"
                ),
            )
        return Response(
            content=res.content,
            media_type="application/json",
            headers={
                "Content-Disposition": f'attachment; filename="dossier_{animal_id.replace(" ", "_")}.json"'
            },
        )


# ================= GATE READER MANAGEMENT =================


@app.get("/readers", response_class=HTMLResponse)
async def readers_page(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=4.0) as client:
        try:
            res = await client.get(f"{BACKEND_BASE_URL}/readers", headers=headers)
            if res.status_code == 401:
                return RedirectResponse(url="/login")
            if res.status_code == 403:
                raise HTTPException(
                    status_code=403,
                    detail=extract_backend_error(res, "access Gate Readers"),
                )
            readers = filter_readers_by_scope(
                res.json() if res.status_code == 200 else [], user
            )
        except HTTPException:
            raise
        except Exception:
            readers = []

        try:
            barns_res = await client.get(f"{BACKEND_BASE_URL}/barns", headers=headers)
            barns = filter_barns_by_scope(
                barns_res.json() if barns_res.status_code == 200 else [], user
            )
        except Exception:
            barns = []

    return templates.TemplateResponse(
        "readers.html",
        {"request": request, "user": user, "readers": readers, "barns": barns},
    )


@app.get("/htmx/readers-list", response_class=HTMLResponse)
async def htmx_readers_list(
    request: Request, q: str = "", user: dict = Depends(get_current_user)
):
    headers = get_auth_headers(user) if user else {}
    async with httpx.AsyncClient(timeout=3.0) as client:
        try:
            res = await client.get(
                f"{BACKEND_BASE_URL}/readers", params={"q": q}, headers=headers
            )
            readers = filter_readers_by_scope(
                res.json() if res.status_code == 200 else [], user
            )
        except Exception:
            readers = []
    return templates.TemplateResponse(
        "partials/reader_rows.html",
        {"request": request, "user": user, "readers": readers},
    )


@app.post("/htmx/readers/create", response_class=HTMLResponse)
async def htmx_readers_create(
    request: Request,
    reader_name: str = Form(...),
    label: str = Form(...),
    barn_id: str = Form(...),
    direction_mode: str = Form("BIDIRECTIONAL"),
    outer_reader_name: str = Form(""),
    firmware_version: str = Form("1.58.1998"),
    user: dict = Depends(get_current_user),
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
                "firmwareVersion": firmware_version,
            },
            headers=get_auth_headers(user),
        )
        message, kind = (
            f"Reader '{reader_name.strip()}' registered ({direction_mode}).",
            "ok",
        )
        if res.status_code == 200:
            message = f"{message} {backend_note(res)}".strip()
        if res.status_code == 403:
            err = extract_backend_error(res, f"provision reader in Barn '{barn_id}'")
            message = format_403_security_message(user, err, request.url.path)
            await log_security_violation_best_effort(user, request.url.path, message)
            kind = "error"
        elif res.status_code != 200:
            message, kind = (
                extract_backend_error(res, "Failed to register the reader"),
                "error",
            )

        list_res = await client.get(
            f"{BACKEND_BASE_URL}/readers", headers=get_auth_headers(user)
        )
        readers = filter_readers_by_scope(
            list_res.json() if list_res.status_code == 200 else [], user
        )
    return templates.TemplateResponse(
        "partials/reader_rows.html",
        {"request": request, "user": user, "readers": readers},
        headers=flash_header(message, kind),
    )


@app.post("/htmx/readers/{gate_id}/delete", response_class=HTMLResponse)
async def htmx_readers_delete(
    request: Request, gate_id: str, user: dict = Depends(get_current_user)
):
    """Delete or retire from the readers list. The backend decides which one and says so."""
    if not user:
        return ""

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.delete(
            f"{BACKEND_BASE_URL}/readers/{gate_id}", headers=headers
        )
        message, kind = "", "ok"
        if res.status_code == 403:
            err = extract_backend_error(res, f"remove Gate Reader '{gate_id}'")
            message = format_403_security_message(user, err, request.url.path)
            await log_security_violation_best_effort(user, request.url.path, message)
            kind = "error"
        elif res.status_code == 404:
            message, kind = f"Gate reader '{gate_id}' no longer exists.", "error"
        elif res.status_code != 200:
            message, kind = (
                extract_backend_error(res, "Failed to remove the reader"),
                "error",
            )
        else:
            message = describe_removal(res, "Reader", gate_id)

        list_res = await client.get(f"{BACKEND_BASE_URL}/readers", headers=headers)
        readers = filter_readers_by_scope(
            list_res.json() if list_res.status_code == 200 else [], user
        )
    return templates.TemplateResponse(
        "partials/reader_rows.html",
        {"request": request, "user": user, "readers": readers},
        headers=flash_header(message, kind),
    )


@app.get("/readers/{gate_id}", response_class=HTMLResponse)
async def reader_detail_page(
    request: Request, gate_id: str, user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.get(f"{BACKEND_BASE_URL}/readers/{gate_id}", headers=headers)
        if res.status_code == 403:
            raise HTTPException(
                status_code=403,
                detail=extract_backend_error(res, f"access Gate Reader '{gate_id}'"),
            )
        if res.status_code != 200:
            raise HTTPException(status_code=404, detail="Gate reader not found")
        detail = res.json()
        if not filter_readers_by_scope([detail["reader"]], user):
            raise HTTPException(
                status_code=403, detail=f"access Gate Reader '{gate_id}'"
            )

        barns_res = await client.get(f"{BACKEND_BASE_URL}/barns", headers=headers)
        barns = filter_barns_by_scope(
            barns_res.json() if barns_res.status_code == 200 else [], user
        )

    return templates.TemplateResponse(
        "reader_detail.html",
        {
            "request": request,
            "user": user,
            "reader": detail["reader"],
            "transactions": detail["transactions"],
            "barns": barns,
        },
    )


@app.post("/readers/{gate_id}/update")
async def reader_update_action(
    request: Request,
    gate_id: str,
    barn_id: str = Form(...),
    label: str = Form(...),
    direction_mode: str = Form(...),
    reader_name: str = Form(""),
    firmware_version: str = Form("1.58.1998"),
    user: dict = Depends(get_current_user),
):
    if not user:
        return RedirectResponse(url="/login")

    # FastAPI turns an empty form value into "missing", but here the difference matters: a form that sends an EMPTY
    # outer reader removes it, a form without the field leaves it alone.
    form = await request.form()
    outer_reader_name = form.get("outer_reader_name")

    payload = {
        "barnId": barn_id,
        "label": label,
        "directionMode": direction_mode,
        "firmwareVersion": firmware_version,
    }
    if reader_name.strip():
        payload["readerName"] = reader_name.strip()
    if outer_reader_name is not None:
        # an empty value removes the outer reader (needed to switch a paired gate to IN_ONLY / OUT_ONLY)
        payload["outerReaderName"] = outer_reader_name.strip()

    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.put(
            f"{BACKEND_BASE_URL}/readers/{gate_id}",
            json=payload,
            headers=get_auth_headers(user),
        )
    if res.status_code == 403:
        err = extract_backend_error(
            res, f"mutate Gate Reader '{gate_id}' in Barn '{barn_id}'"
        )
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(
            url=f"/readers/{gate_id}?error={quote(msg)}",
            status_code=status.HTTP_303_SEE_OTHER,
        )
    if res.status_code != 200:
        return redirect_with(
            f"/readers/{gate_id}",
            problem=extract_backend_error(res, "Could not save the reader settings."),
        )

    return redirect_with(
        f"/readers/{gate_id}",
        notice=f"Reader settings saved. {backend_note(res)}".strip(),
    )


@app.post("/readers/{gate_id}/delete")
async def reader_delete_action(
    request: Request, gate_id: str, user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.delete(
            f"{BACKEND_BASE_URL}/readers/{gate_id}", headers=get_auth_headers(user)
        )
    if res.status_code == 403:
        err = extract_backend_error(res, f"remove Gate Reader '{gate_id}'")
        msg = format_403_security_message(user, err, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(
            url=f"/readers/{gate_id}?error={quote(msg)}",
            status_code=status.HTTP_303_SEE_OTHER,
        )
    if res.status_code == 404:
        return redirect_with(
            "/readers", problem=f"Gate reader '{gate_id}' no longer exists."
        )
    if res.status_code != 200:
        return redirect_with(
            f"/readers/{gate_id}",
            problem=extract_backend_error(res, "Could not remove the reader."),
        )
    return redirect_with("/readers", notice=describe_removal(res, "Reader", gate_id))


# ================= ABNORMALITY DETECTION & ALERTING =================


async def fetch_alerts_data(
    client: httpx.AsyncClient,
    headers: dict,
    state: str = "OPEN",
    alert_type: str = "ALL",
    q: str = "",
) -> dict:
    try:
        res = await client.get(
            f"{BACKEND_BASE_URL}/alerts",
            params={"state": state, "type": alert_type, "q": q},
            headers=headers,
        )
        if res.status_code == 200:
            data = res.json()
            return {
                "alerts": data.get("alerts", []),
                "stats": data.get(
                    "stats",
                    {
                        "openCount": 0,
                        "acknowledgedCount": 0,
                        "missingCount": 0,
                        "unexpectedCount": 0,
                        "wrongBarnCount": 0,
                    },
                ),
            }
    except Exception as e:
        print(f"[ERROR] Failed to fetch alerts: {e}")

    return {
        "alerts": [],
        "stats": {
            "openCount": 0,
            "acknowledgedCount": 0,
            "missingCount": 0,
            "unexpectedCount": 0,
            "wrongBarnCount": 0,
        },
    }


@app.get("/alerts", response_class=HTMLResponse)
async def alerts_page(
    request: Request,
    state: str = "OPEN",
    type: str = "ALL",
    q: str = "",
    user: dict = Depends(get_current_user),
):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=8.0) as client:
        alert_data = await fetch_alerts_data(
            client, headers, state=state, alert_type=type, q=q
        )
        try:
            barns_res = await client.get(f"{BACKEND_BASE_URL}/barns", headers=headers)
            barns = filter_barns_by_scope(
                barns_res.json() if barns_res.status_code == 200 else [], user
            )
        except Exception:
            barns = []

    return templates.TemplateResponse(
        "alerts.html",
        {
            "request": request,
            "user": user,
            "alerts": alert_data["alerts"],
            "stats": alert_data["stats"],
            "barns": barns,
            "current_state": state,
            "current_type": type,
            "q": q,
        },
    )


@app.get("/htmx/alerts-list", response_class=HTMLResponse)
async def htmx_alerts_list(
    request: Request,
    state: str = "OPEN",
    type: str = "ALL",
    q: str = "",
    user: dict = Depends(get_current_user),
):
    headers = get_auth_headers(user) if user else {}
    async with httpx.AsyncClient(timeout=8.0) as client:
        alert_data = await fetch_alerts_data(
            client, headers, state=state, alert_type=type, q=q
        )

    return templates.TemplateResponse(
        "partials/alert_table_content.html",
        {
            "request": request,
            "user": user,
            "alerts": alert_data["alerts"],
            "stats": alert_data["stats"],
            "current_state": state,
            "current_type": type,
            "q": q,
            "oob_update": True,
        },
    )


@app.post("/htmx/alerts/reconcile", response_class=HTMLResponse)
async def htmx_alerts_reconcile(
    request: Request,
    barn_id: str = Form(...),
    threshold_minutes: int = Form(30),
    user: dict = Depends(get_current_user),
):
    if not user:
        return "<div class='p-4 text-red-600 text-xs'>Session expired.</div>"

    headers = get_auth_headers(user)
    banner_msg = ""
    async with httpx.AsyncClient(timeout=10.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/alerts/reconcile-missing",
            json={"barnId": barn_id, "thresholdMinutes": threshold_minutes},
            headers=headers,
        )
        if res.status_code == 403:
            err = extract_backend_error(res, f"reconcile Barn '{barn_id}'")
            msg = format_403_security_message(user, err, request.url.path)
            await log_security_violation_best_effort(user, request.url.path, msg)
            return HTMLResponse(content=render_403_alert_html(msg), status_code=403)
        if res.status_code == 200:
            body = res.json()
            flagged = body.get("flaggedCount", 0)
            banner_msg = (
                f"Roll-call completed for {barn_id}: {flagged} missing cattle flagged."
            )
        alert_data = await fetch_alerts_data(
            client, headers, state="OPEN", alert_type="ALL", q=""
        )

    return templates.TemplateResponse(
        "partials/alert_table_content.html",
        {
            "request": request,
            "user": user,
            "alerts": alert_data["alerts"],
            "stats": alert_data["stats"],
            "current_state": "OPEN",
            "current_type": "ALL",
            "q": "",
            "oob_update": True,
            "banner_msg": banner_msg,
        },
    )


@app.post("/htmx/alerts/{alert_id}/acknowledge", response_class=HTMLResponse)
async def htmx_alert_acknowledge(
    request: Request,
    alert_id: str,
    state: str = "OPEN",
    type: str = "ALL",
    user: dict = Depends(get_current_user),
):
    headers = get_auth_headers(user) if user else {}
    async with httpx.AsyncClient(timeout=8.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/alerts/{alert_id}/acknowledge", headers=headers
        )
        if res.status_code == 403:
            err = extract_backend_error(res, f"acknowledge Alert '{alert_id}'")
            msg = format_403_security_message(user or {}, err, request.url.path)
            await log_security_violation_best_effort(user or {}, request.url.path, msg)
            return HTMLResponse(content=render_403_alert_html(msg), status_code=403)
        alert_data = await fetch_alerts_data(
            client, headers, state=state, alert_type=type, q=""
        )

    return templates.TemplateResponse(
        "partials/alert_table_content.html",
        {
            "request": request,
            "user": user,
            "alerts": alert_data["alerts"],
            "stats": alert_data["stats"],
            "current_state": state,
            "current_type": type,
            "q": "",
            "oob_update": True,
        },
    )


@app.post("/htmx/alerts/{alert_id}/resolve", response_class=HTMLResponse)
async def htmx_alert_resolve(
    request: Request,
    alert_id: str,
    note: str = Form("Verified and cleared by operator"),
    state: str = Form("OPEN"),
    type: str = Form("ALL"),
    user: dict = Depends(get_current_user),
):
    headers = get_auth_headers(user) if user else {}
    async with httpx.AsyncClient(timeout=8.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/alerts/{alert_id}/resolve",
            json={"note": note},
            headers=headers,
        )
        if res.status_code == 403:
            err = extract_backend_error(res, f"resolve Alert '{alert_id}'")
            msg = format_403_security_message(user or {}, err, request.url.path)
            await log_security_violation_best_effort(user or {}, request.url.path, msg)
            return HTMLResponse(content=render_403_alert_html(msg), status_code=403)
        alert_data = await fetch_alerts_data(
            client, headers, state=state, alert_type=type, q=""
        )

    return templates.TemplateResponse(
        "partials/alert_table_content.html",
        {
            "request": request,
            "user": user,
            "alerts": alert_data["alerts"],
            "stats": alert_data["stats"],
            "current_state": state,
            "current_type": type,
            "q": "",
            "oob_update": True,
        },
    )


@app.get("/htmx/alerts/badge", response_class=HTMLResponse)
async def htmx_alerts_badge(user: dict = Depends(get_current_user)):
    if not user:
        return ""
    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=3.0) as client:
        data = await fetch_alerts_data(
            client, headers, state="OPEN", alert_type="ALL", q=""
        )
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
        raise HTTPException(
            status_code=403,
            detail="Access denied: Insufficient privileges for disease surveillance.",
        )

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=8.0) as client:
        try:
            res = await client.get(
                f"{BACKEND_BASE_URL}/surveillance/overview", headers=headers
            )
            if res.status_code == 403:
                raise HTTPException(
                    status_code=403,
                    detail=extract_backend_error(res, "access Disease Surveillance"),
                )
            data = res.json() if res.status_code == 200 else {}
        except HTTPException:
            raise
        except Exception:
            data = {}

    return templates.TemplateResponse(
        "surveillance.html",
        {
            "request": request,
            "user": user,
            "metrics": data.get(
                "metrics",
                {
                    "totalHerd": 0,
                    "vaccinatedHerd": 0,
                    "vaccinationCoveragePct": 0,
                    "underTreatment": 0,
                    "quarantined": 0,
                    "totalTransfers": 0,
                },
            ),
            "transmissions": data.get("transmissions", []),
        },
    )


@app.get("/surveillance/export")
async def surveillance_export_proxy(
    report_type: str = "OUTBREAK_SURVEILLANCE",
    farm_id: str = "ALL",
    format: str = "CSV",
    user: dict = Depends(get_current_user),
):
    if not user:
        return RedirectResponse(url="/login")
    if user.get("role") not in ["SYSTEM_ADMIN", "FARM_MANAGER", "EXECUTIVE"]:
        raise HTTPException(
            status_code=403,
            detail="Access denied: Insufficient privileges for surveillance export.",
        )

    async with httpx.AsyncClient(timeout=15.0) as client:
        res = await client.get(
            f"{BACKEND_BASE_URL}/surveillance/export",
            params={"reportType": report_type, "farmId": farm_id, "format": format},
            headers=get_auth_headers(user),
        )
        if res.status_code == 403:
            raise HTTPException(
                status_code=403,
                detail=extract_backend_error(
                    res, f"export surveillance data for '{farm_id}'"
                ),
            )
        content_disp = res.headers.get(
            "Content-Disposition", f'attachment; filename="report.{format.lower()}"'
        )
        media_type = res.headers.get("Content-Type", "application/octet-stream")
        return Response(
            content=res.content,
            media_type=media_type,
            headers={"Content-Disposition": content_disp},
        )


@app.post("/htmx/surveillance/transmit", response_class=HTMLResponse)
async def htmx_surveillance_transmit(
    request: Request,
    target_system: str = Form("National Animal Disease Control System (NADCS)"),
    endpoint_url: str = Form(
        "https://api.nadcs.dah.gov.vn/v2/surveillance/herd-health"
    ),
    report_type: str = Form("OUTBREAK_SURVEILLANCE"),
    farm_scope: str = Form("ALL"),
    payload_format: str = Form("JSON"),
    user: dict = Depends(get_current_user),
):
    if not user:
        return "<div class='p-4 text-red-600 text-xs'>Session expired.</div>"

    headers = get_auth_headers(user)
    banner_msg = ""
    async with httpx.AsyncClient(timeout=10.0) as client:
        tx_res = await client.post(
            f"{BACKEND_BASE_URL}/surveillance/transmit",
            json={
                "targetSystem": target_system,
                "endpointUrl": endpoint_url,
                "reportType": report_type,
                "farmScope": farm_scope,
                "payloadFormat": payload_format,
            },
            headers=headers,
        )
        if tx_res.status_code == 403:
            err = extract_backend_error(
                tx_res, f"transmit surveillance data for Farm '{farm_scope}'"
            )
            msg = format_403_security_message(user, err, request.url.path)
            await log_security_violation_best_effort(user, request.url.path, msg)
            return HTMLResponse(content=render_403_alert_html(msg), status_code=403)
        if tx_res.status_code == 200:
            banner_msg = tx_res.json().get("responseSummary", "Transmission completed.")

        ov_res = await client.get(
            f"{BACKEND_BASE_URL}/surveillance/overview", headers=headers
        )
        transmissions = (
            ov_res.json().get("transmissions", []) if ov_res.status_code == 200 else []
        )

    return templates.TemplateResponse(
        "partials/transmission_rows.html",
        {"request": request, "transmissions": transmissions, "banner_msg": banner_msg},
    )


@app.post("/htmx/barns/{barn_id}/verify-return", response_class=HTMLResponse)
async def htmx_verify_barn_return(
    request: Request, barn_id: str, user: dict = Depends(get_current_user)
):
    headers = get_auth_headers(user) if user else {}
    verify_banner = ""
    async with httpx.AsyncClient(timeout=6.0) as client:
        v_res = await client.post(
            f"{BACKEND_BASE_URL}/barns/{barn_id}/verify-return", headers=headers
        )
        if v_res.status_code == 403:
            err = extract_backend_error(v_res, f"mutate Barn '{barn_id}'")
            msg = format_403_security_message(user or {}, err, request.url.path)
            await log_security_violation_best_effort(user or {}, request.url.path, msg)
            return HTMLResponse(content=render_403_alert_html(msg), status_code=403)
        if v_res.status_code == 200:
            body = v_res.json()
            verify_banner = (
                f"Return check for {barn_id}: {body.get('exitedCount', 0)} left (OUT), "
                f"{body.get('returnedCount', 0)} returned (IN), "
                f"{body.get('newlyFlaggedAlerts', 0)} missing-cattle alert(s) raised."
            )
        res = await client.get(
            f"{BACKEND_BASE_URL}/facilities/distribution", headers=headers
        )
        farms = filter_farms_by_scope(
            res.json() if res.status_code == 200 else [], user
        )

    return templates.TemplateResponse(
        "partials/headcount_cards.html",
        {"request": request, "farms": farms, "verify_banner": verify_banner},
    )


# ================= USER & ROLE ADMINISTRATION =================


@app.get("/users", response_class=HTMLResponse)
async def users_page(
    request: Request,
    error: str = "",
    success: str = "",
    user: dict = Depends(get_current_user),
):
    if not user:
        return RedirectResponse(url="/login")
    if user.get("role") not in ("SYSTEM_ADMIN", "FARM_MANAGER"):
        raise HTTPException(
            status_code=403,
            detail="Access denied: Insufficient privileges for user & role administration.",
        )

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=6.0) as client:
        users_res = await client.get(f"{BACKEND_BASE_URL}/users", headers=headers)
        if users_res.status_code == 403:
            raise HTTPException(
                status_code=403,
                detail=extract_backend_error(
                    users_res, "access User & Role Administration"
                ),
            )
        users_list = users_res.json() if users_res.status_code == 200 else []

        dist_res = await client.get(
            f"{BACKEND_BASE_URL}/facilities/distribution", headers=headers
        )
        farms = filter_farms_by_scope(
            dist_res.json() if dist_res.status_code == 200 else [], user
        )

    return templates.TemplateResponse(
        "users.html",
        {
            "request": request,
            "user": user,
            "users_list": users_list,
            "farms": farms,
            "error": error,
            "success": success,
        },
    )


@app.post("/users/create")
async def create_user_submit(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)
    if user.get("role") not in ("SYSTEM_ADMIN", "FARM_MANAGER"):
        raise HTTPException(
            status_code=403,
            detail="Access denied: Insufficient privileges to create users.",
        )

    form = await request.form()
    payload = {
        "username": form.get("username", ""),
        "fullName": form.get("fullName", ""),
        "password": form.get("password", ""),
        "role": form.get("role", "BARN_SUPERVISOR"),
        "farmId": form.get("farmId", ""),
        "barnIds": form.getlist("barnIds"),
    }

    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.post(
            f"{BACKEND_BASE_URL}/users", json=payload, headers=get_auth_headers(user)
        )

    if res.status_code == 403:
        err_detail = extract_backend_error(
            res, f"create user '{payload['username']}' in Farm '{payload['farmId']}'"
        )
        msg = format_403_security_message(user, err_detail, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(
            url=f"/users?error={quote(msg)}", status_code=status.HTTP_303_SEE_OTHER
        )

    if res.status_code != 200:
        err_msg = extract_backend_error(res, "Failed to create user")
        return RedirectResponse(
            url=f"/users?error={quote(err_msg)}", status_code=status.HTTP_303_SEE_OTHER
        )

    success_msg = f"Created user {payload['username']} with role {payload['role']}"
    return RedirectResponse(
        url=f"/users?success={quote(success_msg)}",
        status_code=status.HTTP_303_SEE_OTHER,
    )


@app.post("/users/{target_username}/update-role")
async def update_user_role_submit(
    target_username: str, request: Request, user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)
    if user.get("role") not in ("SYSTEM_ADMIN", "FARM_MANAGER"):
        raise HTTPException(
            status_code=403,
            detail="Access denied: Insufficient privileges to grant roles.",
        )

    form = await request.form()
    new_role = form.get("role", "BARN_SUPERVISOR")
    farm_id = form.get("farmId", "")

    barn_ids = []
    if new_role == "BARN_SUPERVISOR" and farm_id:
        async with httpx.AsyncClient(timeout=6.0) as client:
            dist_res = await client.get(
                f"{BACKEND_BASE_URL}/facilities/distribution",
                headers=get_auth_headers(user),
            )
            farms = filter_farms_by_scope(
                dist_res.json() if dist_res.status_code == 200 else [], user
            )
            for f in farms:
                if f.get("farmId") == farm_id and f.get("barns"):
                    barn_ids = [f["barns"][0]["barnId"]]
                    break

    async with httpx.AsyncClient(timeout=6.0) as client:
        res = await client.put(
            f"{BACKEND_BASE_URL}/users/{target_username}/role-scope",
            json={"role": new_role, "farmId": farm_id, "barnIds": barn_ids},
            headers=get_auth_headers(user),
        )

    if res.status_code == 403:
        err_detail = extract_backend_error(
            res, f"update role for '{target_username}' in Farm '{farm_id}'"
        )
        msg = format_403_security_message(user, err_detail, request.url.path)
        await log_security_violation_best_effort(user, request.url.path, msg)
        return RedirectResponse(
            url=f"/users?error={quote(msg)}", status_code=status.HTTP_303_SEE_OTHER
        )

    if res.status_code != 200:
        err_msg = extract_backend_error(res, "Failed to update role")
        return RedirectResponse(
            url=f"/users?error={quote(err_msg)}", status_code=status.HTTP_303_SEE_OTHER
        )

    success_msg = f"Updated {target_username} to {new_role}"
    return RedirectResponse(
        url=f"/users?success={quote(success_msg)}",
        status_code=status.HTTP_303_SEE_OTHER,
    )
