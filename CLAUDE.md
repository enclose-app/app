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

Unit tests cover the pure logic that decides what happens to user data:
`MotionGateTest` (anti-cheat thresholds), `ConquestTest` (what a new claim does
to older ones), `CoverageTest` (per-city percentages), `TrackingManagerRestoreTest`
and `WalkProgressRecorderTest` (surviving process death). `ExampleUnitTest.java`
and `ExampleInstrumentedTest.java` are leftover template files.

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
- **`confirmClaim` carves overlaps** via `Conquest.carve` (pure, tested): a
  partly-covered territory is re-saved with reduced geometry and recomputed area;
  a completely covered one is marked **conquered**, not deleted. The whole result
  is written with `repository.applyClaim`, which is one Room `@Transaction` —
  carving is justified by the new claim, so the two must never land apart. The
  JTS work runs on `Dispatchers.Default`; it is far too slow for the frame clock.

### Nothing the user walked for is ever destroyed

Three separate paths used to lose territories, and all three are now closed.
Treat this as a standing constraint rather than a past fix:

- **Conquest archives, never deletes.** A swallowed claim keeps its `ring` and
  the geometry it held when it fell, plus `conqueredAtEpochMs`/`conqueredById`.
  `observeActive()` hides it from the map; `observeConquered()` feeds the
  "Fallen claims" history on the profile screen. Only an explicit user delete
  (which has an undo) removes a row.
- **The claim write is atomic** — see `applyClaim` above.
- **The walk in progress is on disk** — see below.

A walked territory can't be re-created from the couch, which is what makes these
different from ordinary state. Weigh any change here accordingly.

### Surviving process death

`LocationService` is `START_STICKY`, so the system restarts it after a
low-memory kill — but `TrackingManager` is an in-memory `object`, so it comes
back empty and its `isTracking` guard silently drops every subsequent fix while
the notification still claims to be recording.

`WalkProgressRecorder` therefore mirrors the path to `walk_progress` /
`walk_progress_points` as it is walked (append-only: one small insert per fix,
so cost doesn't grow with the length of the walk). It watches `TrackingManager`
from the outside, which is what keeps the manager free of Android and DB types.
On restart `LocationService.beginRecording` rehydrates via
`TrackingManager.restore` and calls `recorder.adopt(...)` so the restored path
isn't written twice; with nothing to restore it stops itself rather than burning
battery on fixes nobody will keep.

`restore` recomputes distance, `hasLeftStart` and `canCloseLoop` from the path
rather than storing them, so restored state can't disagree with its own points.
`readyToClose` deliberately starts false — the last stored point says where the
walker *was*, not where they are.

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

Room (KSP), database version 8, five entities: `territories`, `walks`, a
single-row `profile` (`id = "me"`, auto-created with a random guest name on
first access), and the `walk_progress` / `walk_progress_points` pair backing the
walk in progress. Geometry is stored as **hand-rolled JSON strings** in
`ringJson`/`geometryJson`, converted in the entity companions — the only Room
`TypeConverter` is for `SyncStatus`.

`TerritoryDao` and `WalkProgressDao` are abstract classes rather than interfaces
so they can host `@Transaction` methods.

**There is no destructive-migration fallback, and none may be added.** A
territory is a walk someone went out and did — it can't be re-entered from the
couch — so dropping tables to land a schema change is never an acceptable
trade, pre-release included.

The rule that follows: **every version bump ships a `Migration`.**
`EncloseDatabase` has three worked examples — `MIGRATION_5_6` (add a column),
`MIGRATION_6_7` (add nullable columns), `MIGRATION_7_8` (create tables, with the
`CREATE TABLE` copied verbatim from the exported JSON so Room's validation
passes). Without one, Room
throws when opening the database instead of quietly emptying it — loud in
development, and impossible to silently lose data in the field.

Schemas are exported to `app/schemas/` (`exportSchema = true` plus the
`room.schemaLocation` KSP arg) and checked in, so each migration can be written
against the real previous schema. Bump the version and the next build writes the
new JSON alongside it; commit that with the migration.

### City tagging

`Territory.city` is filled in by reverse geocoding *after* the claim is saved —
never before, since geocoding needs a network and claiming must not. Blank means
unresolved, and `CityTagger.backfill()` (mutex-guarded, shared via `EncloseApp`,
triggered from `ProfileViewModel.init`) catches up on anything walked offline.
`CityResolver` wraps the platform `Geocoder`, so there is no API key and no
extra dependency, and it returns null rather than failing when the device has no
geocoder or no network.

The profile screen's headline percentage is **per city** (`Coverage.byCity`,
pure and unit-tested in `CoverageTest`): claimed area over the bounding box of
that city's claims. Measuring across all claims at once — as it did originally —
collapses towards zero the moment someone walks in a second city, because the
box between two cities is mostly countryside.

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
