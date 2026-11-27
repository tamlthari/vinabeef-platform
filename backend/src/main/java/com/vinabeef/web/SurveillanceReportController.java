package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import com.vinabeef.security.CurrentScopeProvider;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/surveillance")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
@SuppressWarnings("unchecked")
public class SurveillanceReportController {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentScopeProvider currentScope;
    private final NadcsGateway nadcs;

    /** How much of a transmitted body the log keeps (characters). The size and SHA-256 always describe the whole body. */
    @Value("${nadcs.body-store-max-chars:2000000}")
    private int bodyStoreMaxChars;

    /**
     * What the caller may choose. The destination and the credential are NOT part of it: they come from the server's
     * configuration (nadcs.*), so a request cannot redirect a transmission to another address.
     */
    @Data
    public static class TransmitRequest {
        private String targetSystem;   // label only
        private String reportType;
        private String farmScope;
        private String payloadFormat; // JSON or XML
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'EXECUTIVE')")
    public ResponseEntity getSurveillanceOverview() {
        var scope = currentScope.getCurrentScope();
        String effectiveFarm = SurveillanceFarmScope.resolve(scope, "ALL");
        boolean allFarms = "ALL".equalsIgnoreCase(effectiveFarm);

        Long totalHerd = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM animals a
            LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id
            WHERE a.lifecycle_status = 'ACTIVE' AND (? = TRUE OR b.farm_id = ?)
        """, Long.class, allFarms, effectiveFarm);

        // "vaccinated" = at least one VACCINATION record (same definition as the dashboard)
        Long vaccinatedHerd = jdbcTemplate.queryForObject("""
            SELECT COUNT(DISTINCT hr.animal_id)
            FROM health_records hr
            JOIN animals a ON hr.animal_id = a.animal_id
            LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id
            WHERE hr.record_type = 'VACCINATION' AND a.lifecycle_status = 'ACTIVE' AND (? = TRUE OR b.farm_id = ?)
        """, Long.class, allFarms, effectiveFarm);

        Long treatmentCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM animals a
            LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id
            WHERE a.health_status = 'UNDER_TREATMENT' AND a.lifecycle_status = 'ACTIVE' AND (? = TRUE OR b.farm_id = ?)
        """, Long.class, allFarms, effectiveFarm);

        Long quarantineCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM animals a
            LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id
            WHERE a.health_status = 'QUARANTINE' AND a.lifecycle_status = 'ACTIVE' AND (? = TRUE OR b.farm_id = ?)
        """, Long.class, allFarms, effectiveFarm);

        Long transferCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM animal_transfers
            WHERE (? = TRUE OR from_farm_id = ? OR to_farm_id = ?)
        """, Long.class, allFarms, effectiveFarm, effectiveFarm);

        var transmissions = jdbcTemplate.queryForList(SurveillanceQueries.TRANSMISSION_LIST, allFarms, effectiveFarm);

        List<Map<String, Object>> dataChecks = new ArrayList<>();
        for (var check : SurveillanceQueries.checks().values()) {
            Long n = jdbcTemplate.queryForObject(check.sql(), Long.class, allFarms, effectiveFarm);
            dataChecks.add(Map.of("code", check.code(), "label", check.label(), "severity", check.severity(),
                    "count", n != null ? n : 0L));
        }

        double vacPct = (totalHerd != null && totalHerd > 0 && vaccinatedHerd != null)
                ? Math.round((vaccinatedHerd * 1000.0) / totalHerd) / 10.0 : 0.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("effectiveFarm", effectiveFarm);
        out.put("metrics", Map.of(
                "totalHerd", totalHerd != null ? totalHerd : 0,
                "vaccinatedHerd", vaccinatedHerd != null ? vaccinatedHerd : 0,
                "vaccinationCoveragePct", vacPct,
                "underTreatment", treatmentCount != null ? treatmentCount : 0,
                "quarantined", quarantineCount != null ? quarantineCount : 0,
                "totalTransfers", transferCount != null ? transferCount : 0));
        out.put("vaccinatedDefinition", "At least one vaccination record on file; no validity period is applied.");
        out.put("dataChecks", dataChecks);
        // what the page needs to tell the truth about delivery; the API key itself is never returned
        out.put("nadcs", Map.of(
                "mode", nadcs.isLive() ? "LIVE" : "SIMULATION",
                "endpoint", nadcs.endpoint(),
                "testServer", nadcs.isTestServer(),
                "targetHost", nadcs.isTestServer() ? nadcs.targetHost() : "",
                "apiKeyConfigured", nadcs.apiKeyConfigured()));
        out.put("transmissions", transmissions);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'EXECUTIVE')")
    @Audited(action = "EXPORT_SURVEILLANCE_REPORT", targetType = "REPORT")
    public ResponseEntity exportReport(
            @RequestParam(defaultValue = "OUTBREAK_SURVEILLANCE") String reportType,
            @RequestParam(defaultValue = "ALL") String farmId,
            @RequestParam(defaultValue = "CSV") String format) {

        var scope = currentScope.getCurrentScope();
        String cleanType = reportType.trim().toUpperCase();
        String cleanFarm = SurveillanceFarmScope.resolve(scope, farmId);
        String cleanFormat = format.trim().toUpperCase();

        ReportRows data = queryReportRows(cleanType, cleanFarm);
        List rows = data.rows();
        String filename = "vinabeef_" + cleanType.toLowerCase() + "_" + cleanFarm.toLowerCase() + "." + cleanFormat.toLowerCase();

        ResponseEntity.BodyBuilder ok = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-Report-Truncated", String.valueOf(data.truncated()))
                .header("Access-Control-Expose-Headers", "X-Report-Truncated");
        if ("JSON".equals(cleanFormat)) {
            return ok.contentType(MediaType.APPLICATION_JSON).body(renderJsonReport(cleanType, cleanFarm, rows).getBytes(StandardCharsets.UTF_8));
        } else if ("XML".equals(cleanFormat)) {
            return ok.contentType(MediaType.APPLICATION_XML).body(renderXmlReport(cleanType, cleanFarm, rows).getBytes(StandardCharsets.UTF_8));
        }
        return ok.contentType(MediaType.parseMediaType("text/csv; charset=UTF-8")).body(renderCsvReport(rows).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Sends the report to the national system, or, in SIMULATION mode, records honestly that nothing was sent.
     * The result is stored with the status the agency really answered (DELIVERED / REJECTED / UNREACHABLE / SIMULATED).
     */
    @PostMapping("/transmit")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'EXECUTIVE')")
    @Audited(action = "TRANSMIT_NADCS_SURVEILLANCE", targetType = "EXTERNAL_API")
    public ResponseEntity transmitToExternalApi(@RequestBody TransmitRequest req, Authentication auth) {
        var scope = currentScope.getCurrentScope();
        String targetSystem = (req.getTargetSystem() != null && !req.getTargetSystem().isBlank())
                ? req.getTargetSystem().trim()
                : "National Animal Disease Control System (NADCS)";
        String reportType = (req.getReportType() != null && !req.getReportType().isBlank())
                ? req.getReportType().trim().toUpperCase() : "OUTBREAK_SURVEILLANCE";
        if (!SurveillanceQueries.isKnownType(reportType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown report type '" + reportType + "'."));
        }
        String requestedFarm = (req.getFarmScope() != null && !req.getFarmScope().isBlank())
                ? req.getFarmScope().trim() : "ALL";
        String farmScope = SurveillanceFarmScope.resolve(scope, requestedFarm);
        String format = (req.getPayloadFormat() != null && !req.getPayloadFormat().isBlank())
                ? req.getPayloadFormat().trim().toUpperCase() : "JSON";

        ReportRows data = queryReportRows(reportType, farmScope);
        if (data.truncated()) {
            // a partial submission to the national system is worse than none
            return ResponseEntity.status(422).body(Map.of("error",
                    "The report has more than " + SurveillanceQueries.MAX_ROWS + " records; narrow it to one farm or another report type. Nothing was sent."));
        }
        List rows = data.rows();
        String payload = "XML".equals(format)
                ? renderXmlReport(reportType, farmScope, rows)
                : renderJsonReport(reportType, farmScope, rows);

        // the id exists before the call so it can travel with the request (X-Client-Reference) and be found on the other side
        String txId = "TX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        NadcsGateway.Result result;
        try {
            result = nadcs.send(payload, format, rows.size(), txId);
        } catch (NadcsGateway.NotConfiguredException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }

        String actor = auth != null ? auth.getName() : "system";
        TransmissionBody.Captured body = TransmissionBody.capture(payload, bodyStoreMaxChars);

        jdbcTemplate.update("""
            INSERT INTO external_transmissions
            (transmission_id, target_system, endpoint_url, report_type, farm_scope, record_count, payload_format,
             http_status, delivery_status, response_summary, payload_snapshot, payload_bytes, payload_sha256,
             payload_truncated, transmitted_by, transmitted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """, txId, targetSystem, nadcs.endpoint(), reportType, farmScope, rows.size(), format,
                result.httpStatus(), result.deliveryStatus(), result.summary(), body.text(), body.bytes(), body.sha256(),
                body.truncated(), actor);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", result.deliveryStatus());
        out.put("delivered", NadcsGateway.DELIVERED.equals(result.deliveryStatus()));
        out.put("transmissionId", txId);
        out.put("httpStatus", result.httpStatus());
        out.put("recordCount", rows.size());
        out.put("responseSummary", result.summary());
        return ResponseEntity.ok(out);
    }

    /**
     * One row of the transmission log with the body that was sent (or, in SIMULATION, would have been sent).
     * {@code maxChars} limits the excerpt returned here; the whole stored body is at {@code /transmissions/{id}/body}.
     * A farm-scoped account sees only the transmissions of its own farm; a report sent for all farms is not in its log.
     */
    @GetMapping("/transmissions/{transmissionId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'EXECUTIVE')")
    public ResponseEntity getTransmission(@PathVariable String transmissionId,
                                          @RequestParam(defaultValue = "120000") int maxChars) {
        var scope = currentScope.getCurrentScope();
        String farm = SurveillanceFarmScope.resolve(scope, "ALL");
        boolean allFarms = "ALL".equalsIgnoreCase(farm);
        int excerpt = Math.min(Math.max(maxChars, 0), 500_000);

        var rows = jdbcTemplate.queryForList(SurveillanceQueries.TRANSMISSION_DETAIL,
                excerpt, transmissionId, allFarms, farm);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No transmission '" + transmissionId + "' is visible to this account."));
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        boolean visible = Boolean.TRUE.equals(row.get("body_visible"));
        boolean legacy = row.get("payload_bytes") == null;            // recorded before the full body was kept
        String text = visible && row.get("body_excerpt") != null ? row.get("body_excerpt").toString() : "";
        long storedChars = ((Number) row.get("stored_chars")).longValue();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transmissionId", row.get("transmission_id"));
        out.put("targetSystem", row.get("target_system"));
        out.put("endpointUrl", row.get("endpoint_url"));
        out.put("reportType", row.get("report_type"));
        out.put("farmScope", row.get("farm_scope"));
        out.put("recordCount", row.get("record_count"));
        out.put("payloadFormat", row.get("payload_format"));
        out.put("contentType", TransmissionBody.contentType(String.valueOf(row.get("payload_format"))));
        out.put("httpStatus", row.get("http_status"));
        out.put("deliveryStatus", row.get("delivery_status"));
        out.put("responseSummary", row.get("response_summary"));
        out.put("transmittedBy", row.get("transmitted_by"));
        out.put("transmittedAt", row.get("transmitted_at"));
        out.put("bodyVisible", visible);
        out.put("legacy", legacy);
        out.put("payloadBytes", row.get("payload_bytes"));
        out.put("payloadSha256", row.get("payload_sha256"));
        out.put("storedTruncated", Boolean.TRUE.equals(row.get("payload_truncated")));
        out.put("storedChars", storedChars);
        out.put("bodyExcerpt", text);
        out.put("excerptTruncated", visible && storedChars > text.length());
        out.put("testServerUi", nadcs.testServerUiUrl());
        return ResponseEntity.ok(out);
    }

    /** The whole stored body of one transmission, as it was sent (same visibility rule as the detail). */
    @GetMapping("/transmissions/{transmissionId}/body")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'EXECUTIVE')")
    public ResponseEntity getTransmissionBody(@PathVariable String transmissionId) {
        var scope = currentScope.getCurrentScope();
        String farm = SurveillanceFarmScope.resolve(scope, "ALL");
        boolean allFarms = "ALL".equalsIgnoreCase(farm);
        var rows = jdbcTemplate.queryForList(SurveillanceQueries.TRANSMISSION_BODY, transmissionId, allFarms, farm);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error",
                    "No transmission '" + transmissionId + "' with a body this account may see."));
        }
        Map<String, Object> row = rows.get(0);
        String format = String.valueOf(row.get("payload_format"));
        String text = row.get("payload_snapshot") == null ? "" : row.get("payload_snapshot").toString();
        var ok = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(TransmissionBody.contentType(format) + "; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + TransmissionBody.fileName(transmissionId, format) + "\"")
                .header("X-Payload-Truncated", String.valueOf(Boolean.TRUE.equals(row.get("payload_truncated"))));
        if (row.get("payload_sha256") != null) {
            ok = ok.header("X-Payload-Sha256", row.get("payload_sha256").toString());
        }
        return ok.body(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A request the account's farm scope does not allow: 403 with the reason, in the {"error": ...} shape the portal reads. */
    @ExceptionHandler(SurveillanceFarmScope.Denied.class)
    public ResponseEntity<Map<String, String>> scopeDenied(SurveillanceFarmScope.Denied e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }

    /** Rows of a report, and whether the cap cut it. */
    private record ReportRows(List rows, boolean truncated) {
    }

    private ReportRows queryReportRows(String reportType, String farmId) {
        boolean allFarms = "ALL".equalsIgnoreCase(farmId);
        String sql = SurveillanceQueries.report(reportType);
        List all = SurveillanceQueries.farmParams(reportType) == 3
                ? jdbcTemplate.queryForList(sql, allFarms, farmId, farmId)
                : jdbcTemplate.queryForList(sql, allFarms, farmId);
        boolean truncated = all.size() > SurveillanceQueries.MAX_ROWS;
        return new ReportRows(truncated ? new ArrayList<>(all.subList(0, SurveillanceQueries.MAX_ROWS)) : all, truncated);
    }

    private String renderCsvReport(List rows) {
        if (rows.isEmpty()) {
            return "message\nNo records found for selected scope\n";
        }
        StringBuilder sb = new StringBuilder();
        Map firstRow = (Map) rows.get(0);
        List headers = new ArrayList(firstRow.keySet());

        StringJoiner headerJoiner = new StringJoiner(",");
        for (Object h : headers) {
            headerJoiner.add(String.valueOf(h));
        }
        sb.append(headerJoiner).append("\n");

        for (Object obj : rows) {
            Map row = (Map) obj;
            StringJoiner rowJoiner = new StringJoiner(",");
            for (Object h : headers) {
                Object val = row.get(h);
                String str = val == null ? "" : String.valueOf(val).replace("\"", "\"\"");
                rowJoiner.add("\"" + str + "\"");
            }
            sb.append(rowJoiner).append("\n");
        }
        return sb.toString();
    }

    private String renderJsonReport(String reportType, String farmScope, List rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"agency\": \"National Animal Disease Control System (NADCS)\",\n");
        sb.append("  \"schemaVersion\": \"NADCS-2.1\",\n");
        sb.append("  \"reportType\": \"").append(escapeJson(reportType)).append("\",\n");
        sb.append("  \"farmScope\": \"").append(escapeJson(farmScope)).append("\",\n");
        sb.append("  \"generatedAt\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"recordCount\": ").append(rows.size()).append(",\n");
        sb.append("  \"records\": [\n");

        for (int i = 0; i < rows.size(); i++) {
            Map row = (Map) rows.get(i);
            sb.append("    {");
            int j = 0;
            for (Object k : row.keySet()) {
                String key = String.valueOf(k);
                sb.append("\"").append(escapeJson(key)).append("\": ");
                Object v = row.get(k);
                if (v == null) {
                    sb.append("null");
                } else if (v instanceof Number || v instanceof Boolean) {
                    sb.append(v);
                } else {
                    sb.append("\"").append(escapeJson(String.valueOf(v))).append("\"");
                }
                if (++j < row.size()) sb.append(", ");
            }
            sb.append("}").append(i + 1 < rows.size() ? "," : "").append("\n");
        }

        sb.append("  ]\n}");
        return sb.toString();
    }

    private String renderXmlReport(String reportType, String farmScope, List rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<SurveillanceReport schema=\"NADCS-WOAH-2.1\" reportType=\"").append(escapeXml(reportType))
          .append("\" farmScope=\"").append(escapeXml(farmScope))
          .append("\" generatedAt=\"").append(Instant.now()).append("\">\n");
        sb.append("  <RecordCount>").append(rows.size()).append("</RecordCount>\n");
        sb.append("  <Records>\n");
        for (Object obj : rows) {
            Map row = (Map) obj;
            sb.append("    <Record>\n");
            for (Object k : row.keySet()) {
                String tag = String.valueOf(k).replaceAll("[^a-zA-Z0-9_]", "_");
                Object v = row.get(k);
                String val = v == null ? "" : escapeXml(String.valueOf(v));
                sb.append("      <").append(tag).append(">").append(val).append("</").append(tag).append(">\n");
            }
            sb.append("    </Record>\n");
        }
        sb.append("  </Records>\n</SurveillanceReport>\n");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}