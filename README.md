## Start Guide

## Backend
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
#### Prepare the Flyway schema
```bash
backend/src/main/resources/db/migration/*.sql
```
Run the project
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