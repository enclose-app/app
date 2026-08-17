# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this app is

Enclose is a single-module Android app (Kotlin, Compose, MapLibre). You walk a
loop; when the loop closes you claim the enclosed area as a "territory". A new
claim **conquers** overlapping older claims — their geometry is carved away.
Everything works fully offline; sync to a backend is an unimplemented seam.

## Skills

Three project skills in `.claude/skills/` carry the procedures that are easy to
get wrong. Prefer them over improvising:

- **`room-migration`** — any change to an entity, column, or table. The no-data-loss
  policy is strict and the procedure has steps that fail silently if skipped.
- **`verify`** — build, test, lint, and how to read the results.
- **`device-check`** — install, mock GPS, read the on-device DB, and simulate the
  process kill the walk-restore path exists for.

## Commands

```bash
./gradlew testDebugUnitTest assembleDebug   # the everyday check
./gradlew installDebug                      # build + install on a connected device
./gradlew testDebugUnitTest --tests "io.app.enclose.data.ConquestTest"
./gradlew testDebugUnitTest --tests "*.ConquestTest.a claim never conquers itself"
./gradlew lintDebug                         # Android lint (no ktlint/detekt configured)
./gradlew connectedDebugAndroidTest         # instrumented tests (needs a device)
```

**Lint fails on this repo by design.** The baseline is 3 errors / 31 warnings,
all pre-existing `MissingPermission` in `ActivityMonitor.kt` (×2) and
`EncloseMap.kt`. Those three are not yours and are not to be "fixed" in passing;
a fourth is. Compare by file and issue id, never line number — see the `verify`
skill.

Gradle's configuration cache is on (`gradle.properties`); adding
configuration-phase side effects to build scripts will break it. `minSdk` is 35,
so a device/emulator on API 35+ is required.

## Testing conventions

Unit tests are plain JVM JUnit4 and there is **no Robolectric**, so nothing
touching `Context`, `SharedPreferences`, Room, or Play Services can be unit
tested. The established response is to *extract the decision into pure Kotlin and
test that*, leaving the Android shell thin:

| Pure unit | Tested by | Kept testable by |
|---|---|---|
| `MotionGate` | `MotionGateTest` | normalising Play Services types into `MotionSample` |
| `Conquest` | `ConquestTest` | taking domain objects, returning what changed |
| `Coverage` | `CoverageTest` | no Android or DB types |
| `Place` | `PlaceTest` | separated from the `Geocoder` call |
| `WalkProgressRecorder` | `WalkProgressRecorderTest` | the `WalkProgressStore` interface seam |
| `TrackingManager.restore` | `TrackingManagerRestoreTest` | manager has no Android/DB deps |
| `ElevationAccumulator` | `ElevationAccumulatorTest` | altitude in, metres out — no Location |
| `PauseTracker` | `PauseTrackerTest` | takes the normalised `MotionSample` |
| `Passport` | `PassportTest` | takes domain objects only |
| `OfflineTilePlanner` | `OfflineTilePlannerTest` | policy split from the MapLibre calls |
| `PanelSummary` | `PanelSummaryTest` | status/action decided once, drawn three ways |
| `LocationReadiness` | `LocationReadinessTest` | permission/services flags in as plain Booleans |
| `Polyline` | `PolylineTest` | a codec over Strings, no transport |
| `RouteSimplify` | `RouteSimplifyTest` | points in, points out |
| `SnapPolicy` | `SnapPolicyTest` | geometry only, no storage or network |
| `SnapDisplay` | `SnapDisplayTest` | takes a `Territory`, returns what to draw |
| `SnapTagger` | `SnapTaggerTest` | the `SnapStore` + `RouteMatcher` seams |
| `FixWatch` | `FixWatchTest` | counts and an accuracy, no clock of its own |
| `TrackingManager.reportRecordingUnavailable` | `TrackingManagerRecordingFailureTest` | manager has no Android/DB deps |
| `WindowLayoutPolicy` | `WindowLayoutPolicyTest` | window size in as plain Ints, controls as an enum |
| `SplitScreenSupport` | `SplitScreenSupportTest` | `Build` fields passed in, not read |
| `ActivityType.resolve` | `ActivityTypeTest` | the stored name is just a String |
| `Mvt` | `MvtTest` | bytes in, lines out — the test writes its own tiles |
| `SlippyTile` | `SlippyTileTest` | tile arithmetic with no map library |
| `WalkableWays` | `WalkableWaysTest` | tags in as a plain `Map<String, String>` |
| `PathGraph` | `PathGraphTest` | ways in, junctions out |
| `LoopPlanner` | `LoopPlannerTest` | a graph, a target and a seed — no clock |
| `PastRoutes` | `PastRoutesTest` | domain objects only |
| `RouteSuggester` | `RouteSuggesterTest` | the `WalkableArea` seam |
| `DistanceMarkers` | `DistanceMarkersTest` | points in, marker positions out |

JTS is pure Java and works fine in JVM tests. `TrackingManager` is a singleton
`object`, so tests touching it must reset state in `@After`.

**What unit tests cannot reach:** the location service, the geocoder, the map,
migrations, and anything in `UserSettings`. When a change lands there, say so and
use `device-check` — don't imply the suite covered it. `ExampleUnitTest.java` and
`ExampleInstrumentedTest.java` are leftover template files.

## Architecture

Layering runs `tracking` → `ui` → `data` → `sync`, with `geo` as a leaf used by
everything. Package = layer, under `app/src/main/java/io/app/enclose/`.

### Dependency wiring

`EncloseApp` is a hand-rolled service locator: it lazily builds the Room database
and everything shared — the territory, walk, profile and walk-progress
repositories, `UserSettings`, `CityResolver`, `CityTagger` — and
`MapLibre.getInstance()` runs in `onCreate` (required before any `MapView`
exists). ViewModels are `AndroidViewModel`s that cast `application as EncloseApp`
to reach them; there is no DI framework, so new dependencies get added as a
`by lazy` there. Shared singletons matter here beyond convenience: one
`CityResolver` means one geocoder cache, and one `CityTagger` means competing
backfills can't run.

`applicationScope` is for work that must outlive the component that started it —
clearing the finished walk while `LocationService` is being torn down, whose own
scope would cancel it halfway.

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

**Starting a walk is checked, not assumed.** `startWalk()` used to flip
`isTracking` and hope: if the service then couldn't subscribe to location it
stopped itself in silence, leaving a walk on screen whose path could never grow
and whose only exit was Stop-and-discard. Four rules close that off, and each one
covers a case the others don't:

- **`LocationReadiness`** (pure, tested) is the single value for "can a fix be
  recorded, and if not, why not". It exists because two of its cases were
  previously read as plain "granted". *Approximate* location is off by hundreds of
  metres, which is past `TrackingManager.MAX_ACCURACY_METERS`, so every fix was
  discarded before it could anchor a path — precise (`ACCESS_FINE_LOCATION`) is
  therefore required, and coarse-only is a recovery state, not a grant. And with
  the device's **location switch off**, subscribing *succeeds* and then never
  delivers a callback, so nothing throws and nothing reports; it has to be read
  from `LocationManager` up front. `PanelSummary` turns each case into its own
  repair — the app's settings page for Precise, the device's for the switch —
  because offering the wrong one is a button that does nothing.
- **`TrackingManager.reportRecordingUnavailable`** is how `LocationService` says
  it can't run instead of stopping quietly. What that costs follows the same
  asymmetry as everything else here: a walk with **nothing** recorded drops back
  to idle (there is nothing to lose, and leaving it running is the trap), while a
  walk that has **already recorded ground** stays up so Stop can still claim it.
  A fix arriving is what clears the flag — not the notice being dismissed.
- **`FixWatch`** (pure, tested) decides when "acquiring…" has stopped being a
  plausible thing to say: after 30 s with an empty path it says either *no fix at
  all* or *fixes arriving and all too vague to keep*. Those need different things
  from the user, and telling someone to go outside when the real problem is
  approximate location costs them the walk.

None of this is reachable from a unit test end to end — the permission reads, the
`LocationManager` and the fused provider all need `device-check`.

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

**RUN and BIKE are currently turned off** (`ActivityType.available`): their chips
show greyed, and `ActivityType.resolve` sends a stored-but-unavailable choice
back to WALK so nobody walks under a ceiling they can't see. Everything else
about them is intact — including the classifier's ability to *detect* a run or a
ride and raise the ceiling — so re-enabling is that one flag.

**Three strikes, not one.** Blocking is not fatal, and neither is staying
blocked. Movement rejected for longer than `GRACE_MS` (90 s) costs the walk a
**strike** (`Verdict.Strike`); only the `MAX_STRIKES`th returns `Verdict.Void`.
`bankStrike()` clears the speed window and the countdown, so carrying on costs
another full window rather than a strike per fix — roughly four and a half
minutes of sustained vehicle movement before a walk dies. The asymmetry is the
reason: a driver caught on the third strike still claims nothing, while a walker
caught wrongly on the first loses hours that can't be re-walked. `WalkState.strikes`
drives the count the panel shows; the gate keeps one counter, so the resume-gap
check below spends from the same three.

Resuming from a blocked stretch is graded rather than fatal: under
`MAX_RESUME_GAP_METERS` (150 m) costs nothing, beyond it costs a strike and flags
`hadSignalGap`, and past `MAX_UNVERIFIED_GAP_METERS` (1 km) still voids outright —
that isn't a walk with a hole in it, it's two walks with a drive between them.

`clearSpeedWindow()` exists so the signal-gap path can drop the speed history
without wiping strikes: silence is not evidence of speed, but it is not an
amnesty either.

The extensive KDoc in `MotionGate.kt` explains why each threshold has the value
it does — including why the ceilings were raised once and why
`ABSOLUTE_MAX_SPEED_MPS` was raised least. Read it before touching the numbers.

**Silence is not evidence of speed.** A backgrounded or dozing device stops
delivering fixes and then hands the missed stretch over in a burst on wake. Three
rules in `TrackingManager.onLocation` keep that from being mistaken for a drive,
and they are load-bearing:

- Fixes worse than `MAX_ACCURACY_METERS` return **before** the gate runs. A
  reacquisition fix lands hundreds of metres out; feeding it to the speed window
  was on its own enough to void an honest walk.
- Losing the signal has **two shapes, and only one of them is silence.** More
  than `SIGNAL_GAP_MS` with no fix covers the dozing device. The commoner one is
  a *frozen* fix: the provider keeps reporting the last position it was sure of,
  at the normal interval, then snaps to the true one — nothing looks wrong until
  the snap, so the silence rule never sees it. That is caught instead by a
  segment faster than `REACQUISITION_SPEED_MPS` (55 m/s ≈ 200 km/h), which no
  road vehicle reaches, so ordinary driving is still judged as driving. Both
  shapes reset the speed window and the grace countdown, drop the speed baseline
  so the jump never becomes a sample, and flag `WalkState.hadSignalGap`.
- Neither clears `blockedReason` — a walk already being rejected as a vehicle
  when the signal went still has to answer for the ground in between, which is
  what keeps the anti-cheat honest across a gap.
- The gap is **reported, not punished**: the live panel and the claim dialog say
  part of the route is a straight line across unobserved ground. Discarding an
  hour on foot because the device went to sleep is the worse error by a wide
  margin — see "Nothing the user walked for is ever destroyed".

`LocationService` must timestamp each fix by its own `elapsedRealtimeNanos`, not
by delivery time, and must iterate `result.locations` rather than taking
`lastLocation`. Timing a batch by delivery reads as hundreds of metres per second;
taking only the newest throws the walked ground away.

### Geometry conventions (`geo/`)

- **Rings are implicitly closed**: the last point is *not* a duplicate of the
  first. JTS conversion and GeoJSON export both add the closing point and strip
  it again on the way back. GPX/GeoJSON writers close explicitly per spec.
- `Territory.ring` is the original as-walked boundary and never changes.
  `Territory.polygons` (a `List<GeoPolygon>`, i.e. multipolygon with holes) is
  the **effective** claimed geometry, which shrinks/splits/gains holes as later
  claims carve into it. **Area, conquest and export use `polygons`; GPX uses
  `ring`; rendering goes through `SnapDisplay`** — see "Snapping to real paths".
- `Geo` uses an equirectangular projection around the mean latitude plus the
  shoelace formula — accurate enough at city-walk scale, and `GeoClip` projects
  the same way before handing geometry to JTS (which is planar). `GeoClip` runs
  `buffer(0)` to repair self-intersections from GPS noise and returns the
  original input unchanged on any failure. `GeoClip.isSimpleRing` answers the
  same question about geometry that came from somewhere else.

### Snapping to real paths

A recorded loop wobbles across buildings and cuts corners it didn't cut, so a
claim's outline can be matched onto real roads. Four constraints shape all of it,
and none of them are incidental:

- **Display only.** `Territory.ring` stays the boundary of record, `areaSqMeters`
  is measured from it, and `Conquest` carves with it. A footpath missing from the
  map must never shrink what someone owns. `SnapDisplay` is a named object rather
  than an extension property precisely so a leak into `Conquest`, `Coverage`,
  `Passport` or `OfflineTilePlanner` is visible in a diff. **Export stays raw,
  GeoJSON as well as GPX** — `toGeoJson` writes the area into `properties`
  beside the geometry, and shipping a shape whose stated area disagrees with it
  is worse than shipping the honest one.
- **After the claim, never during the walk.** Live snapping is an anti-cheat
  hole: a drive down a road map-matches to that road perfectly, which is exactly
  the signal `MotionGate` exists to reject. Do not "improve" this into a live
  feature.
- **A carved claim stops using its matched outline** (`Territory.carvedAtEpochMs`,
  stamped by `Conquest.carve`). The matched ring describes the *whole* loop as
  walked, and matching is opt-in and needs a network, so it can arrive weeks after
  a rival already took part of that loop — `Conquest` only revisits a claim when a
  new walk overlaps it, so there would be nothing to correct it.
- **Opt-in, and it never bulk-uploads by itself.** `UserSettings.snapToPaths` is
  off by default. This is the only feature that sends a precise record of where
  someone walked anywhere, so turning it on covers new claims only; existing ones
  need the explicit button that states how many walks it would send.

`snappedAtEpochMs` is nullable and separate from the ring on purpose: it
distinguishes *never asked* from *asked and refused*, without which a loop round a
park — which has no roads to match and is refused every time — would be
re-uploaded on every backfill forever.

**No matching host is bound.** `RouteMatcher` is the seam and `NoRouteMatcher` is
what `EncloseApp` wires, in the same idiom as `RemoteSyncApi`/`NoBackendSyncApi`,
because there is no free, key-less, terms-clean map-matching endpoint and this app
ships to Play. `RouteMatcher`'s KDoc carries what an implementation owes its
caller, including the two wire traps (Valhalla encodes polylines at 1e6, not 1e5;
`trace_route` re-routes through GPS gaps and returns one shape per leg with
duplicated vertices).

### Suggested routes

Ask for a distance, get a loop to walk **from where you are standing**; press
again for a different one; accept it and it is drawn faintly under the walk while
you follow it. The map control is `MapControl.PLAN`; the sheet is
`RoutePlannerSheet`; the state machine is `RoutePlan` in `EncloseViewModel`.

Seven constraints, none of them incidental:

- **Every suggestion starts from a *fresh* fix.** Not the camera centre, not
  home, not the last walk — and specifically **not `currentLocation()`**, which
  is the last known fix and therefore survives across sessions. Planning from a
  stale one produces a real, correct route drawn around wherever the phone last
  saw sky, off screen, looking exactly like a broken feature: found on an
  emulator, whose last known location is Mountain View until the first mock fix
  lands, and the same thing happens to anyone who opens the app indoors after
  travelling. `MapController.recentLocation(FRESH_FIX_MS)` is what the sheet
  passes, aged on `elapsedRealtimeNanos`, and no usable fix is its own reported
  outcome (`RouteUnavailable.NO_FIX`). A previous walk is only offered when its
  near end is within `PastRoutes.MAX_START_METERS`, and a planned loop is refused
  outright when there is no mapped path within `PathGraph.NEAR_START_METERS`.
- **No motorways, no trunk roads.** `WalkableWays` is an **allowlist** of road
  classes, not a blocklist, because tiles gain classes over time and the failure
  mode of guessing wrong points one way. `access`/`foot` restrictions are obeyed,
  `foot` overrides `access`, and everything else is priced rather than banned —
  steps and main roads are walkable, just expensive (`comfort` is a cost
  multiplier, so a park path has to be under a third longer to win).
- **The road network comes out of the basemap the app already draws.** There is
  no routing host and no API key: `Mvt` (a hand-rolled vector-tile reader, in the
  same idiom as `Polyline` and `GpxImporter`) decodes the `transportation` layer
  of OpenFreeMap tiles, and `PathGraph` rebuilds a network from it. This is why
  the feature could be built at all where `RouteMatcher` is still deliberately
  unbound — **it reads tiles rather than uploading a route**, so nothing about
  where anyone walked leaves the device; the only thing a server learns is which
  ~4 km tile someone asked about.
- **Zoom 13, capped and cached.** A zoom 14 tile is ~700 KB; zoom 13 covers four
  times the ground for a quarter of that with the same streets and paths,
  generalised to about a metre. `OpenFreeMapWalkableArea` caps a search at
  `MAX_TILES` and caches decoded tiles so *shuffling costs nothing* — which is
  the point, given `OfflineTilesWorker` refuses to spend mobile data at all. The
  zoom, the cap and the cache are the feature, not tuning. `RouteSuggester`
  caches the built graph too, for the same reason and the ART one: turning a
  city's tiles into a network measured 150 ms on a desktop JVM, and this codebase
  has already learned once what that figure is worth on a device.
- **Three properties of vector tiles have to be undone before anything routes**,
  and all three fail the same silent way — no route found, and nothing on screen
  to say why. All three were found by running the pipeline over a real tile, and
  each is pinned by a test in `PathGraphTest`:
  1. Tiles **clip roads at their own edges**, so `Mvt` cuts at the boundary
     **interpolating**, not dropping vertices — a dropped vertex leaves a gap the
     width of a whole segment — and `PathGraph` snaps ends within `SNAP_METERS`.
  2. Tiles are **simplified for drawing**, which removes the junction vertex
     where a side street meets a road running straight past it. Vertex-to-vertex
     snapping alone therefore finds no T-junctions at all (measured: 3 000
     disconnected fragments, no loop findable anywhere), so a vertex lying within
     `TOUCH_METERS` of another way's *segment* splits it. `WalkableWay.level`
     (from `layer`/`brunnel`) is what stops that welding a footbridge to the road
     beneath it; ways sharing a vertex join regardless of level, which is how a
     bridge meets what it lands on.
  3. A tile carries **fragments** — a service road whose link was simplified
     away, a path drawn inside a park. One of them happened to hold the two nodes
     nearest the middle of the test tile, so `nearestNode` handed the planner a
     two-node island and every search died over an otherwise healthy graph.
     Fragments are dropped at build time, by a **relative** rule
     (`ISLAND_FRACTION` of the largest component): five junctions beside four
     thousand is a stray, five junctions and nothing else is a village.

  Runs of degree-2 vertices are then contracted into one edge that keeps its
  polyline, so the search sees junctions while the drawing keeps every bend.
- **Evidence before guesswork.** `PastRoutes` offers loops the walker has already
  closed, ahead of anything generated: a walked route has no missing pavement and
  no locked gate, and it closed once already. Generated loops are biased towards
  claimed ground by `FamiliarGround`, which is a **discount, not a requirement** —
  insisting on familiar ground would mean one claim yields one suggestion
  forever. `LoopPlanner` is two shortest paths (out, then back with the outbound
  edges charged `RETRACE_PENALTY` times over), with the radius refined against
  the measured length; seeds are spread by the golden angle so consecutive
  presses go somewhere visibly different, and the whole search is deterministic
  per seed so a rotation can't quietly swap the route out.
- **A route on the map hides the claims.** While `plannedRoute` is non-empty
  `EncloseMap` draws no territories at all: the route is a thin line through
  streets and the claims are filled polygons over exactly the ground it crosses,
  so together they are unreadable and the one you need is the one you haven't
  walked. They come back the instant the route goes — cleared by hand, or by the
  walk ending, which is why **all three** endings clear it (`stopWalk`,
  `cancelWalk`, and the `voidEvents` collector; the last was the one that used to
  leave a route drawn over a map with nothing on it). Decided inside `EncloseMap`
  rather than by its callers so the full screen and the floating window can't
  disagree.
- **A suggestion is drawn while it is being considered, not only once taken.**
  Choosing between loops described only as "4.6 km, new ground" is choosing
  blind; the map is the answer to "do I want to walk that?". So `MapScreen`
  draws the previewed route (falling back to the accepted one) and frames it
  with `fitTo(route, bottomInsetPx)` in the half of the map the sheet isn't
  covering — the sheet reports its own measured height for that. Three rules
  follow: **dismissing the sheet keeps the suggestion** (looking at the map must
  not be the gesture that loses it), **Clear in the sheet header takes whatever
  is drawn off the map** — a suggestion being reviewed *or* an accepted route,
  since to the person looking at the map they are the same thing, the line on it
  — with holding the route control as the shortcut for the same (the Home/float
  idiom), and the sheet's result area has a fixed minimum height and scrolls. That last one is not cosmetic: content growing when the first
  suggestion arrives re-anchors the `ModalBottomSheet` and settles it to hidden,
  so the very first press dismissed the sheet that asked for it.
- **Accepting a route changes nothing about how a walk works.** It does not start
  the walk by itself (the same `location.canRecord` guard as the Start button
  runs, and test mode still warns), does not relax `MotionGate`, does not close
  the loop, and has no bearing on what gets claimed. Stop is still what closes a
  loop, exactly as everywhere else.

**Online only, and the only thing in the app that is.** `EncloseViewModel`
checks `NET_CAPABILITY_VALIDATED` before it does anything — up front rather than
after a timeout, and it withholds previously walked routes too, because a planner
that answered offline with history only would look like a broken shuffle button.

The accepted route is persisted (`UserSettings.plannedRoute`, an encoded
polyline) for one reason: a walk survives a low-memory kill, so the line being
followed has to survive with it. It is cleared when the walk ends.

### Persistence

Room (KSP), database version 12, six entities: `territories`, `walks`, a
single-row `profile` (`id = "me"`, auto-created with a random guest name on
first access), and the `walk_progress` / `walk_progress_points` pair backing the
walk in progress, and `offline_regions`. Geometry is stored as **hand-rolled JSON strings** in
`ringJson`/`geometryJson`, converted in the entity companions — the only Room
`TypeConverter` is for `SyncStatus`.

`TerritoryDao` and `WalkProgressDao` are abstract classes rather than interfaces
so they can host `@Transaction` methods.

**There is no destructive-migration fallback, and none may be added.** A
territory is a walk someone went out and did — it can't be re-entered from the
couch — so dropping tables to land a schema change is never an acceptable
trade, pre-release included.

The rule that follows: **every version bump ships a `Migration`.**
`EncloseDatabase` has seven worked examples — `MIGRATION_5_6` (add a column),
`MIGRATION_6_7` (add nullable columns), `MIGRATION_7_8` (create tables, with the
`CREATE TABLE` copied verbatim from the exported JSON so Room's validation
passes), `MIGRATION_8_9` and `MIGRATION_9_10` (a `NOT NULL` column needs a SQL `DEFAULT`
for existing rows even when the Kotlin property has one — a constructor default
is not a column default). Without one, Room throws when opening the database instead of quietly
emptying it — loud in development, and impossible to silently lose data in the
field. The `room-migration` skill has the full procedure.

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
geocoder or no network. A 10s timeout caps each lookup.

`resolvePlace()` returns a `Place` whose `city`/`area`/`country` fields are
**each independently nullable** — the geocoder routinely names a country but no
city. Show what resolved and omit what didn't; a placeholder row implies the
walk was missing something. `Place.groupingName` (`city ?: area ?: country`) is
the single name a claim is filed under, and `CityTagger` and `Coverage` depend on
that fallback order — `PlaceTest` pins it. The territory detail screen's Location
card seeds from the stored `city` so an offline visit still says something, then
only ever *adds* to it from the live lookup.

The profile screen's headline percentage is **per city** (`Coverage.byCity`,
pure and unit-tested in `CoverageTest`): claimed area over the bounding box of
that city's claims. Measuring across all claims at once — as it did originally —
collapses towards zero the moment someone walks in a second city, because the
box between two cities is mostly countryside.

### Remembered preferences

`UserSettings` (SharedPreferences, file `enclose_ui`) holds **every** preference:
seen-intro, activity type, basemap, territory sort, test mode, snap-to-paths, panel-collapsed,
floating-window, home, the planned route and the distance it was asked for, and the map camera. It exists as one class because the previous ad-hoc `prefs.getString(...)`
calls scattered through `EncloseViewModel` are exactly why the camera and two
toggles went unpersisted for so long — there was nowhere to notice the gap. Add
new preferences here, not inline.

Two non-obvious rules:

- `lastCamera()` is read fresh per composition, never cached in the ViewModel. A
  rebuild of the map re-reads it; a snapshot taken at construction would teleport
  the user back to wherever they were at launch. (Rotation no longer causes that
  rebuild — `MainActivity` handles `orientation`/`screenSize` itself so a
  multi-window resize can't tear down the GL map mid-walk — but process death and
  a style swap still do.) It is
  deliberately not a flow — the map owns the live camera and reports back via
  `saveCamera`, and feeding it back would fight the gesture that just moved it.
- Camera components are stored as separate floats, so a partial write reads as
  "no saved camera" rather than failing to parse.

The territory list's *search query* is deliberately transient: reopening the
list pre-filtered would hide claims.

Stats need no persistence work — every figure on the profile screen derives from
Room, so they are durable by construction.

### Offline tiles

The basemap streams from OpenFreeMap, so walking out of signal used to leave a
grey screen in an app that is otherwise strictly offline-first.
`OfflineTilePlanner` (pure, tested) decides **what**: one region per city with
claims, padded by 1.5 km and clamped to a 20 km span, because tile count grows
with area and unclamped claims spread across a country would be tens of
gigabytes. `OfflineTileCache` wraps MapLibre's callback API and holds no policy,
which is what keeps the decisions testable without a device.

Downloads run only from `OfflineTilesWorker`, constrained to **unmetered
network, storage-not-low, battery-not-low**. Spending someone's mobile data
because they claimed a loop would be indefensible, so if those are never
satisfied the download simply never happens and the map streams as before.

Eviction is **least-visited first**, tie-broken on the older visit, down to a
300 MB budget (`DEFAULT_BUDGET_BYTES`). A visit is counted when the map camera
settles inside a cached region, so the metric is where the user actually goes,
not where they once claimed something. Regions in the current plan are never
evicted — deleting one would only queue an immediate re-download.

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
- **The bottom panel folds.** `ControlPanel` renders either the full panel or a
  one-row `CollapsedPanel`, and the collapsed row always carries the primary
  action — the controls for the walk you're on can never be the thing that's
  hidden. Which of the two, and what each says, comes from `PanelSummary` (pure,
  tested), shared with the floating card so three surfaces can't describe the
  same walk differently. The choice persists in `UserSettings.panelCollapsed`;
  `WindowLayoutPolicy` can force it collapsed in a short window but never forces
  it open.
- **Multi-window.** `MainActivity` is `resizeableActivity`, `singleTask`, and
  declares `configChanges` for every dimension a resize touches — not for
  rotation but so entering split screen or PiP doesn't recreate the activity and
  tear down the GL map mid-walk. **No public API puts an app into split screen**:
  `FLAG_ACTIVITY_LAUNCH_ADJACENT` is documented as doing nothing unless already
  split, and Samsung's One UI is the one widespread build that honours it from
  full screen. `SplitScreenSupport` (pure, tested) decides whether asking is
  worth it; the map's split control is **hidden entirely where it isn't**, and
  where it is, the request is followed by the Recents explanation if the window
  hasn't changed within `SPLIT_SETTLE_MS`.
- **Landscape is the compact layout, not a second one.** A landscape phone is
  ~360–400 dp tall, under `COMPACT_HEIGHT_DP`, so it folds the panel and moves
  zoom to the left rail by the same rules split screen uses. What landscape does
  need on its own is **insets**: the app is edge-to-edge, and rotating moves the
  display cutout and the 3-button nav bar to the *sides*, where `statusBarsPadding`
  and `navigationBarsPadding` don't reach. Everything floating over the map takes
  `WindowInsets.safeDrawing.only(...)` with the sides it actually needs — the
  rails take horizontal only, because the panel's measured height already carries
  the bottom inset and adding it twice pushes the rail up a bar's worth. The
  panel is capped at `PANEL_MAX_WIDTH` so it doesn't become a metre-wide strip.
- **There is no ⋮ app menu.** The explainer, test mode and GPX import live in the
  profile screen's *App* section, behind the avatar; the map's chrome is only for
  things reached for mid-walk. A ⋮ button appears **only** when
  `ControlLayout.menu` is non-empty — i.e. when a window is so short that even
  both rails can't hold the map controls — so a control can never become
  unreachable, and an empty menu never takes up the corner. `GpxImportDialogs`
  and `HowItWorksSheet` are `internal` because both screens can now show them:
  the import outlives the screen that started it.
- **The map's controls are data, not hard-coded buttons.** `MapControlSpec`
  describes each one once; `WindowLayoutPolicy.placeControls` (pure, tested)
  decides whether it's drawn on the right rail, the left rail or in the ⋮ menu,
  from the height actually left between the top row and the panel. Nothing is
  ever resized to fit — 48 dp is the accessibility minimum — so a short window
  (split screen) gives ground in three steps: **zoom moves to the left edge**,
  then **the left rail takes the overflow too** (lowest priority first), and only
  what neither rail can hold goes to the ⋮ menu. That middle step was missing and
  it showed on exactly the windows the policy exists for — a split-screen half
  put three controls in the menu while the left edge held two zoom buttons and a
  hand's worth of nothing. A control on the far rail is one press; one in the
  menu is two and has to be found first. Each list keeps the rails' drawing
  order, so growing the window puts a control back where it was instead of
  reshuffling the stack. The left rail clears `ORNAMENT_CLEARANCE` so it never
  covers the OSM attribution, and the right rail's height gives up
  `COMPASS_CLEARANCE_DP` for the map's own compass — the rail grows *upward* from
  the panel, so without it the topmost button climbs into the compass on a short
  window, and the compass is how a rotated map gets back to north.
- **Floating window (PiP)** and split screen are both **map controls, at the top
  of the right-hand rail** — one button each, not menu items. Floating follows
  the home button's idiom: tap floats now and arms the automatic float, hold
  disarms it (`UserSettings.floatingWindow`). Auto-enter is armed *only while a
  walk is running*: a window that appears over whatever the user switched to has
  to be earning its place. In PiP the whole app is replaced by
  `FloatingWalkCard` — the map, following the walker, with one line of figures
  over the top. Two rules there: it never passes `onCameraIdle`, so chasing the
  walker round a tiny window can't overwrite the framing the user set up on the
  real map; and the figures sit at the *top*, because the bottom-left corner is
  the OpenStreetMap attribution. PiP sends touches to the system, so the card is
  a read-out, not a control panel.
- `EncloseMap` wraps MapLibre's `MapView` in an `AndroidView`, forwarding the
  Compose lifecycle. State reaches the map by updating seven named
  `GeoJsonSource`s (claimed polygons, closing zone, the suggested route, live
  path, start anchor, the saved home, and the kilometre markers). The suggested route is drawn dashed,
  half-transparent and **under** the trail on purpose — it is the walk you were
  offered, and once you set off it is the walked line that matters; drawn as
  boldly as the trail it would hide how far round you had got. Camera actions
  go through the imperative `MapController` handle, which
  exposes `isStyleLoaded`/`canLocate` so the UI can disable controls that would
  otherwise silently no-op.
- **Kilometre markers are numbered bitmaps, not map labels.** `DistanceMarkers`
  (pure, tested) interpolates a point into the segment each whole kilometre falls
  in, rather than picking the nearest fix — across a signal gap the path is one
  straight segment kilometres long, and "nearest fix" would put one marker on it
  or none. The badges are drawn in code (`MilestoneMarker.kt`) and registered
  with `style.addImage` for the same reason the home marker is, plus one more
  that decides it: **a `text-field` renders from glyphs the basemap serves over
  HTTP**, so a walk with no signal would get dots with no numbers on them, which
  is the walk they are most use on. Two consequences to keep: images belong to
  the style, so `Overlays` counts how many are registered and starts again at
  zero when a basemap swap rebuilds it; and the colour is *baked into the bitmap*,
  so `Overlays.milestoneColors` re-paints every badge when the theme changes
  without the style being rebuilt — which is what happens whenever the basemap has
  been pinned to light or dark by hand. Below `MILESTONE_MIN_ZOOM` they aren't
  drawn at all: a kilometre is ~30 px there, and the marks would cover the trail
  they mark.
- **The map follows the walker.** `MapController.followUser` turns on when a walk
  starts and when the recenter button is pressed, and off the instant the user
  pans — detected via `REASON_API_GESTURE`, so the app's own fly-to animations
  don't switch it off on their first frame. Following uses `panTo` (centre only,
  no zoom change): re-zooming every few seconds would take the choice of how much
  ground to see away from the user. The location component stays on
  `CameraMode.NONE` deliberately — following is driven from Compose off the same
  walk state everything else reads, and MapLibre's own tracking mode would put a
  second animator on the camera that knows nothing about the walk. Basemaps are free OpenFreeMap styles — no API key.
- `BasemapStyle` is deliberately **independent of the app theme**: light/dark
  legibility outdoors is a different question from whether the user wants a dark
  app.
- Theming: `EncloseTheme` sets fully explicit M3 light/dark schemes (M3 baseline
  generation was rejected — it mixed the brand purple with stock lavender greys)
  plus `LocalEncloseAccents`, a `CompositionLocal` of semantic colors (trail,
  anchor, home, closing zone, GPS quality) shared by both Compose chrome and the
  map overlays so the two can't drift. The home marker is built in code
  (`HomeMarker.kt`) rather than shipped as a drawable for exactly that reason —
  its fill is `accents.home`, the same value the home button is tinted with — and
  it is registered with `style.addImage` inside `installOverlays`, since a
  light/dark swap builds a new style and an image added to the old one goes with
  it.
- Shared widgets live in `UiKit.kt`, formatters in `Format.kt`. Check both
  before writing a new card, tile, dialog, or unit formatter.

### Test mode

**It does not exist in a release build.** `EncloseViewModel.devToolsAvailable`
(`BuildConfig.DEBUG`) hides the switch in the profile screen's *App* section, and
`setTestMode` refuses to turn it on regardless — the stored preference is also
read as false there, so a `true` carried over from a debug build or a restored
backup can't survive into a shipped one as a walk that silently never starts the
location service. `buildConfig = true` in `app/build.gradle.kts` is what makes
`BuildConfig` exist at all; AGP stopped generating it by default in 8.0.

**The UI otherwise doesn't advertise it.** The explainer sheet, the
permission-recovery block, the top-row status chip and the panel's copy have all
had it stripped, because the mode is expected to be removed. Anything added here
stays behind that one switch — don't re-introduce it into user-facing copy.

A dev affordance on the map: taps inject points instead of GPS. It uses relaxed
distance thresholds (`TrackingManager`'s `*_TEST_METERS` constants), skips
starting `LocationService`, and bypasses `MotionGate` entirely — tapped points
teleport and would always read as a vehicle.

**Start asks first while it's on.** `TestWalkWarningDialog` stands between the
panel's Start button and `startWalk()`, because a test walk is indistinguishable
from a real one on screen — same panel, same figures, same Stop — while recording
nothing of where the user actually went, and that is only discoverable at the end,
when the route is gone. The dialog leads with "Turn it off & start" and re-runs
the `location.canRecord` guard on that branch: test mode is exactly the state in
which `PanelSummary` offers Start without checking location, so the real walk
underneath it may still need a permission prompt. Map taps don't warn — tapping
the map *is* the unambiguous request for an injected point.

**GPX import** (`EncloseViewModel.importGpx`) rides the same injection path, fed
from a route recorded elsewhere rather than tapped out: same relaxed thresholds,
same gate bypass, same rule that the loop is only closed when the user presses
Stop.

**Import is not test-mode-gated, and works in every build.** Recording with a
watch or a health app and claiming the loop afterwards is a real way to use this,
so it has to survive into the build where test mode doesn't exist. Two doors, one
path: the picker in the profile screen's *App* section, and a track shared or
"opened with" from another app (Samsung Health → share → Enclose), which
`MainActivity`'s SEND/VIEW filters accept and import on arrival. The cost is
stated rather than hidden: replayed points carry no timestamps, so **a GPX of a
drive will claim territory**. If a backend or a leaderboard ever lands, this is
the first hole to close.

Three rules hold the widened path together:

- **A GPS walk is never thrown away for an import.** `importGpx` refuses while
  one is running rather than cancelling it (the old KDoc's "in test mode this can
  only be a tapped route" reasoning stopped being true). Another *injected* walk
  is still abandoned, since nothing there was walked.
- **`EncloseViewModel.injectedWalk` is what says "no GPS behind this walk"**, not
  `testMode` — an import runs outside test mode, and a walk started with the
  switch in one position can be stopped with it in the other. It decides whether
  `LocationService` is started and stopped, and the panel reads it to stand the
  GPS accuracy chip and the signal-gap notice down; left on `testMode`, an
  imported walk shows "acquiring…" for a receiver that was never switched on.
- **A share is state, not an event.** `MainActivity.sharedTrack` is set from
  `onCreate` (fresh creates only — the system re-delivers the starting intent
  after a process kill, which would replay an old track into a new walk) and from
  `onNewIntent`, which is where `singleTask` routes the second track someone
  shares. The composition consumes it, switches to the map — the only screen that
  draws import progress and frames the result — and clears it.

The SEND filter accepts four mime types for the same reason the picker filters on
`*/*`: GPX has a registered type nobody uses, and fitness apps hand the file over
as `application/octet-stream` or plain XML far more often. VIEW is kept to
`application/gpx+xml` alone, since VIEW on octet-stream would offer Enclose as a
way to open every unrecognised file on the device.

`GpxImporter` is a hand-rolled scanner rather than an XML parser, for a reason
worth keeping: `XmlPullParser` and `DocumentBuilderFactory` are both stubbed out
in the mockable `android.jar`, so anything built on them can't be unit tested
here — and the parsing is the part most likely to be wrong. It reads `<trkpt>`,
falling back to `<rtept>` then `<wpt>`, tolerates either attribute order and
either quote style, and picks up `<ele>` only when it belongs to the point in
hand. `GpxImporterTest` includes a round trip against `GeoExporter.toGpx`, which
is the cheap guard against the two halves drifting apart.

**It scans with `String.indexOf` and never with `Regex`, and that is load
bearing.** `Regex.findAll` builds a fresh `Matcher` over the whole input per
match, and on Android each one copies the input into ICU, so cost grows with the
square of the track. The JVM hides this completely — a 20 000-point ride parsed
in 113 ms in the unit tests while the same file, on a device, took 247 ms at
1 000 points, 1.6 s at 3 000, and had not finished after **five minutes** at
20 000, with a modal up and no way out but killing the app. Anything that scans
this file needs its offsets computed once up front; searching forward from each
point re-reads the remainder every time, and reads to the very end whenever the
tag is absent — which for `<ele>` is the common case. `GpxImporterTest` has a
timeout-bounded 20 000-point case in both shapes (with and without `<ele>`) to
keep that from creeping back; it is the test that caught the second regression.

The other half of the same lesson: **unit-test timings say nothing about ART.**
When something here is performance-sensitive, measure it on a device.

The picker filters on `*/*` deliberately: most providers hand a `.gpx` over as
`application/octet-stream`, so a precise filter mostly hides the file the user
came to pick.
