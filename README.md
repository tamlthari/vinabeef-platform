## Start Guide

## Backend
### Start Docker containers for the database
```bash
docker compose up -d postgres redis

# check containers' statuses
docker compose ps
```
### Initialize Spring project
```bash
curl https://start.spring.io/starter.zip \
  -d type=maven-project \
  -d language=java \
  -d baseDir=backend \
  -d groupId=com.vinabeef \
  -d artifactId=farm-management \
  -d name=farm-management \
  -d packageName=com.vinabeef \
  -d javaVersion=21 \
  -d dependencies=web,data-jpa,data-redis,postgresql,flyway,lombok \
  -o backend.zip && unzip backend.zip && rm backend.zip
```
Add MQTT dependency to pom.xml
```bash
<dependency>
    <groupId>org.springframework.integration</groupId>
    <artifactId>spring-integration-mqtt</artifactId>
</dependency>
```
Check Java version and upgrade
```bash
java -version
javac -version
```
```bash
brew install openjdk@21
sudo ln -sfn $(brew --prefix openjdk@21)/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

### Run the project
#### Prepare the Flyway schema
```bash
backend/src/main/resources/db/migration/*.sql
```
#### Compile using Maven
```
./mvnw clean package -DskipTests
```
For the first time
```bash
cd backend
./mvnw clean spring-boot:run
```
For subsequent use
```bash
cd backend
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw clean spring-boot:run
```
### Populate the database with large data of ~25,000 cattle
```bash
docker exec -i vinabeef-postgres psql -U vinabeef_user -d vinabeef_db < scripts/seed_25k_cattle.sql
```
Check the data is loaded correctly
```bash
docker exec -it vinabeef-postgres psql -U vinabeef_user -d vinabeef_db -c "
SELECT 
    f.farm_id,
    COUNT(DISTINCT b.barn_id) AS total_barns,
    SUM(b.capacity) AS total_capacity,
    SUM(b.current_headcount) AS total_in_barn_headcount,
    (SELECT COUNT(*) FROM animals a JOIN barns b2 ON a.assigned_barn_id = b2.barn_id WHERE b2.farm_id = f.farm_id) AS total_registered_cattle
FROM farms f
LEFT JOIN barns b ON f.farm_id = b.farm_id
GROUP BY f.farm_id
ORDER BY f.farm_id;
"
```
## Frontend
```bash
cd vinabeef-platform
mkdir -p frontend/templates/partials
cd frontend

python3 -m venv vinabeef
source vinabeef/bin/activate
pip install "fastapi>=0.115.0" "uvicorn[standard]>=0.30.0" "jinja2>=3.1.4" "httpx>=0.27.0" "python-multipart"
```

### Run the front end
```bash
./vinabeef/bin/python -m uvicorn main:app --reload --port 3000
```

## Subscribe MQTT and get events pushed by sensor
config.csv follows 'device_key','token','company_key'
### Set up MQTT environment
```bash
python -m venv mqtt
source mqtt/bin/activate
pip install paho-mqtt==1.6.1
```
```bash
python3 multi_webs_sub_mqtt_threads.py \
  -u sensor.demo.safetrust.com \
  -p 443 \
  -f config.csv \
  -o mqtt_events.csv \
  -d 0 \
  -e true \
  -w 1
```

## Performance
### Add database index to PostGres data base
```bash
docker exec -it vinabeef-postgres psql -U vinabeef_user -d vinabeef_db -c "
CREATE INDEX IF NOT EXISTS idx_tag_assignments_animal_id ON tag_assignments(animal_id);
CREATE INDEX IF NOT EXISTS idx_tag_assignments_tag_id ON tag_assignments(tag_id);
CREATE INDEX IF NOT EXISTS idx_health_records_animal_id ON health_records(animal_id);
CREATE INDEX IF NOT EXISTS idx_rfid_read_events_reader_name ON rfid_read_events(reader_name);
"
```