FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 tubego
WORKDIR /app
COPY backend/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY backend/tubego_server ./tubego_server
ENV TUBEGO_DATA_DIR=/data
RUN mkdir /data && chown tubego:tubego /data
USER tubego
VOLUME ["/data"]
EXPOSE 8000
CMD ["uvicorn", "tubego_server.main:app", "--host", "0.0.0.0", "--port", "8000"]
