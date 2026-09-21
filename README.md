# noiz — foundational modules

Generated from the noiz PRD v1.0 (20 Sep 2026). This is the **Phase 1 spine**: the four
foundational modules, written to compile against the pinned toolchain rather than to be
"representative".

```
noiz/
├── android/
│   ├── gradle/libs.versions.toml          # single source of truth for every pin
│   ├── settings.gradle.kts                # module graph (§3.2)
│   ├── app/src/main/AndroidManifest.xml   # permissions, FGS types, Auto descriptor
│   ├── core/designsystem/                 # §7  Glass design system
│   ├── core/playback/                     # §3.4–3.5, §6.2  audio engine + service
│   ├── core/database/                     # §8.3  Room schema + FTS4
│   └── core/data/                         # §8.4  outbox worker
└── backend/
    ├── Dockerfile, requirements.txt
    ├── nginx.conf                         # §9.4  TLS, Range, X-Accel-Redirect
    ├── noiz/main.py                       # §9    app factory, lifespan, problem+json
    ├── noiz/core/{settings,db,security}.py
    ├── noiz/api/deps.py
    ├── noiz/api/v1/{auth,stream}.py
    └── noiz/worker.py                     # §2.2  Celery queues + beat schedule
```

## 1. The Glass UI system

| File | Contents |
| --- | --- |
| `core/designsystem/GlassTokens.kt` | `GlassLevel` G0–G4 with blur/tint/opaque specs, `GlassTier` (Full/Static/Opaque), `Modifier.glassBorder` luminous gradient, `NoizPalette`, `NoizMotion` |
| `core/designsystem/GlassSurface.kt` | `GlassSurface` (Haze `hazeEffect` on Full, shared pre-blurred bitmap on Static, tinted fill on Opaque), `AmbientBackdrop` (G0 mesh + the screen's single `hazeSource`), `StaticBackdrop`, `GlassText` with automatic contrast scrim, `ProvideGlass` |
| `core/designsystem/GlassTierController.kt` | Launch + live tier selection from JankStats (12 % over a 5 s window), thermal, Battery Saver, Reduce transparency |
| `feature/home/HomeScreen.kt` | Root scaffold: G0 backdrop, scroll-linked G2 top bar, mini-player, and the `AnchoredDraggableState` Now Playing G3 sheet |

Frame-budget rules from §7.2 are enforced structurally, not by convention:

* **One backdrop source per screen.** `AmbientBackdrop` is the only `hazeSource`; every
  panel takes the same `HazeState`. Adding a second source is a compile-time-visible
  signature change.
* **Radius is never animated.** It comes from the `GlassLevel` enum, and tier changes
  cross-fade alpha instead (`GlassTierController` only ever swaps the whole modifier).
* **State reads stay in the draw/layout phase.** The sheet progress is exposed as
  `progress: () -> Float`, consumed inside `graphicsLayer {}` and `offset {}`, so a drag
  causes zero recompositions. Same for the seek bar's position.
* **Blur is bounded.** Half-resolution input (`GlassInputScale`), radius ≤ 32 dp, and the
  top bar uses `derivedStateOf { listState.canScrollBackward }` so an idle screen blurs
  nothing at all.

## 2. The audio engine

| File | Contents |
| --- | --- |
| `ExoPlayers.kt` | Player construction: float output, `NormalizationProcessor` in the sink chain, `ResolvingDataSource` → `CacheDataSource`, 20–60 s / 64 MB buffer, `WAKE_MODE_NETWORK`, shared audio session id |
| `NormalizationProcessor.kt` | R128 gain with 50 ms ramps, soft limiter, `computeGainDb` matching §6.4 exactly |
| `CrossfadeController.kt` | Equal-power `cos`/`sin` ramps, abort-on-focus-loss, 300 ms skip blend, `canCrossfade` guard |
| `DualPlayerEngine.kt` | Owns both ExoPlayers, prefetch monitor (T−30 s), promotes the prepared player, gapless vs dual mode, repeat/shuffle, unplayable-track skipping |
| `NoizPlayer.kt` | `SimpleBasePlayer` facade — one coherent `State` so MediaSession/notification/Auto never see the swap |
| `PlaybackService.kt` | `MediaLibraryService`, `MediaLibrarySession`, per-controller session commands, `onPlaybackResumption`, Auto browse tree, 5 s position persistence |
| `StreamingSupport.kt` | `SignedUrlResolver` (mints below the cache layer) and `NoizLoadErrorHandlingPolicy` (1/2/4/8 s then 10 s forever) |
| `PlaybackContracts.kt` | `NoizCommands`, `QueueRestoreStore`, `BrowseTreeProvider`, `FavoritesWriter`, `PlaybackSettings`, `SleepTimerController` |

Two design points worth calling out:

**Gapless vs crossfade are different code paths.** With `crossfadeMs == 0` the primary
player is loaded with the whole remaining queue so ExoPlayer itself honours encoder
delay/padding across items — the engine only maps the player's media index back to the
canonical queue index. With `crossfadeMs > 0` each item is loaded alone and the engine
drives the swap. Track metadata is `EngineTrack`, not `MediaItem`, so loudness, peak,
rendition and the gapless flag travel with the queue instead of being re-fetched.

**The idle player is prepared, not promoted blindly.** `prepareOn()` sets the incoming
track's gain *before* its first sample, so a crossfade has no level jump mid-ramp.

## 3. The local database

`core/database/Entities.kt` implements §8.3 verbatim plus `PlayEventEntity`,
`PlaybackStateEntity`, `FavoriteEntity` and `PaletteCacheEntity`.

`SearchIndexEntity` is a **standalone FTS4** index (not `contentEntity`) because it
carries tracks, albums, artists and playlists in one table:

```kotlin
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61,
      tokenizerArgs = ["remove_diacritics=2"],
      notIndexed = ["entityType", "entityId"])
```

`remove_diacritics=2` matches the server's unicode-aware behaviour, so "bjork" finds
"Björk" offline and online identically. `SearchDao.rebuild()` is a `@Transaction` that
clears and repopulates — Room cannot express a trigger across four source tables.

`MutationDao` is an abstract `@Dao` whose `@Transaction` methods write the optimistic
Room change **and** the outbox op atomically, so the UI can never show state that has no
durable mutation behind it.

`core/data/OutboxWorker.kt` flushes in HLC order, batches 100 ops, treats `ok` and
`conflict`/`invalid` as terminal (a permanently bad op must not wedge the queue), and
sends play events separately so a partial failure re-sends rather than duplicating.

## 4. The backend

`noiz/main.py` — app factory + lifespan (pool creation, extension verification, logging),
RFC 7807 `application/problem+json` for every error path, request ids and a
`X-Process-Time-Ms` header for the §10 latency budget, `/healthz` (no auth, no DB
round-trip, so the client's parallel endpoint prober stays under 250 ms) and `/readyz`.

`noiz/core/db.py` — one asyncpg pool, a `vector`-type codec so pgvector columns
round-trip, and `require_extensions()` which fails at boot rather than at first search.

`noiz/core/security.py` — three separate secrets: Argon2id (m=64 MiB, t=3, p=2), HS256
JWTs, and the HMAC stream signature `base64url(HMAC-SHA256(secret, "{user}|{track}|{rendition}|{exp}"))`.

`noiz/api/v1/stream.py` — both serving paths behind one URL shape:

* `GET /api/v1/tracks/{id}/stream-manifest` returns renditions, loudness, `gapless_next`
  and signed URLs.
* `GET /stream/{track}/{rendition}?exp=&sig=&user=` verifies the signature and then, in
  production, replies `206`/`200` with an `X-Accel-Redirect` header so nginx does
  sendfile. With `NOIZ_X_ACCEL_ENABLED=false` FastAPI streams the range itself.
* Range handling covers `bytes=a-b`, `bytes=a-` and suffix `bytes=-n`, returns `416` with
  `Content-Range: bytes */<size>` for unsatisfiable ranges, and `304` on a matching ETag.

Path traversal is guarded twice: the stored path is re-rooted under `media_root` and
checked with `is_relative_to`, and nginx's `/_media/` location is `internal` so a guessed
path is a 404.

## 5. Pins and version-sensitive APIs

The version catalog is the contract. Four APIs change shape between adjacent releases
and are the first things to check when bumping:

| Pin | Sensitivity |
| --- | --- |
| Compose BOM `2025.01.00` (Foundation 1.7.6) | `AnchoredDraggableState` uses `positionalThreshold` + `snapAnimationSpec` + `decayAnimationSpec`. Foundation 1.8 replaced these with a single `animationSpec`; bumping the BOM means editing `HomeScreen.kt`. |
| Haze `1.2.1` | `hazeEffect {}` scope members `blurRadius` / `inputScale` / `tints` / `noiseFactor`, and `HazeInputScale.Fixed`. Wrapped behind `GlassSurface` on purpose. |
| Media3 `1.5.1` | `DefaultRenderersFactory.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)` is a three-argument override; `DefaultAudioSink.DefaultAudioProcessorChain(vararg AudioProcessor)`; `LoadErrorHandlingPolicy.LoadErrorInfo` for retry delays; `SimpleBasePlayer` handler overloads. All four moved within the 1.x line. |
| Room `2.7.0-alpha11` | `@Fts4` tokenizer args, `fallbackToDestructiveMigrationOnDowngrade(dropAllTables)`. |

Predictive back uses `dispatchRawDelta`, not `AnchoredDraggableState.offset` assignment,
because `offset` has no public setter and `anchoredDrag`'s block signature changed.

## 6. What is intentionally not here

These are Phase 1 spine modules. Deliberately absent, with the seam left in place:

* Hilt modules and `Application` wiring. `PlaybackComponents` is an explicit service
  locator with `requireNotNull` factories — replace it with `@Module` bindings and delete
  the object; the interfaces it returns are already the injection points.
* Coil `AsyncImage` in `ArtworkPlaceholder` (module-level screenshot/Roborazzi
  renderability without a network dependency).
* Celery task *bodies* for ingest/analysis/enrich/scrobble. `worker.py` ships the queue
  topology, routing, rate limits and beat schedule; the modules are discovered at import
  so a deployment without them still starts.
* Alembic migrations. `docker-compose.yml` and `Dockerfile` expect `migrations/`; the SQL
  from §8.2 is the baseline to autogenerate from.

## 7. Running

```bash
# Backend
cp .env.example .env      # set NOIZ_JWT_SECRET and NOIZ_STREAM_HMAC_SECRET
docker compose up -d postgres redis
docker compose up api worker

# Android (arm64-v8a primary per NFR-07)
cd android && ./gradlew :app:installDebug
```

`.env` must define `POSTGRES_PASSWORD`, `NOIZ_JWT_SECRET` and
`NOIZ_STREAM_HMAC_SECRET`; compose refuses to start without the latter two rather than
falling back to a default secret.
