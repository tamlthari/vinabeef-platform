# Quick Container spin-up

## Cloud server application hosted location

The VinaBeef platform is publicly available for a limited duration before February 1, 2027 at `tamholthucwzr.men`.

## 1. Prerequisites by Operating System

Ensure **Git** and **Docker Compose v2** (`docker compose`) are installed before cloning the repository.

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-v2 git
sudo usermod -aG docker $USER && newgrp docker

```

- **Windows (10/11):** Enable WSL 2 (`wsl --install` in an Administrator PowerShell, followed by a reboot), install [Git for Windows](https://git-scm.com/download/win), and install [Docker Desktop for Windows](https://www.docker.com/products/docker-desktop/) with **Use the WSL 2 based engine** checked in Settings.

---

## 2. Clone the Repository (Line-Ending Safe)

Clone `vinabeef-platform` with `core.autocrlf=input` so Windows does not convert `backend/mvnw` to `CRLF` line endings (which causes `./mvnw: not found` inside the Linux container build):

```bash
git clone --config core.autocrlf=input https://github.com/tamlthari/vinabeef-platform.git
cd vinabeef-platform

```

---

## 3. Configure Local Overrides & MQTT Client Suffix

On the production EC2 host, **Caddy** acts as the sole public entry point (`80`/`443`), while `frontend` (`3000`) and `backend` (`8080`) stay on the private Docker bridge network. In addition, two backend instances connecting to Safetrust's MQTT broker with the same client ID (`Web_Direct_728589`) will disconnect each other.

Create a `docker-compose.override.yml` file in the repository root to expose ports `3000` and `8080` on `localhost` and assign a unique local MQTT client suffix:

```yaml
services:
  backend:
    ports:
      - "8080:8080"
    environment:
      VINABEEF_INGESTION_SAFETRUST_CLIENT_ID_SUFFIX: "local_dev"

  frontend:
    ports:
      - "3000:3000"
```

_(Because `docker-compose.override.yml` is automatically merged by `docker compose`, you do not need to modify the production `docker-compose.yml`.)_

---

## 4. Build and Start the Core Containers

Start PostgreSQL, Redis, the Spring Boot API, and the FastAPI portal (omitting `caddy` locally so it does not attempt public TLS certificate issuance):

```bash
docker compose up -d --build postgres redis backend frontend

```

Monitor the backend startup until Flyway finishes applying schema migrations `V1` through `V15` and Spring Boot started FarmManagementApplication:

```bash
docker logs -f vinabeef-backend

```

Press `Ctrl + C` once startup completes.

---

## 5. Seed the Database (25,000 Cattle, 120 Barns, 125 Readers)

Import `scripts/seed_25k_cattle.sql` into PostgreSQL, then restart `vinabeef-backend` so `RbacScopeService` links the demo supervisor accounts to the seeded barns[cite: 3, 8]:

- **macOS & Linux (Bash / Zsh):**

```bash
docker exec -i vinabeef-postgres psql -U vinabeef_user -d vinabeef_db < scripts/seed_25k_cattle.sql
docker restart vinabeef-backend

```

- **Windows (PowerShell):**

```powershell
Get-Content -Raw scripts\seed_25k_cattle.sql | docker exec -i vinabeef-postgres psql -U vinabeef_user -d vinabeef_db
docker restart vinabeef-backend

```

---

## 6. (Optional) Run the Barn Edge Gateway Container

To test store-and-forward edge buffering locally on port `8090`, build and run `edge_gateway/Dockerfile` pointing at your local backend:

```bash
cd edge_gateway
cp edge.env.example .env
# Edit .env: set CLOUD_BASE_URL=http://host.docker.internal:8080/api/v1 and MQTT_CLIENT_ID=Web_Edge_local
docker build -t vinabeef-edge .
docker run -d --name vinabeef-edge --env-file .env -v edge-data:/data -p 8090:8090 vinabeef-edge

```

Verify the gateway health and SQLite outbox queue status at `http://localhost:8090/status`.

---

## 7. Access the Local Portal & Demo Accounts

Open **`http://localhost:3000/login`** (FastAPI portal) or query **`http://localhost:8080/api/v1`** (Spring Boot REST API) using any of the pre-seeded accounts (password for all accounts is **`password123`**):

| Username                       | Role              | Facility / Barn Scope                                                                   |
| ------------------------------ | ----------------- | --------------------------------------------------------------------------------------- |
| **`admin`**                    | `SYSTEM_ADMIN`    | Global access across all 3 farms, 120 barns, 125 readers, and 25,000 cattle[cite: 3, 8] |
| **`manager_daklak`**           | `FARM_MANAGER`    | Dak Lak Cattle Station (`FARM-DAK-LAK` — 40 barns)                                      |
| **`manager_gialai`**           | `FARM_MANAGER`    | Gia Lai Grazing Facility (`FARM-GIA-LAI` — 40 barns)                                    |
| **`manager_dongnai`**          | `FARM_MANAGER`    | Dong Nai Feedlot (`FARM-DONG-NAI` — 40 barns)                                           |
| **`supervisor_daklak`**        | `BARN_SUPERVISOR` | Scoped to 3 assigned barns in `FARM-DAK-LAK`                                            |
| **`supervisor_gialai_2barns`** | `BARN_SUPERVISOR` | Scoped to 2 assigned barns in `FARM-GIA-LAI`                                            |
| **`executive`**                | `EXECUTIVE`       | Global read-only analytics and regulatory reporting                                     |
