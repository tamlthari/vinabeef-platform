```
+-------------------------------------------------+
                     |              REMOTE FARMS (x3)                  |
                     |  - Dak Lak   - Gia Lai   - Dong Nai             |
                     |  (120 Barns, ~25,000 Cattle)                    |
                     +-------------------------------------------------+
                                     |                     |
                   [RFID Gates: EPC Gen 2]        [Farm Browser Clients]
                                     |                     |
                      MQTT / TLS (QoS 1)                   | HTTPS
                                     v                     |
                     +-------------------------------+     |
                     | Central MQTT Broker (EMQX)    |     |
                     +-------------------------------+     |
                                     |                     |
                       Spring Integration MQTT             |
                                     v                     v
+-----------------------------------------------------------------------------------+
|                        CENTRAL BACKEND (SPRING BOOT 3.x)                          |
|                                                                                   |
|  [ Ingress & Edge Adapter ]       [ Web MVC & Security ]    [ WebSocket Broker ] |
|  - MQTT Inbound Channel Adapter   - Spring Security 6       - STOMP Alerts / Live |
|  - Raw Ingress Validator          - JWT & Farm-Scoped RBAC    Movement Feed       |
|                                                                                   |
|  [ Core Modular Monolith Domain Services ]                                        |
|  - Ingestion & Dedup Engine       - Movement & Presence Engine                    |
|  - Cattle & Tag Registry          - Anomaly & Alert Engine                        |
|  - Facilities & Gate Mapping      - Health & Vaccination Ledger                   |
|  - Audit Log Interceptor          - External Disease API Adapter (Resilience4j)   |
+-----------------------------------------------------------------------------------+
             |                               |                         |
   JDBC / Transactional Path        Cache / Dedup Buffer        Cold Storage / Export
             v                               v                         v
+------------------------+      +-----------------------+     +--------------------+
| Relational DB (RDS)    |      | In-Memory (Redis)     |     | Object Store (S3)  |
| PostgreSQL 16          |      | - Fast Dedup Window   |     | - Report Artifacts |
| - System of Record     |      | - Active Barn Headct  |     | - Audit Archives   |
| - ACID Movements       |      | - Real-time Presence  |     | - Disease Batch    |
| - Audit & Health Data  |      +-----------------------+     |   Export Payloads  |
+------------------------+                                    +--------------------+
                                     ^
                                     | REST API + WebSocket
                                     |
+-----------------------------------------------------------------------------------+
|                       FRONTEND TIER (PYTHON WEB PLATFORM)                         |
|                                                                                   |
|  - Technology: Python (Streamlit / Plotly Dash or Reflex / FastAPI + HTMX)        |
|  - Gate Operations & Live Feed: Real-time In/Out movements & headcounts           |
|  - Discrepancy Desk: Missing & Unexpected cattle alert reviews                    |
|  - Veterinary & Herd Master: Cattle registration, vaccinations, medical history   |
|  - Executive & Compliance: Multi-farm reports, audit queries, external API sync   |
+-----------------------------------------------------------------------------------+
```
Each barn gate houses an RFID reader station equipped with an MQTT client (over TLS) transmitting reads back to an enterprise MQTT broker (e.g., EMQX or Mosquitto).   
#### Topic Taxonomy
```
Upstream Reads (Gate to Cloud): vinabeef/farms/{farmId}/barns/{barnId}/gates/{gateId}/rfid/reads
```
```
Heartbeat / Health: vinabeef/farms/{farmId}/gates/{gateId}/status
```
```
Downstream Commands / Acknowledgment: vinabeef/farms/{farmId}/gates/{gateId}/ack
```
#### Performance Latency
Ingestion Pipeline & The 2-Second Latency BudgetTo guarantee Quality Scenario QA-01 ($T_{gate} \le 2\text{ s}$ under peak passage bursts):   $$T_{\text{gate}} = T_{\text{transport}} + T_{\text{validate}} + T_{\text{dedup}} + T_{\text{persist}} + T_{\text{ack}} \le 2.0\text{ s}$$
```
src/main/java/com/vinabeef/
├── ingestion/          // MQTT Adapters, Ingress DTOs, Payload Validators
├── cattle/             // Animal aggregate, Tag Assignment, Lifecycle status
├── facilities/         // Farm, Barn, Gate, and Reader Station mappings[cite: 26]
├── movement/           // Movement validation, Presence projection, Deduplication[cite: 26]
├── alerts/             // Missing animal & Unexpected tag evaluation engines[cite: 26]
├── health/             // Vaccination & Clinical treatment immutable ledger[cite: 26]
├── audit/              // AOP Audit logging, Tamper-evident trail[cite: 26]
├── integration/        // Outbound adapter for National Disease Control System[cite: 26]
├── reporting/          // Aggregated statistics, S3 document exporter[cite: 26]
└── security/           // Spring Security 6, JWT filters, Farm-scoped RBAC[cite: 26, 31]
```
Module Responsibilities & Core Patterns
Ingestion Module: Uses spring-integration-mqtt to ingest events asynchronously. Validates reader registrations against active gate inventories.Movement & Presence Engine: Implements domain state machines for animal movements[cite: 26]. Updates presence projections in real-time[cite: 26].Alert & Anomaly Engine:
Unexpected Animal: Triggers immediately if an unregistered tag is detected or if an animal appears at a barn different from its assigned barn without an authorized transfer.   
Missing Animal: Runs on a scheduled window (e.g., end of grazing session) using Spring 
@Scheduled. 
Computes:   $$\text{Missing Set} = \text{Animals}_{\text{Exited}} \setminus \left(\text{Animals}_{\text{Returned}} \cup \text{Animals}_{\text{Transferred}}\right)$$   
Security & Farm-Scoped RBAC:Enforces authorization using Spring Security @PreAuthorize combined with custom evaluation expressions:   
```bash
Java@PreAuthorize("hasRole('FARM_MANAGER') and @securityService.isFarmAssigned(authentication, #farmId)")
public void approveAnimalTransfer(UUID farmId, TransferRequest request) { ... }
```
Roles defined: SYSTEM_ADMIN, FARM_MANAGER, BARN_SUPERVISOR, EXECUTIVE.   
Audit Logging Engine: Spring AOP aspect (@Around("@annotation(Audited)")) intercepts sensitive commands (cattle registration, gate configuration changes, manual presence overrides, vaccination entries). Emits immutable records to audit_entries capturing actor, target entity, pre/post state hash, timestamp, and client IP.   
External Disease System Adapter: Implements Resilience4j Circuit Breaker and Retry policies. Transmits encrypted health/outbreak JSON data to government endpoints; failures are stored in a retry queue in PostgreSQL to prevent data loss or domain stalling.  
```
vinabeef-ui/
├── core/
│   ├── api_client.py         # HTTP client with JWT handling & automatic refresh
│   ├── websocket_client.py   # STOMP / WebSocket listener for live gate alerts
│   └── auth.py               # Role-based view guards & session state
├── pages/
│   ├── 1_Live_Gate_Monitor.py    # Real-time movement counters, gate status, live feed
│   ├── 2_Alerts_Discrepancies.py # Missing animal review desk & wrong-barn triage
│   ├── 3_Herd_Management.py      # Cattle profiles, RFID association, transfers
│   ├── 4_Veterinary_Ledger.py    # Vaccination logging, treatment records
│   ├── 5_Reports_Analytics.py    # Barn balance sheets, export PDF/Excel from S3
│   └── 6_Admin_Settings.py       # Reader-to-gate mapping, user roles & audit view
└── app.py                    # Entry point & navigation routing
```
Physical Data Schema (PostgreSQL Core)
```
farms (farm_id PK, name, location, status)   barns (barn_id PK, farm_id FK, code, capacity, current_headcount)   gates (gate_id PK, barn_id FK, label, direction_mode)   reader_stations (reader_id PK, gate_id FK, mqtt_client_id, status, last_ping)   animals (animal_id PK, assigned_barn_id FK, lifecycle_status, registered_at)   rfid_tags (tag_id PK, epc_value UNIQUE, tag_status)   tag_assignments (assignment_id PK, animal_id FK, tag_id FK, valid_from, valid_to)   rfid_read_events (event_id PK, reader_id FK, tag_value, observed_at, source_key UNIQUE, created_at)   movements (movement_id PK, animal_id FK, gate_id FK, source_event_id FK, direction, occurred_at)   presence_states (animal_id PK/FK, current_barn_id FK, presence_status [IN_BARN, OUT_PASTURE, UNCERTAIN], last_movement_id FK, updated_at)   health_records (record_id PK, animal_id FK, record_type, payload JSONB, administered_by, occurred_at)   alerts (alert_id PK, type [MISSING, UNEXPECTED_TAG, WRONG_BARN], animal_id FK nullable, barn_id FK, state, evidence JSONB, raised_at)   audit_entries (audit_id PK, actor_id, actor_role, action, entity_type, entity_id, details JSONB, timestamp) 
```
Deployment Topology on AWS Kubernetes (EKS)

With a Modular Monolith backend and a Python frontend, your AWS architecture remains straightforward, reliable, and cost-effective:
```

                                [ AWS Cloud ]
                                      |
                           [ AWS Network Load Balancer ]
                                      |
                     +----------------+----------------+
                     | (TLS Port 8883)                 | (HTTPS Port 443)
                     v                                 v
         [ EMQX Cluster (EKS) ]           [ AWS ALB Ingress Controller ]
                     |                                 |
         (MQTT Internal Ingress)              +--------+--------+
                     |                        |                 |
                     v                        v                 v
      +-----------------------------+  +-------------+   +--------------+
      |  Spring Boot Backend Pods   |  | Python Web  |   | Python Web   |
      |  (Modular Monolith Replica) |  | Client Pods |   | Client Pods  |
      |  - Ingestion & Movement     |  | (Farm Node) |   | (Exec Node)  |
      |  - Health & Administration  |  +-------------+   +--------------+
      +-----------------------------+
                     |
         +-----------+-----------+
         |                       |
         v                       v
  [ Amazon RDS ]          [ Amazon ElastiCache ]       [ Amazon S3 ]
  (PostgreSQL 16)         (Redis - Dedup Cache)        (Audit & Report Bucket)
```
#### MQTT Java integration and Python failover
```
[Safetrust IoT Broker]
                           (sensor.demo.safetrust.com)
                                   |         |
                  Primary Path     |         |  Hot-Standby Path
         (Direct In-Process MQTT)  |         |  (Always Subscribed)
                                   v         v
                     +--------------------+   +---------------------------+
                     | Spring Boot Direct |   | Python Adapter            |
                     | MQTT Ingestion     |   | (multi_webs_sub_mqtt)     |
                     +--------------------+   +---------------------------+
                               |                            |
                     (Latency & Heartbeat)                  | Health Polling (1s)
                               v                            v
                     +--------------------+   Failover?     |
                     | IngestionHealth    | <---------------+ (GET /failover-status)
                     | Service            |                 |
                     +--------------------+                 | Failover Triggered:
                               |                            | (Direct Down or >500ms)
                               |                            v
                               |              +---------------------------+
                               +------------> | POST /safetrust-event     |
                                              +---------------------------+
                                                            |
                                                            v
                                              +---------------------------+
                                              | SafetrustProcessingService|
                                              | - Redis Dedup (3s window) |
                                              | - Postgres UNIQUE(event)  |
                                              +---------------------------+
```

#### Offline and Mobile processing
```
+-----------------------------------------------------------------------------------+
|                        CENTRAL CLOUD / HEADQUARTERS CORE                          |
|                                                                                   |
|  +-----------------------+     +-----------------------------------------------+  |
|  | FastAPI + HTMX Portal |     |          Spring Boot 4.1 Backend              |  |
|  | (Admin, Ops & NADCS)  |<--->|  - REST & Mobile Sync API (/api/v1/mobile/*)  |  |
|  +-----------------------+     |  - Batch Ingestion API (/api/v1/ingestion/*)  |  |
|                                |  - SafetrustProcessingService (Idempotent)    |  |
|                                |  - RBAC Security & AOP Audit Logger           |  |
|                                +-----------------------+-----------------------+  |
|                                                        |                          |
|                                        +---------------+---------------+          |
|                                        |                               |          |
|                                +-------v-------+               +-------v-------+  |
|                                |  PostgreSQL   |               |     Redis     |  |
|                                | (25k Herd DB) |               | (Cloud Dedup) |  |
|                                +---------------+               +---------------+  |
+----------------------------------------^-------------------------------^----------+
                                         |                               |
               WAN Sync (HTTPS Batch /   |                               | Cellular / Barn Wi-Fi
               MQTT QoS 1 Replay)        |                               | (Offline Outbox Sync)
+----------------------------------------+----+   +----------------------+----------+
|     BARN SITE: EDGE GATEWAY NODE            |   |   FIELD OPERATIONS: MOBILE APP  |
|  (Industrial Mini-PC / Local Docker Host)   |   |  (Supervisor Tablet / Phone)    |
|                                             |   |                                 |
|  +-------------------+   +---------------+  |   |  +---------------------------+  |
|  | Local Mosquitto   |-->| Edge Sync &   |  |   |  | Offline-First PWA / App   |  |
|  | MQTT Broker (LAN) |   | Dedup Worker  |  |   |  | - Pre-cached Barn Herd    |  |
|  +---------^---------+   +-------+-------+  |   |  | - Encrypted SQLite Outbox |  |
|            |                     |          |   |  +-------------^-------------+  |
|  +---------+---------+   +-------v-------+  |   |                | BLE / NFC      |
|  | Barn Gate Readers |   | SQLite WAL    |  |   |  +-------------+-------------+  |
|  | (DrEBE6, Dr49F9)  |   | Outbox Queue  |  |   |  | Handheld UHF/HF RFID Wand |  |
|  +-------------------+   +---------------+  |   |  +---------------------------+  |
+---------------------------------------------+   +---------------------------------+
```