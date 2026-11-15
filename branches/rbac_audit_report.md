Group farm in dashboard
Create backend/src/main/resources/db/migration/V6__seed_vaccination_records.sql to populate sample clinical records into health_records (FR-09, FR-10):


Update backend/src/main/java/com/vinabeef/web/GateMonitorController.java to aggregate facilities, calculate herd metrics, and compute vaccination coverage per barn and per farm

Update FastAPI Controller (frontend/main.py)
Update both home_page and htmx_barns in frontend/main.py to query /facilities/distribution

Redesigned Grouped Partial (frontend/templates/partials/headcount_cards.html)
Replace frontend/templates/partials/headcount_cards.html with this Safetrust-styled layout