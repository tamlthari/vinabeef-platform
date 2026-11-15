from datetime import date

from fastapi import FastAPI, Request, Response, Form, Depends, HTTPException, status
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
import httpx
import json
import math

app = FastAPI(title="VinaBeef Enterprise Portal")
app.mount("/static", StaticFiles(directory="static"), name="static")
templates = Jinja2Templates(directory="templates")

BACKEND_BASE_URL = "http://localhost:8080/api/v1"

# --- Herd Count & Display ---

def build_page_range(current_page: int, total_pages: int) -> list:
    if total_pages <= 7:
        return list(range(1, total_pages + 1))
    if current_page <= 4:
        return [1, 2, 3, 4, 5, "...", total_pages]
    if current_page >= total_pages - 3:
        return [1, "...", total_pages - 4, total_pages - 3, total_pages - 2, total_pages - 1, total_pages]
    return [1, "...", current_page - 1, current_page, current_page + 1, "...", total_pages]

async def fetch_paginated_cattle(client: httpx.AsyncClient, headers: dict, q: str = "", page: int = 1, size: int = 100) -> dict:
    page = max(1, page)
    size = 100
    try:
        res = await client.get(
            f"{BACKEND_BASE_URL}/cattle",
            params={"q": q, "page": page, "size": size},
            headers=headers
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
        "q": q
    }

# --- Authentication & Session Helper ---

def get_current_user(request: Request):
    token = request.cookies.get("vinabeef_token")
    role = request.cookies.get("vinabeef_role")
    username = request.cookies.get("vinabeef_user")
    raw_scopes = request.cookies.get("vinabeef_scopes", "[]")
    
    try:
        farm_scopes = json.loads(raw_scopes)
    except Exception:
        farm_scopes = []

    if not token or not role or not username:
        return None

    return {
        "token": token,
        "username": username,
        "role": role,
        "farm_scopes": farm_scopes
    }

def get_auth_headers(user: dict = Depends(get_current_user)) -> dict:
    if not user:
        return {}
    return {"Authorization": f"Bearer {user['token']}"}

# --- Page Routes ---

@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request):
    user = get_current_user(request)
    if user:
        return RedirectResponse(url="/", status_code=status.HTTP_302_FOUND)
    return templates.TemplateResponse("login.html", {"request": request, "error": None})

@app.post("/login")
async def login_action(
    request: Request,
    response: Response,
    username: str = Form(...),
    password: str = Form(...)
):
    async with httpx.AsyncClient(timeout=5.0) as client:
        try:
            res = await client.post(
                f"{BACKEND_BASE_URL}/auth/login",
                json={"username": username, "password": password}
            )
            if res.status_code == 200:
                data = res.json()
                redirect = RedirectResponse(url="/", status_code=status.HTTP_302_FOUND)
                redirect.set_cookie("vinabeef_token", data["token"], httponly=True, max_age=86400)
                redirect.set_cookie("vinabeef_role", data["role"], max_age=86400)
                redirect.set_cookie("vinabeef_user", data["username"], max_age=86400)
                redirect.set_cookie("vinabeef_scopes", json.dumps(data.get("farmScopes", [])), max_age=86400)
                return redirect
            else:
                return templates.TemplateResponse(
                    "login.html",
                    {"request": request, "error": "Invalid username or password"}
                )
        except Exception as e:
            return templates.TemplateResponse(
                "login.html",
                {"request": request, "error": f"Backend connection error: {str(e)}"}
            )

@app.get("/logout")
async def logout_action():
    redirect = RedirectResponse(url="/login", status_code=status.HTTP_302_FOUND)
    redirect.delete_cookie("vinabeef_token")
    redirect.delete_cookie("vinabeef_role")
    redirect.delete_cookie("vinabeef_user")
    redirect.delete_cookie("vinabeef_scopes")
    return redirect

# 1. Live Gate Monitor (Home)
@app.get("/", response_class=HTMLResponse)
async def home_page(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=3.0) as client:
        dashboard_error = None
        try:
            farms_res = await client.get(f"{BACKEND_BASE_URL}/facilities/distribution", headers=headers)
            farms_res.raise_for_status()
            farms = farms_res.json()
        except (httpx.HTTPError, ValueError) as exc:
            farms = []
            dashboard_error = str(exc)

        try:
            movements = (await client.get(f"{BACKEND_BASE_URL}/movements/recent", headers=headers)).json()
        except Exception:
            movements = []

        try:
            status_res = await client.get(f"{BACKEND_BASE_URL}/ingestion/failover-status", headers=headers)
            status_data = status_res.json() if status_res.status_code == 200 else {}
        except Exception:
            status_data = {
                "activeIngressRoute": "DIRECT_MQTT",
                "directMqttConnected": False,
                "disconnectReason": "Connecting to backend...",
                "recentAvgLatencyMs": 0.0,
                "latencyBreached": False
            }

    return templates.TemplateResponse("index.html", {
        "request": request,
        "user": user,
        "farms": farms,
        "dashboard_error": dashboard_error,
        "movements": movements,
        "status": status_data,
        "current_date": date.today()
    })


# 2. Audit Trail Viewer (Restricted to SYSTEM_ADMIN)
@app.get("/audit", response_class=HTMLResponse)
async def audit_page(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")
    if user["role"] != "SYSTEM_ADMIN":
        raise HTTPException(status_code=403, detail="Access denied: SYSTEM_ADMIN role required.")

    return templates.TemplateResponse("audit.html", {"request": request, "user": user})

# 3. Reports & Analytics (Restricted to EXECUTIVE and FARM_MANAGER)
@app.get("/reports", response_class=HTMLResponse)
async def reports_page(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")
    if user["role"] not in ["EXECUTIVE", "FARM_MANAGER", "SYSTEM_ADMIN"]:
        raise HTTPException(status_code=403, detail="Access denied: Insufficient privileges for reporting.")

    return templates.TemplateResponse("reports.html", {"request": request, "user": user})

# 4. Cattle Transfer Workbench (Demonstrating Farm-Scoped RBAC)
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
            farms = res.json()
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

@app.get("/htmx/audit-table", response_class=HTMLResponse)
async def htmx_audit_table(request: Request, user: dict = Depends(get_current_user)):
    if not user or user["role"] != "SYSTEM_ADMIN":
        return "<div class='text-rose-400 p-4'>Unauthorized</div>"

    async with httpx.AsyncClient(timeout=3.0) as client:
        try:
            res = await client.get(f"{BACKEND_BASE_URL}/management/audit-logs", headers=get_auth_headers(user))
            logs = res.json() if res.status_code == 200 else []
        except Exception:
            logs = []

    return templates.TemplateResponse("partials/audit_table.html", {"request": request, "logs": logs})

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

@app.get("/htmx/report-data", response_class=HTMLResponse)
async def htmx_report_data(request: Request, farm_id: str = "ALL", user: dict = Depends(get_current_user)):
    if not user:
        return "<div class='text-rose-400 p-4'>Unauthorized</div>"

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=4.0) as client:
        if farm_id == "ALL":
            try:
                res = await client.get(f"{BACKEND_BASE_URL}/management/executive/summary", headers=headers)
                data = res.json() if res.status_code == 200 else []
                is_summary = True
            except Exception:
                data, is_summary = [], True
        else:
            try:
                res = await client.get(f"{BACKEND_BASE_URL}/management/farms/{farm_id}/report", headers=headers)
                data = res.json().get("barnData", []) if res.status_code == 200 else []
                is_summary = False
            except Exception:
                data, is_summary = [], False

        # Query recent movements to populate the Screenshot 60 report table
        try:
            mov_res = await client.get(f"{BACKEND_BASE_URL}/movements/recent", headers=headers)
            recent_movs = mov_res.json() if mov_res.status_code == 200 else []
        except Exception:
            recent_movs = []

    # Map recent passages to match Screenshot 60 columns
    report_logs = []
    if recent_movs:
        for m in recent_movs:
            report_logs.append({
                "date_time": str(m.get("occurredAt", "")).replace("T", " ")[:19],
                "person": m.get("animalId", "CI 356278"),
                "sensor_name": "DrEBE6" if "GATE-DL" in m.get("gateId", "") else "DrC1C4",
                "device_access": "RFID Ear Tag",
                "sensor_description": "North Gate Barn 01" if "01A" in m.get("gateId", "") else "South Feeding Gate",
                "event": f"Movement {m.get('direction', 'IN')}",
                "result": "Success"
            })
    else:
        # Default representative rows matching the Safetrust layout
        report_logs = [
            {"date_time": "10/17/2026 03:01:28", "person": "CI 356278", "sensor_name": "DrEBE6", "device_access": "RFID Ear Tag", "sensor_description": "North Pasture Gate", "event": "Movement OUT", "result": "Success"},
            {"date_time": "10/17/2026 02:55:12", "person": "COW-0002", "sensor_name": "DrEBE6", "device_access": "RFID Ear Tag", "sensor_description": "North Pasture Gate", "event": "Movement IN", "result": "Success"},
            {"date_time": "10/17/2026 02:40:05", "person": "COW-0003", "sensor_name": "DrEBE7", "device_access": "RFID Ear Tag", "sensor_description": "South Feeding Gate", "event": "Movement OUT", "result": "Success"},
            {"date_time": "10/17/2026 02:15:30", "person": "an.nguyen@safetrust.com", "sensor_name": "DrC1C4", "device_access": "Phone", "sensor_description": "Door C1C4", "event": "Authentication", "result": "Success"}
        ]

    return templates.TemplateResponse("partials/report_content.html", {
        "request": request,
        "farm_id": farm_id,
        "data": data,
        "logs": report_logs,
        "is_summary": is_summary
    })

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
                <div class="p-4 bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 rounded-xl">
                    <strong>Mutation Approved:</strong> Cattle {data.get('animalId')} transfer registered in {data.get('farmId')} by {data.get('approvedBy')}.
                </div>
                """
            elif res.status_code == 403:
                return f"""
                <div class="p-4 bg-rose-500/10 border border-rose-500/20 text-rose-400 rounded-xl">
                    <strong>Access Denied (403):</strong> User '{user['username']}' is not authorized to mutate Farm '{farm_id}'. Security violation logged in Audit ledger.
                </div>
                """
            else:
                return f"""
                <div class="p-4 bg-amber-500/10 border border-amber-500/20 text-amber-400 rounded-xl">
                    <strong>Backend Error ({res.status_code}):</strong> {res.text}
                </div>
                """
        except Exception as e:
            return f"""
            <div class="p-4 bg-rose-500/10 border border-rose-500/20 text-rose-400 rounded-xl">
                <strong>Failure:</strong> {str(e)}
            </div>
            """

# ================= CATTLE & RFID TAG MANAGEMENT =================

@app.get("/herd", response_class=HTMLResponse)
async def herd_page(request: Request, page: int = 1, q: str = "", user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=10.0) as client:
        pagination = await fetch_paginated_cattle(client, headers, q=q, page=page, size=100)
        try:
            barns_res = await client.get(f"{BACKEND_BASE_URL}/barns", headers=headers)
            barns = barns_res.json() if barns_res.status_code == 200 else []
        except Exception:
            barns = []

    return templates.TemplateResponse("herd.html", {
        "request": request,
        "user": user,
        "barns": barns,
        **pagination
    })

@app.get("/htmx/herd-list", response_class=HTMLResponse)
async def htmx_herd_list(request: Request, page: int = 1, q: str = "", user: dict = Depends(get_current_user)):
    headers = get_auth_headers(user) if user else {}
    async with httpx.AsyncClient(timeout=10.0) as client:
        pagination = await fetch_paginated_cattle(client, headers, q=q, page=page, size=100)

    return templates.TemplateResponse("partials/herd_table_content.html", {
        "request": request,
        "oob_update": True,
        **pagination
    })

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
        if res.status_code != 200:
            err = res.json().get("error", "Failed to register cattle")
            return f"<div class='p-4 rounded bg-red-50 border border-red-200 text-red-700 text-xs'>{err}</div>"

        pagination = await fetch_paginated_cattle(client, headers, q="", page=1, size=100)

    return templates.TemplateResponse("partials/herd_table_content.html", {
        "request": request,
        "oob_update": True,
        **pagination
    })

@app.get("/herd/{animal_id}", response_class=HTMLResponse)
async def cattle_detail_page(request: Request, animal_id: str, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.get(f"{BACKEND_BASE_URL}/cattle/{animal_id}", headers=headers)
        if res.status_code != 200:
            raise HTTPException(status_code=404, detail="Animal not found")
        detail = res.json()

    return templates.TemplateResponse("cattle_detail.html", {
        "request": request,
        "user": user,
        "animal": detail["profile"],
        "tags": detail["tags"],
        "vaccinations": detail["vaccinations"],
        "movements": detail["movements"]
    })

@app.post("/herd/{animal_id}/assign-tag")
async def cattle_assign_tag(
    animal_id: str,
    tag_epc: str = Form(...),
    credential_identifier: str = Form("EPC-26"),
    user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=4.0) as client:
        await client.post(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}/tags",
            json={"tagEpc": tag_epc, "credentialIdentifier": credential_identifier},
            headers=get_auth_headers(user)
        )
    return RedirectResponse(url=f"/herd/{animal_id}", status_code=status.HTTP_303_SEE_OTHER)

@app.post("/herd/{animal_id}/vaccinate")
async def cattle_record_vaccination(
    animal_id: str,
    vaccine_name: str = Form(...),
    batch_number: str = Form(...),
    notes: str = Form(""),
    user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=4.0) as client:
        await client.post(
            f"{BACKEND_BASE_URL}/cattle/{animal_id}/vaccinations",
            json={"vaccineName": vaccine_name, "batchNumber": batch_number, "notes": notes},
            headers=get_auth_headers(user)
        )
    return RedirectResponse(url=f"/herd/{animal_id}", status_code=status.HTTP_303_SEE_OTHER)


# ================= GATE READER MANAGEMENT =================

@app.get("/readers", response_class=HTMLResponse)
async def readers_page(request: Request, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=4.0) as client:
        try:
            res = await client.get(f"{BACKEND_BASE_URL}/readers", headers=headers)
            if res.status_code in (401, 403):
                return RedirectResponse(url="/login")
            readers = res.json() if res.status_code == 200 else []
        except Exception:
            readers = []

        try:
            barns_res = await client.get(f"{BACKEND_BASE_URL}/barns", headers=headers)
            barns = barns_res.json() if barns_res.status_code == 200 else []
        except Exception:
            barns = []

    return templates.TemplateResponse("readers.html", {
        "request": request,
        "user": user,
        "readers": readers,
        "barns": barns
    })

@app.get("/htmx/readers-list", response_class=HTMLResponse)
async def htmx_readers_list(request: Request, q: str = "", user: dict = Depends(get_current_user)):
    headers = get_auth_headers(user) if user else {}
    async with httpx.AsyncClient(timeout=3.0) as client:
        try:
            res = await client.get(f"{BACKEND_BASE_URL}/readers", params={"q": q}, headers=headers)
            readers = res.json() if res.status_code == 200 else []
        except Exception:
            readers = []
    return templates.TemplateResponse("partials/reader_rows.html", {"request": request, "readers": readers})

@app.post("/htmx/readers/create", response_class=HTMLResponse)
async def htmx_readers_create(
    request: Request,
    reader_name: str = Form(...),
    label: str = Form(...),
    barn_id: str = Form(...),
    direction_mode: str = Form("BIDIRECTIONAL"),
    firmware_version: str = Form("1.58.1998"),
    user: dict = Depends(get_current_user)
):
    if not user:
        return ""

    async with httpx.AsyncClient(timeout=4.0) as client:
        await client.post(
            f"{BACKEND_BASE_URL}/readers",
            json={
                "readerName": reader_name,
                "label": label,
                "barnId": barn_id,
                "directionMode": direction_mode,
                "firmwareVersion": firmware_version
            },
            headers=get_auth_headers(user)
        )
        readers = (await client.get(f"{BACKEND_BASE_URL}/readers", headers=get_auth_headers(user))).json()
    return templates.TemplateResponse("partials/reader_rows.html", {"request": request, "readers": readers})

@app.get("/readers/{gate_id}", response_class=HTMLResponse)
async def reader_detail_page(request: Request, gate_id: str, user: dict = Depends(get_current_user)):
    if not user:
        return RedirectResponse(url="/login")

    headers = get_auth_headers(user)
    async with httpx.AsyncClient(timeout=4.0) as client:
        res = await client.get(f"{BACKEND_BASE_URL}/readers/{gate_id}", headers=headers)
        if res.status_code != 200:
            raise HTTPException(status_code=404, detail="Gate reader not found")
        detail = res.json()
        barns = (await client.get(f"{BACKEND_BASE_URL}/barns", headers=headers)).json()

    return templates.TemplateResponse("reader_detail.html", {
        "request": request,
        "user": user,
        "reader": detail["reader"],
        "transactions": detail["transactions"],
        "barns": barns
    })

@app.post("/readers/{gate_id}/update")
async def reader_update_action(
    gate_id: str,
    barn_id: str = Form(...),
    label: str = Form(...),
    direction_mode: str = Form(...),
    firmware_version: str = Form("1.58.1998"),
    user: dict = Depends(get_current_user)
):
    if not user:
        return RedirectResponse(url="/login")

    async with httpx.AsyncClient(timeout=4.0) as client:
        await client.put(
            f"{BACKEND_BASE_URL}/readers/{gate_id}",
            json={
                "barnId": barn_id,
                "label": label,
                "directionMode": direction_mode,
                "firmwareVersion": firmware_version
            },
            headers=get_auth_headers(user)
        )
    return RedirectResponse(url=f"/readers/{gate_id}", status_code=status.HTTP_303_SEE_OTHER)