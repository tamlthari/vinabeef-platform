1
Create backend/src/main/resources/db/migration/V3__fix_sensor_nulls_and_readers.sql

2
Update backend/src/main/java/com/vinabeef/ingestion/RfidReadEvent.java so Hibernate allows cardUuid to be nul

3
Update backend/src/main/java/com/vinabeef/movement/SafetrustProcessingService.java. If an event does not have an RFID tag, persist the telemetry for audit/logging and exit early before attempting movement state transitions

4
Update your SecurityConfig.java (typically in backend/src/main/java/com/vinabeef/security/SecurityConfig.java) to permit /error and disable CSRF on ingestion routes:

5
Enable out-of-order in backend/src/main/resources/application.yml

Under the spring.flyway section, add out-of-order: true:
YAML

spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    out-of-order: true        # <--- Allows Flyway to run version 3 even though version 4 was already applied
    validate-on-migrate: true

Fix MQTT connection:
6
Update DirectMqttIngestionService.java

Replace backend/src/main/java/com/vinabeef/ingestion/DirectMqttIngestionService.java with the following implementation. It disables HTTPS hostname verification, aligns the client ID prefix with Safetrust's convention, and includes detailed logging for root causes and reason codes