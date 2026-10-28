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
from celery.signals import worker_process_init

from noiz.core.db import close_pool, init_pool
from noiz.core.settings import get_settings

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
    """Task modules that actually exist, without raising for the ones that don't.

    `importlib.util.find_spec("noiz.ingest.tasks")` imports the *parent* package, so it
    raises `ModuleNotFoundError` rather than returning `None` when `noiz.ingest` is
    absent. That propagated out of `create_celery()` and killed the worker at startup,
    which is the opposite of what the comment above claims.
    """
    found: list[str] = []
    for name in TASK_MODULES:
        try:
            if importlib.util.find_spec(name) is not None:
                found.append(name)
        except (ImportError, ModuleNotFoundError, ValueError):
            continue
    return found


def _resolved_rate_limits(app: Celery) -> dict:
    """Expand the RATE_LIMITS globs onto the tasks that are actually registered.

    Celery matches `task_annotations` by exact task name. Globbing is only supported by
    `task_routes`, so the previous glob dict was inert and every provider request went
    out unthrottled — the opposite of the "rate limits are respected" claim.

    Iterate the application's registry, not the task class: the old
    `from celery.app.registry import tasks` imported a name that does not exist in
    Celery 5.x and killed both worker and beat at startup.
    """
    resolved: dict = {}
    for pattern, limit in RATE_LIMITS.items():
        prefix = pattern.rstrip("*")
        for name in app.tasks:
            if name == pattern or name.startswith(prefix):
                resolved[name] = {"rate_limit": limit}
    return resolved


def create_celery() -> Celery:
    settings = get_settings()

    app = Celery(
        "noiz",
        broker=settings.redis_url,
        backend=settings.redis_url,
        include=_installed_task_modules(),
    )

    app.conf.update(
        task_default_queue=TASK_DEFAULT_QUEUE,
        task_queues={queue: {"exchange": queue, "routing_key": queue} for queue in TASK_QUEUES},
        task_routes=TASK_ROUTES,
        # `task_annotations` is keyed by EXACT task name — glob patterns are a
        # `task_routes` feature only. Passing the globs silently applied nothing, so
        # every provider call ran unthrottled.
        task_annotations=_resolved_rate_limits(app),
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
            # The reco/enrich/scrobble stages are not implemented yet. Scheduling their
            # task names made beat enqueue work that no worker could execute (and made
            # beat noisy at startup), so only the stages that exist are scheduled.
            "recompute-taste-vectors": {
                "task": "noiz.reco.tasks.recompute_all_taste",
                "schedule": crontab(hour=3, minute=0),
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


@worker_process_init.connect
def _bootstrap_pool(**_: object) -> None:
    """Every prefork child needs its own asyncpg pool.

    Without this, any task touching the database hit
    `RuntimeError("database pool is not initialised")`.
    """
    import anyio

    anyio.run(lambda: init_pool(get_settings()))
