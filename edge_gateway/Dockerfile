FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY barn_edge_gateway.py .
ENV EDGE_DB_PATH=/data/edge_outbox.db
VOLUME /data
EXPOSE 8090
CMD ["python", "barn_edge_gateway.py"]
