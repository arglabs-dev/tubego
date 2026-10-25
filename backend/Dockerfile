FROM python:3.12-slim
ARG TUBEGO_BUILD_REVISION
ARG YTDLP_VERSION=2026.8.19
ENV TUBEGO_BUILD_REVISION=$TUBEGO_BUILD_REVISION
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 tubego
WORKDIR /app
COPY backend/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir --only-binary=deno -r requirements.txt \
    && pip install --no-cache-dir --index-url https://pypi.org/simple "yt-dlp[default]==$YTDLP_VERSION" \
    && deno --version \
    && python -c "import yt_dlp_ejs"
COPY backend/tubego_server ./tubego_server
COPY src/storage.py ./src/storage.py
COPY src/disk_guard.py ./src/disk_guard.py
ENV TUBEGO_DATA_DIR=/data
RUN mkdir /data && chown tubego:tubego /data
USER tubego
VOLUME ["/data"]
EXPOSE 8000
CMD ["uvicorn", "tubego_server.main:app", "--host", "0.0.0.0", "--port", "8000"]
