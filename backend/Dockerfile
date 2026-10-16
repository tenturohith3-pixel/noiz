# noiz backend image (API + Celery worker share it; only the command differs).
FROM python:3.12-slim AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

# ffmpeg/ffprobe drive probe, loudness (ebur128) and transcoding; rsgain is the
# preferred R128 scanner when present (§4.1 stage 9).
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        libsndfile1 \
        rsgain \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /srv/noiz

# `alembic.ini` resolves `script_location = migrations` and `prepend_sys_path = .`
# relative to the working directory, so WORKDIR must be the directory those two
# land in — the api service shells out to `alembic upgrade head` before uvicorn
# and would otherwise fail with "No config file 'alembic.ini'".
ENV PYTHONPATH=/srv/noiz

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY noiz ./noiz
COPY alembic.ini ./alembic.ini
COPY migrations ./migrations

# Ingest runs as an unprivileged user with no write access outside the job dir (§12).
# /media and /inbox must be chowned too: Docker initialises named volumes from the
# image directory, so `media:/media` and `inbox:/inbox` otherwise inherit root:root
# 0755 and every ffmpeg/ingest write fails with EACCES.
RUN useradd --system --uid 10001 --create-home noiz \
    && mkdir -p /media /inbox \
    && chown -R noiz:noiz /srv/noiz /media /inbox \
    && chmod 0750 /media /inbox
USER noiz

EXPOSE 8000

# Probes /readyz rather than /healthz: /healthz never touches the database, so it
# reports healthy while Postgres is down.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/readyz', timeout=2).status==200 else 1)"

# --proxy-headers so nginx's X-Forwarded-For is honoured for client IPs.
CMD ["uvicorn", "noiz.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2", "--proxy-headers", "--forwarded-allow-ips", "*"]
