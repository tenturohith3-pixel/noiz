"""Celery application (ADR 3, §2.2).

CPU-bound ingest/analysis work runs in a prefork pool, not in the API's event loop.
Every stage is an idempotent task keyed by `ingest_job.id`, so a crashed worker resumes
mid-pipeline instead of starting over.

Run:
    celery -A noiz.worker worker -Q ingest,transcode,analysis,enrich,scrobble -c 4
    celery -A noiz.worker beat
"""

from __future__ import annotations

import importlib.util
import os

from celery import Celery
from celery.schedules import crontab

# Dedicated queues so a 500k-track analysis backlog can never delay a user upload.
TASK_QUEUES = ("ingest", "transcode", "analysis", "enrich", "scrobble")

TASK_DEFAULT_QUEUE = "ingest"

TASK_ROUTES = {
    "noiz.ingest.*": {"queue": "ingest"},
    "noiz.audio.transcode.*": {"queue": "transcode"},
    "noiz.audio.analysis.*": {"queue": "analysis"},
    "noiz.enrich.*": {"queue": "enrich"},
    "noiz.scrobble.*": {"queue": "scrobble"},
}

# §4.2: MusicBrainz is 1 req/s and AcoustID 3 req/s. Per-worker token buckets live in
# Redis so `-c 4` cannot multiply the outbound rate.
RATE_LIMITS = {
    "noiz.enrich.musicbrainz.*": "60/m",
    "noiz.enrich.acoustid.*": "180/m",
    "noiz.enrich.coverart.*": "300/m",
    "noiz.enrich.lastfm.*": "300/m",
    "noiz.enrich.discogs.*": "60/m",
}


# Task modules are discovered rather than hard-listed: a deployment that has not yet
# built (say) the reco stage still starts its worker instead of crashing on import.
TASK_MODULES = (
    "noiz.ingest.tasks",
    "noiz.audio.tasks",
    "noiz.enrich.tasks",
    "noiz.scrobble.tasks",
    "noiz.reco.tasks",
)


def _installed_task_modules() -> list[str]:
    return [name for name in TASK_MODULES if importlib.util.find_spec(name) is not None]


def create_celery() -> Celery:
    app = Celery(
        "noiz",
        broker=os.environ.get("NOIZ_REDIS_URL", "redis://redis:6379/0"),
        backend=os.environ.get("NOIZ_REDIS_URL", "redis://redis:6379/0"),
        include=_installed_task_modules(),
    )

    app.conf.update(
        task_default_queue=TASK_DEFAULT_QUEUE,
        task_queues={queue: {"exchange": queue, "routing_key": queue} for queue in TASK_QUEUES},
        task_routes=TASK_ROUTES,
        task_annotations=RATE_LIMITS,
        # Long media jobs: 10 minute wall-clock cap matches the ingest sandbox limit (§12).
        task_time_limit=600,
        task_soft_time_limit=540,
        task_acks_late=True,
        task_reject_on_worker_lost=True,
        # One job at a time per prefork child so a big FLAC cannot starve its siblings.
        worker_prefetch_multiplier=1,
        worker_max_tasks_per_child=50,  # bounds ffmpeg/Essentia memory growth
        result_expires=3600,
        timezone="UTC",
        enable_utc=True,
        beat_schedule={
            # Library delta sync / taste recompute (§5.3) runs nightly.
            "recompute-taste-vectors": {
                "task": "noiz.reco.tasks.recompute_all_taste",
                "schedule": crontab(hour=3, minute=0),
            },
            # Recommend evaluation harness gate (§5.6) runs before the taste job lands.
            "recommender-eval": {
                "task": "noiz.reco.tasks.evaluate_recommender",
                "schedule": crontab(hour=2, minute=30),
            },
            # New-from-your-artists polling (§5.4).
            "poll-followed-artists": {
                "task": "noiz.enrich.tasks.poll_followed_artists",
                "schedule": crontab(hour=4, minute=0, day_of_week=5),
            },
            # Scrobble retry sweep; Last.fm rejects anything older than 14 days (§6.11).
            "scrobble-retry-sweep": {
                "task": "noiz.scrobble.tasks.retry_pending",
                "schedule": crontab(minute="*/15"),
            },
            # WatchFolder settle-and-ingest for drops the inotify handler missed (§4.3).
            "inbox-sweep": {
                "task": "noiz.ingest.tasks.sweep_inbox",
                "schedule": crontab(minute="*/5"),
            },
        },
    )
    return app


celery_app = create_celery()
