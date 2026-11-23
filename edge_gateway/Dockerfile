FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY barn_edge_gateway.py local_swipes.py usb_input.py ./
ENV EDGE_DB_PATH=/data/edge_outbox.db
VOLUME /data
EXPOSE 8090
# USB readers: pass the device in, e.g.  docker run --device=/dev/input/event5 --group-add input ...   (keyboard-style reader)
#                                  or    docker run --device=/dev/ttyACM0 --group-add dialout ...      (serial / CDC reader)
CMD ["python", "barn_edge_gateway.py"]
