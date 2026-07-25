# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this app is

Enclose is a single-module Android app (Kotlin, Compose, MapLibre). You walk a
loop; when the loop closes you claim the enclosed area as a "territory". A new
claim **conquers** overlapping older claims — their geometry is carved away.
Everything works fully offline; sync to a backend is an unimplemented seam.

## Commands

```bash
./gradlew assembleDebug                 # build
./gradlew installDebug                  # build + install on a connected device
./gradlew testDebugUnitTest             # JVM unit tests
./gradlew testDebugUnitTest --tests "io.app.enclose.tracking.MotionGateTest"
./gradlew testDebugUnitTest --tests "*.MotionGateTest.driving speed is blocked on speed alone"
./gradlew lint                          # Android lint (no ktlint/detekt configured)
./gradlew connectedDebugAndroidTest     # instrumented tests (needs a device)
```

Gradle's configuration cache is on (`gradle.properties`); adding
configuration-phase side effects to build scripts will break it. `minSdk` is 35,
so a device/emulator on API 35+ is required.

`MotionGateTest` is the only meaningful test. `ExampleUnitTest.java` and
`ExampleInstrumentedTest.java` are leftover template files.

## Architecture

Layering runs `tracking` → `ui` → `data` → `sync`, with `geo` as a leaf used by
everything. Package = layer, under `app/src/main/java/io/app/enclose/`.

### Dependency wiring

`EncloseApp` is a hand-rolled service locator: it lazily builds the Room database
and the three repositories, and `MapLibre.getInstance()` runs in `onCreate`
(required before any `MapView` exists). ViewModels are `AndroidViewModel`s that
cast `application as EncloseApp` to reach repositories — there is no DI
framework, so new dependencies get added as a `by lazy` on `EncloseApp`.

### The walk → claim pipeline

This is the core flow and it spans four files:

1. `LocationService` (foreground service, `FOREGROUND_SERVICE_TYPE_LOCATION`)
   streams fused-location fixes and forwards each one — with accuracy, speed,
   monotonic timestamp, and the latest `ActivityMonitor` sample — to
   `TrackingManager.onLocation`.
2. `TrackingManager` is a stateful `object` holding the in-progress walk. It has
   **no Android or DB dependencies by design**, so it can be reasoned about and
   tested in isolation. It exposes `walk: StateFlow<WalkState>`,
   `pendingClaim: StateFlow<PendingClaim?>` and `voidEvents: SharedFlow<VoidReason>`.
3. Loops never close automatically. `WalkState.readyToClose` says whether
   stopping *now* would produce a valid loop (left the start zone, walked past
   the minimum perimeter, currently within the closing radius); pressing Stop
   calls `finishWalk()`, which either claims or abandons.
4. `EncloseViewModel` owns everything `TrackingManager` deliberately can't:
   starting/stopping `LocationService`, persisting, and reacting to
   `voidEvents` (the manager cannot stop the service itself).

Two consequences to preserve when editing:

- **Every closed loop is persisted immediately** as a `Walk` row, before the user
  decides whether to claim it (`init` block collecting `pendingClaim`).
  `confirmClaim` then re-saves it with `claimed = true` using the *same id*,
  which is also the `Territory` id — walks and territories are linked by id.
- **`confirmClaim` carves overlaps**: for every existing territory that
  `GeoClip.overlaps` the new ring, it subtracts the ring; an emptied territory is
  deleted, a reduced one is re-saved with recomputed area and `PENDING` status.

### Anti-cheat: `MotionGate`

A drive must not be able to enclose territory. `MotionGate` is plain testable
Kotlin (no Play Services types) combining two independent signals: the activity
classifier (via `ActivityMonitor`, which normalises Play Services
`DetectedActivity` into `MotionSample` and degrades to null when the permission
is denied) and sustained speed averaged over a window. The user's declared
`ActivityType` sets the speed ceiling; a confident classification may *raise* it
but never lower it, and nothing exceeds `ABSOLUTE_MAX_SPEED_MPS`.

Blocking is not immediately fatal: fixes are dropped and a warning shows, and
only after `GRACE_MS` does the walk become `Void`. Resuming more than
`MAX_RESUME_GAP_METERS` from where recording was suspended also voids the walk,
since the intervening ground was never recorded. The extensive KDoc in
`MotionGate.kt` explains why each threshold has the value it does — read it
before touching the numbers.

### Geometry conventions (`geo/`)

- **Rings are implicitly closed**: the last point is *not* a duplicate of the
  first. JTS conversion and GeoJSON export both add the closing point and strip
  it again on the way back. GPX/GeoJSON writers close explicitly per spec.
- `Territory.ring` is the original as-walked boundary and never changes.
  `Territory.polygons` (a `List<GeoPolygon>`, i.e. multipolygon with holes) is
  the **effective** claimed geometry, which shrinks/splits/gains holes as later
  claims carve into it. Rendering, area, and export use `polygons`; GPX uses
  `ring`.
- `Geo` uses an equirectangular projection around the mean latitude plus the
  shoelace formula — accurate enough at city-walk scale, and `GeoClip` projects
  the same way before handing geometry to JTS (which is planar). `GeoClip` runs
  `buffer(0)` to repair self-intersections from GPS noise and returns the
  original input unchanged on any failure.

### Persistence

Room (KSP), database version 5, three entities: `territories`, `walks`, and a
single-row `profile` (`id = "me"`, auto-created with a random guest name on
first access). Geometry is stored as **hand-rolled JSON strings** in
`ringJson`/`geometryJson`, converted in the entity companions — the only Room
`TypeConverter` is for `SyncStatus`.

`fallbackToDestructiveMigration(dropAllTables = true)` is set: **any schema
change wipes user data.** That is intentional pre-release, but bumping the
version silently destroys local territories, so say so when you do it.

### Sync

`RemoteSyncApi` is the backend seam; `NoBackendSyncApi` is wired in
`EncloseApp` and accepts nothing, so rows stay `PENDING` forever and the app
stays fully functional offline. `SyncScheduler.requestSync` enqueues a
network-constrained `SyncWorker` after every mutation. Implementing a backend
means replacing that one binding — the DB, worker, and scheduler don't change.
Note that `WalkRepository` has `pending()`/`markSynced()` but nothing currently
uploads walks.

### UI

- **Navigation is a plain state switch, no navigation library.** Adding a
  destination means touching three places: the `Screen` sealed interface, its
  `ScreenSaver` entry (needed so rotation/process death doesn't dump the user
  back on the map), and the `when` in `MainActivity`. One-shot hand-offs from
  the detail screen back to the map (focus a territory, delete-with-undo) are
  passed as nullable `pending*` params with an `on*Consumed` callback.
- `EncloseMap` wraps MapLibre's `MapView` in an `AndroidView`, forwarding the
  Compose lifecycle. State reaches the map by updating four named
  `GeoJsonSource`s (claimed polygons, closing zone, live path, start anchor);
  camera actions go through the imperative `MapController` handle, which
  exposes `isStyleLoaded`/`canLocate` so the UI can disable controls that would
  otherwise silently no-op. Basemaps are free OpenFreeMap styles — no API key.
- `BasemapStyle` is deliberately **independent of the app theme**: light/dark
  legibility outdoors is a different question from whether the user wants a dark
  app. It's persisted in `SharedPreferences` alongside `ActivityType` and the
  "seen intro" flag.
- Theming: `EncloseTheme` sets fully explicit M3 light/dark schemes (M3 baseline
  generation was rejected — it mixed the brand purple with stock lavender greys)
  plus `LocalEncloseAccents`, a `CompositionLocal` of semantic colors (trail,
  anchor, closing zone, GPS quality) shared by both Compose chrome and the map
  overlays so the two can't drift.
- Shared widgets live in `UiKit.kt`, formatters in `Format.kt`. Check both
  before writing a new card, tile, dialog, or unit formatter.

### Test mode

A dev affordance on the map: taps inject points instead of GPS. It uses relaxed
distance thresholds (`TrackingManager`'s `*_TEST_METERS` constants), skips
starting `LocationService`, and bypasses `MotionGate` entirely — tapped points
teleport and would always read as a vehicle.
