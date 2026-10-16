"""Ingest stage (§4.3, ADR 3).

`tasks.py` holds the Celery tasks the worker's beat schedule already references
(`noiz.ingest.tasks.sweep_inbox`) plus the reusable, Celery-free coroutines that
`seed.py` calls directly.

This package must exist as a real package with an `__init__.py`: `worker.py`
discovers task modules via `importlib.util.find_spec("noiz.ingest.tasks")`, which
imports the *parent* package and therefore raises `ModuleNotFoundError` (rather
than returning `None`) when `noiz/ingest/__init__.py` is missing.
"""
