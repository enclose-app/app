# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this app is

Enclose is an Android app (Kotlin, Compose, MapLibre). You walk a
loop; when the loop closes you claim the enclosed area as a "territory". Claims
are **layers**: a new one that overlaps older claims is drawn on top of them,
and every claim keeps the whole shape and area it was walked with.
Everything works fully offline; sync to a backend is an unimplemented seam.

Three modules: `:app` is the phone app and nearly all of the code; `:wear` is the
Galaxy Watch companion; `:watchlink` is the plain-Kotlin protocol the two share.
See **Watch companion** below.

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
./gradlew testDebugUnitTest :watchlink:test assembleDebug   # the everyday check
./gradlew :app:installDebug                 # phone app (installDebug alone hits every device, watch included)
./gradlew :wear:installDebug                # watch app — target the watch with ANDROID_SERIAL
./gradlew :app:testDebugUnitTest --tests "io.app.enclose.data.FootprintTest"   # one module: a filter that matches nothing in :wear fails it
./gradlew :app:testDebugUnitTest --tests "*.FootprintTest.the same loop claimed twice counts once"
./gradlew lintDebug                         # Android lint (no ktlint/detekt configured)
./gradlew connectedDebugAndroidTest         # instrumented tests (needs a device)
```

**Lint fails on this repo by design.** The baseline is 3 errors / 31 warnings,
all pre-existing `MissingPermission` in `ActivityMonitor.kt` (×2) and
`EncloseMap.kt`. Those three are not yours and are not to be "fixed" in passing;
a fourth is. `:wear` lints clean (0 errors). Compare by file and issue id, never line number — see the `verify`
skill.

Gradle's configuration cache is on (`gradle.properties`); adding
configuration-phase side effects to build scripts will break it. `minSdk` is 33,
so a device/emulator on API 33+ (Android 13) is required, for the watch app as
well. 33 is the floor the code supports as written: below it, `CityResolver`'s
async `Geocoder`, `readNBytes`, `getParcelableExtra` and `POST_NOTIFICATIONS`
need fallbacks (lint `NewApi`/`InlinedApi` lists them at a lower `minSdk`).

## Testing conventions

Unit tests are plain JVM JUnit4 and there is **no Robolectric**, so nothing
touching `Context`, `SharedPreferences`, Room, or Play Services can be unit
tested. The established response is to *extract the decision into pure Kotlin and
test that*, leaving the Android shell thin:

| Pure unit | Tested by | Kept testable by |
|---|---|---|
| `StretchJudge` | `StretchJudgeTest` | a path, its `TrackPoint`s and a `StepLog` in, labels out |
| `StepLog` | `StepLogTest` | step intervals in, a count or null out |
| `StepCounterIntervals` | `StepCounterIntervalsTest` | sensor totals as plain Longs, split from `PhoneStepCounter` |
| `LoopVerdict` | `LoopVerdictTest` | points, times and steps in, a claim or a record out |
| `LoopClosure` | `LoopClosureTest` | points in, the index of the last return out |
| `MotionEvidence.of` | `MotionEvidenceTest` | takes the normalised `MotionSample` |
| `Footprint` | `FootprintTest` | territories in, square metres out |
| `WalkedShape` | `WalkedShapeTest` | a ring and a stored shape in, the walked shape out |
| `Coverage` | `CoverageTest` | no Android or DB types |
| `Place` | `PlaceTest` | separated from the `Geocoder` call |
| `WalkProgressRecorder` | `WalkProgressRecorderTest` | the `WalkProgressStore` interface seam |
| `TrackingManager.restore` | `TrackingManagerRestoreTest` | manager has no Android/DB deps |
| `TrackingManager.finishWalk`, `saveWalk` | `TrackingManagerStopTest` | manager has no Android/DB deps |
| `TrackingManager.onSteps`, `hasDoubt` | `TrackingManagerStepsTest` | manager has no Android/DB deps |
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
| `TerritoryHit` | `TerritoryHitTest` | a point and a list in, a `Territory` out |
| `Geo.ringContains` | `GeoContainsTest` | plain rings, no map library |
| `SnapTagger` | `SnapTaggerTest` | the `SnapStore` + `RouteMatcher` seams |
| `FixWatch` | `FixWatchTest` | counts and an accuracy, no clock of its own |
| `FixPlausibility` | `FixPlausibilityTest` | two fixes, their times and accuracies |
| `PathSpikes` | `PathSpikesTest` | points in, points out |
| `TrackingManager.reportRecordingUnavailable` | `TrackingManagerRecordingFailureTest` | manager has no Android/DB deps |
| `WindowLayoutPolicy` | `WindowLayoutPolicyTest` | window size in as plain Ints, controls as an enum |
| `SplitScreenSupport` | `SplitScreenSupportTest` | `Build` fields passed in, not read |
| `ActivityType.resolve` | `ActivityTypeTest` | the stored name is just a String |
| `DistanceMarkers` | `DistanceMarkersTest` | points in, marker positions out |
| `Json` | `JsonTest` | hand-rolled, so no `org.json` |
| `Backup` | `BackupTest` | entities are plain data classes |
| `watchStatusOf`, `shouldStartFromWatch` | `WatchStatusOfTest` | walk state in, `WatchStatus` out |
| `stepsFromWatch` | `StepsFromWatchTest` | walk state and `WatchSteps` in, intervals out |
| `WatchStatus` (`:watchlink`) | `WatchStatusTest` | the field codec, as plain maps |
| `WatchFormat` (`:watchlink`) | `WatchFormatTest` | numbers in, strings out |
| `watchMapOf`, `watchClaimsNear`, `argbOf` | `WatchMapOfTest` | walk and territories in, protocol out |
| `WatchMap`, `WatchClaims`, `WatchGeo` (`:watchlink`) | `WatchMapTest` | the coordinate codecs, as plain maps |
| `WatchSteps` (`:watchlink`) | `WatchStepsTest` | the packed step codec, as plain maps |
| `ReadyAlert` (`:wear`) | `ReadyAlertTest` | a boolean and a status, no vibrator |
| `StepWitness` (`:wear`) | `StepWitnessTest` | a status in, what to register and hold out |

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
scope would cancel it halfway. `EncloseApp.onCreate` also starts the listeners
that must hear a walk end with **no screen open** — see step 4 below.

### The walk → claim pipeline

This is the core flow and it spans four files:

1. `LocationService` (foreground service, `FOREGROUND_SERVICE_TYPE_LOCATION`)
   streams fused-location fixes and forwards each one — with accuracy, speed,
   monotonic and wall-clock timestamps, and the latest `ActivityMonitor` sample —
   to `TrackingManager.onLocation`. Beside it, `PhoneStepCounter` reads the
   hardware step counter and hands an interval to `TrackingManager.onSteps` every
   10 s, zero-step ones included. Both are timed off one epoch base per service
   (`System.currentTimeMillis() - SystemClock.elapsedRealtime()`), so a fix and
   the steps taken reaching it are on the same clock.
2. `TrackingManager` is a stateful `object` holding the in-progress walk. It has
   **no Android or DB dependencies by design**, so it can be reasoned about and
   tested in isolation. It exposes `walk: StateFlow<WalkState>`,
   `pendingClaim: StateFlow<PendingClaim?>` and
   `unclaimedWalks: SharedFlow<UnclaimedRecording>`.
3. Loops never close automatically, and **the app never ends a walk** — see
   "Anti-cheat" below. `WalkState.readyToClose` says whether stopping *now* would
   close the loop here (left the start zone, walked the minimum perimeter on foot,
   currently within the closing radius). Only the walker ends a walk:
   - **Stop** calls `finishWalk()`, which hands everything recorded to
     `LoopVerdict`. It claims the loop that closed here or — stopped away from the
     start — the one that closed at the last return to it, or keeps the walk
     without a claim on `unclaimedWalks`. Away from the start the panel opens
     `StopDialog` first: claim that earlier loop (`earlierLoop()` says whether
     there is one, and when it closed), Save, Keep walking, or Discard.
   - **Save** (`saveWalk()`) keeps it without a claim, and is what the dialog
     leads with when no loop closed.
   - **Discard** (`cancelWalk()`, behind a confirmation) is the one way a walk is
     lost.

   Stop flushes the step counter first (`LocationService.flushSteps`, capped at
   1 s) so the last stretch is judged on its steps, and never hangs on a sensor —
   and, when a stretch is in doubt, asks the watch for its last ones too
   (`WatchStepRequests.gatherLastSteps`; see "Watch companion").
4. What `TrackingManager` deliberately can't do is split by whether it needs a
   screen. `EncloseApp` (on `applicationScope`, for the life of the process)
   **persists every closed loop** and **keeps every walk stopped without a
   claim** — a walk can end with the map screen gone (Stop on the watch), and
   these used to live in the view model, where such a walk was never saved.
   `EncloseViewModel` keeps the rest: starting and stopping the service from the
   UI (ending a walk runs on `applicationScope`, so it survives the screen going),
   the claim itself, and explaining a walk kept without one.

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
  decides whether to claim it (`EncloseApp.onCreate` collecting `pendingClaim`).
  Not the view model: it must run with no screen open.
  `confirmClaim` then re-saves it with `claimed = true` using the *same id*,
  which is also the `Territory` id — walks and territories are linked by id.
- **`confirmClaim` saves the claim and nothing else.** Claims are layers: one
  that overlaps older claims is drawn over them and takes nothing from any of
  them, so no other row is written. (Claims used to carve each other —
  `Conquest.carve` cut the shared ground out of older claims and marked one
  covered whole as conquered. `MIGRATION_13_14` put every cut claim back to its
  walked shape; see "Nothing the user walked for is ever destroyed".)

### Watch companion

The phone owns the walk; the watch (`:wear`, same `applicationId` and signing
key — the Data Layer connects nothing else) is a window onto it with Start and
Stop. The protocol lives in `:watchlink` (`WatchLink`, `WatchStatus`) so both
apps compile against one definition.

- **Phone → watch:** `WatchPublisher` (on `applicationScope`) keeps three Data
  Layer items, each throttled to what it's worth, because every write wakes both
  radios:
  - `/enclose/status` — the coarse `WatchStatus`, only when it changes. Figures
    are rounded (10 m distance, 5 m to-start) and elapsed time is never sent; the
    watch counts it up. An IDLE is written on every process start, clearing a
    walk that died with the process.
  - `/enclose/map` — `WatchMap`: the path (simplified by `RouteSimplify`, with
    the tolerance doubled until it fits `WatchMap.MAX_POINTS` — coarsened, never
    truncated), start, position and closing radius, at most every 5 s.
  - `/enclose/claims` — `WatchClaims`: the nearest `MAX_CLAIMS` within 6 km of
    the walk's start, as `SnapDisplay` draws them, sent oldest first — the
    watch draws them in arrival order, so that stacks the newest on top, as on
    the phone. Only queried while a walk has a start, so an idle process holds
    no Room observer open.
  Coordinates travel as `Long` 1e-7° (about 1 cm, exact both ways), not floats.
- **Stop:** the watch sends `/enclose/stop`, and offers it **at any point of a
  walk** (`canStopFromWatch`). Stop claims the loop that closed, here or at the
  last return to the start, and keeps a walk with none, so it throws nothing
  away. Ready to close it is *Stop & claim*; until then it is *End walk*, behind
  a second tap, because a sleeve brushing a wrist shouldn't end an hour's walk.
  `WatchCommandService` re-checks the phone's live walk before acting, since the
  watch's status can be seconds old. (It used to be offered only when ready,
  because Stop on a walk that wasn't ready discarded it.)
- **Start:** not a message. Android refuses to start location tracking from the
  background, so the watch opens the phone app at `enclose://walk/start` and
  `MainActivity` starts the walk behind the same checks as the Start button
  (`shouldStartFromWatch`). `enclose://walk/open` opens the app without
  starting — used for a closed loop, which is named and claimed on the phone.
  `trackUriFrom` ignores the `enclose` scheme so neither is mistaken for a GPX.
- **Ready-to-close buzz:** `StatusListenerService` on the watch buzzes once on
  the step into the closing radius (`ReadyAlert`), app open or not, with a
  tap-to-stop notification that clears when stopping would no longer claim. It
  also buzzes **once per loop** when a stretch turns doubtful after that loop
  closed (`ReadyAlert.shouldWarnDoubt`, `WatchStatus.loopClosedAtEpochMs`):
  "Looks like a vehicle. Your loop closed at 14:32." Once per loop, because a bus
  slows at every stop and a buzz each time it pulls away is a watch that won't
  stop vibrating.
- **Steps, the walk's second witness.** The watch counts with Health Services'
  *passive* STEPS (`StepRecorder`), registered only while the status says
  WALKING and cleared when it doesn't; the platform batches deliveries to
  `StepListenerService`, so nothing runs between batches. After each batch the
  watch sends `/enclose/steps` (`WatchSteps`) carrying **every interval it holds
  for the walk**, not only the new ones, so a lost message is repaired by the
  next; the phone drops repeats and overlaps (`TrackingManager.onSteps`). They
  match a walk by its `startedAtEpochMs` (`stepsFromWatch`), and steps counted
  before the first fix are adopted by it (`StepWitness.follow`). **They can only
  help**: `StretchJudge` takes the watch's verdict only where it beats the
  phone's, so a flat, absent or unpermitted watch costs a walk nothing.
  Two details are load-bearing. Intervals travel **packed into one byte array**
  (24 bytes each; `MAX_INTERVALS` is 48 KB), because how the Data Layer encodes an
  array of longs is unknown and a message over 100 KB is not sent at all. And a
  registration is tagged with the boot count and `versionCode` and **made again
  under any other**: Health Services forgets one across a reboot, and the watch
  would otherwise believe it was counting for the rest of the walk.
- **Stop waits for the watch — briefly, and only when it can matter.**
  `WatchStepRequests.gatherLastSteps` (both Stop paths: the panel and
  `WatchCommandService`) flushes the phone's counter, then, if `hasDoubt()` says a
  stretch would be bridged or set aside and a watch is connected, sends
  `/enclose/steps/flush` naming the walk and waits up to 10 s for the answer marked
  `flushed`. The panel shows "Asking your watch…" in place of its buttons. Save
  doesn't wait — it claims nothing either way — and a watch that isn't counting
  answers at once.
- **The watch map** (`WalkMap`) is MapLibre on the watch with the phone's dark
  OpenFreeMap style. It uses **`android-sdk-opengl`**, not the phone's default
  artifact: that one renders with Vulkan, which the Wear emulator lacks
  (`vk::createInstanceUnique` crash) and watch GPUs can't be relied on for.
  Touch gestures are off (a swipe is Wear's "back"); the crown zooms and the
  camera follows the walker, offset above the figures strip. A map is drawn only
  if its `startedAtEpochMs` matches the status's, so the last walk's path never
  shows under a new one. OSM attribution is drawn by `WalkScreen`, since
  MapLibre's sits in a corner a round screen doesn't have.
- **Demo walk:** debug builds of the watch app show `DemoWalk` with
  `adb shell am start -n io.app.enclose/io.app.enclose.wear.MainActivity --ez demo true`
  — for looking at the walking screen with no phone paired.
- **Versions:** phone and watch apps update separately. `WatchStatus.VERSION`
  is in the encoded fields; bump it when a field changes meaning, and a
  mismatched watch shows "Update Enclose" instead of misreading the walk.

What tests can't reach: the Data Layer itself, `RemoteActivityHelper`, Health
Services, and the listener services. Testing those needs a phone and watch paired through the
Wear OS app (for emulators: Android Studio's Wear OS pairing assistant).

### Nothing the user walked for is ever destroyed

Several paths used to lose territories, and all of them are now closed. Treat
this as a standing constraint rather than a past fix:

- **No claim takes ground from another.** Claims are layers: a newer one is
  drawn on top, the older keeps its whole shape and area, and one lying
  entirely under a newer claim is still on the map and reached from the claims
  list. Claims used to carve each other, and `MIGRATION_13_14` restored every
  claim that was cut — found by its carve/conquest stamp *or* by a shape that
  differs from its ring, since claims cut before version 12 carry no stamp.
  `WalkedShape` (pure, tested) is the rule, and backup restore goes through it
  too (`TerritoryEntity.asWalked`), so a file saved before the change can't bring
  a cut claim back. The three stamp columns stay in the table, never written
  again, because removing a column means rebuilding the table. Only an explicit
  user delete (which has an undo) removes a row.
- **The walk in progress is on disk** — see below.
- **A walk is never voided.** Nothing the app decides ends a walk — see
  "Anti-cheat" below. A walk stopped without a claim (Save; Stop away from the
  start with no loop behind it; a loop with too much of it bridged) is handed
  over on `unclaimedWalks` with its route, times, steps and the split that
  decided it, and `EncloseApp` keeps it in `voided_walks` (`VoidedWalk`). It is
  kept apart from `walks` on purpose — everything reading `walks` assumes a
  closed loop. Profile lists them under "Walks that didn't count", with Share as
  GPX and a confirmed Delete. Rows the old motion gate voided (`VEHICLE`,
  `TOO_FAST`, `UNVERIFIED_GAP`) are left as they were and still read back.
  `VoidedWalk.Reason` mirrors `UnclaimedReason` by name, since `data` doesn't
  import `tracking`; `TrackingManagerStopTest` pins the mapping.

A walked territory can't be re-created from the couch, which is what makes these
different from ordinary state. Weigh any change here accordingly.

### Surviving process death

`LocationService` is `START_STICKY`, so the system restarts it after a
low-memory kill — but `TrackingManager` is an in-memory `object`, so it comes
back empty and its `isTracking` guard silently drops every subsequent fix while
the notification still claims to be recording.

`WalkProgressRecorder` therefore mirrors the walk to `walk_progress` /
`walk_progress_points` / `walk_progress_steps` as it is walked — each point with
its time, gap flag and motion evidence, and each step report (append-only: one
small insert per fix or report, so cost doesn't grow with the length of the
walk). It watches `TrackingManager` from the outside, which is what keeps the
manager free of Android and DB types. On restart
`LocationService.beginRecording` rehydrates via `TrackingManager.restore` and
calls `recorder.adopt(...)` so neither the restored path nor its step reports
are written twice; with nothing to restore it stops itself rather than burning
battery on fixes nobody will keep.

`restore` recomputes distance, `hasLeftStart`, `canCloseLoop` and every
stretch's label from the stored points and steps rather than storing them, so
restored state can't disagree with its own points — and a restored walk gets the
verdict the same walk would have got uninterrupted (`TrackingManagerStopTest`).
`readyToClose` deliberately starts false — the last stored point says where the
walker *was*, not where they are.

### Anti-cheat: judged at Stop, never mid-walk

A drive must not be able to enclose territory — and an honest walk must never be
thrown away for looking like one. The old `MotionGate` got the second wrong: it
judged every fix, stopped recording what it disliked and voided the walk after
three strikes, which is how four minutes of a wrong guess from the activity
classifier cost more than an hour on foot. It is gone, and the rule that replaced
it is the one to keep: **nothing during a walk ends it or stops it recording.**
Movement is labelled live; the walk is judged once, at Stop, over everything it
recorded. The worst outcome is a record without a claim.

**`StretchJudge`** (pure, tested) labels the walk in `STRETCH_MS` (30 s)
stretches, each judged on its **net displacement over its time** — GPS wander
between tall buildings zig-zags in place and can't add speed the way a
fix-to-fix average let it. A stretch is `ON_FOOT`, `UNOBSERVED` (a signal gap
nothing contradicts: it counts, and the claim says part of its outline is a
straight line) or `DOUBTFUL`. Slowest first:

- **Walking pace needs no proof.** At or below `FREE_SPEED_MPS` (2.5 m/s ≈
  9 km/h) a stretch counts whatever the classifier says. This band is where the
  gate's mistakes ended real walks, and a car at walking pace gains nothing.
- **Steps are the proof for anything faster**, up to the walk's ceiling — the
  declared `ActivityType`'s, raised (never lowered) by a confident run or ride.
  Steps covering the displacement at `MAX_STEP_LENGTH_M` (2 m) each count it,
  from the phone or the watch, whichever helps; with **no step data at all** it
  counts unless the classifier confidently said vehicle. A confirmed ride needs
  no steps — a bike takes none.
- **Past the ceiling, or past `ABSOLUTE_MAX_SPEED_MPS` (22 m/s) whatever anyone
  says,** it is doubtful.

The classifier reaches it as `MotionEvidence`, stored per point: `ActivityMonitor`
normalises Play Services `DetectedActivity` into `MotionSample` (null without the
permission), and `MotionEvidence.of` keeps only a confident, fresh reading that
could change a verdict. A stretch reads as a vehicle when half its fixes said so.

**Missing evidence is never zero.** `StepLog` (pure, tested) answers "how many
steps between these two times" — or null, when the source wasn't counting across
all of it (`COVERAGE_SLACK_MS` aside). Zero steps across a fast stretch is
evidence of a vehicle; no count is no evidence either way, the rule the tracker
already follows for location. That distinction is why `PhoneStepCounter` reports
on a 10 s heartbeat, **zero-step intervals included** (`StepCounterIntervals`,
pure, tested): the hardware counter only reports changes, so without the
heartbeat a car journey looks exactly like a counter that stopped. No sensor, no
`ACTIVITY_RECOGNITION` permission or a process restart leaves a hole, which
reads as no evidence.

**`LoopVerdict`** (pure, tested) is the one place a walk is judged:

1. **Drop a leading drive.** A walk begun in a car begins where the walking did
   (`StretchJudge.walkStart`), but only past a drive longer than
   `LEAVE_START_RADIUS_METERS` — a wobble in the first fixes is no drive.
   `TrackingManager` applies it live too (`WalkState.startIndex`), so "back to
   start" points where the walker actually started.
2. **Find the loop.** At the start it ends here; anywhere else at the last
   return to the start (`LoopClosure`, the rule GPX imports use).
3. **Bridge.** Each run of doubtful stretches becomes one straight line between
   its ends, so a detour by car or tram adds no area. What a bridge measures is
   the point: a winding tram ride that ends near where it began makes a short
   bridge and costs almost nothing; a straight drive across town makes a long one.
4. **Apply the limit.** Up to `MAX_BRIDGED_SHARE` (20%) of the boundary may be
   bridged — about 1.5 km of a 10k-step loop; at 10% one tram stop sinks a short
   loop, at 30% driving starts to pay. Past it, or with nothing walked at all,
   the walk is kept without a claim (`UnclaimedReason.TOO_MUCH_BRIDGED`).

`Territory.ring` is the boundary as claimed, bridges included. The claim dialog
shows the split — on foot, each bridge with its times and share, the steps — and
the `Walk` row keeps it (`onFootMeters`, `bridgedMeters`, `phoneSteps`,
`watchSteps`, and `trackJson`: everything recorded, when it differs from the
ring).

Live, the panel says a stretch is doubtful while it is (`WalkState.doubtReason`,
`PanelStatus.DOUBTFUL`, a dotted line on the map) and how much is bridged so far
against the limit, so nobody finds out at Stop. Away from the start after a
loop closed, it also says when that loop closed (`WalkState.earlierLoopClosedAtMs`,
looked for only while a stretch is in doubt), so nobody rides on believing the
walk is lost. `READY` outranks `DOUBTFUL`:
someone standing at the start can claim, and at worst the stretch is bridged.
Doubtful time isn't moving time (`PauseTracker`'s `credit`). Steps that arrive
late — the watch batches — judge the walk again, so they can still vouch for
ground already recorded.

**RUN and BIKE are currently turned off** (`ActivityType.available`): their chips
show greyed, and `ActivityType.resolve` sends a stored-but-unavailable choice
back to WALK so nobody walks under a ceiling they can't see. Everything else
about them is intact — including the classifier's ability to *detect* a run or a
ride and raise the ceiling — so re-enabling is that one flag.

The KDoc in `StretchJudge.kt`, `LoopVerdict.kt` and `Motion.kt` explains why each
threshold has the value it does — including why the ceilings stayed where they
were when the gate went. Read it before touching the numbers.

**Silence is not evidence of speed.** A backgrounded or dozing device stops
delivering fixes and then hands the missed stretch over in a burst on wake. These
rules in `TrackingManager.onLocation` keep that from being mistaken for a drive —
or from hiding one — and they are load-bearing:

- Fixes worse than `MAX_ACCURACY_METERS` return **before** anything is recorded
  or judged. A reacquisition fix lands hundreds of metres out.
- Losing the signal has **two shapes, and only one of them is silence.** More
  than `SIGNAL_GAP_MS` with no fix covers the dozing device. The commoner one is
  a *frozen* fix: the provider keeps reporting the last position it was sure of,
  at the normal interval, then snaps to the true one — nothing looks wrong until
  the snap, so the silence rule never sees it. That is caught instead by a
  segment faster than `REACQUISITION_SPEED_MPS` (55 m/s ≈ 200 km/h), which no
  road vehicle reaches. Both drop the speed baseline, so the jump never becomes a
  speed, and mark the point after it `afterGap`.
- **A gap is judged whole.** The line across it is one stretch, judged on how
  long it took and on the steps taken in it: a phone that dozed for ten minutes
  is judged on ten minutes. Steps that cover it make it walked (the claim says
  so); too fast, or zero steps past walking pace, and it is bridged and counted
  against the 20% — so switching location off for a drive buys nothing. Silence
  always flags `WalkState.hadSignalGap`; a snap flags it only if the fix it
  snapped to is kept (see the hold below).
- **A gap is held to the walker's own stride.** Once the walk has shown one
  (`StretchJudge.learnedStepLength`: ground over steps, across walking-pace
  stretches a count covers, from `MIN_LEARNING_STEPS` on), a gap's steps are
  taken at up to `GAP_STEP_FACTOR` (2.2×) of it rather than at 2 m, floored at
  `MIN_GAP_STEP_LENGTH_M` and never looser than before. At 2 m a step, a short
  walk inside a gap could vouch for a ride inside the same silence. Gaps only:
  a stretch with GPS behind it has a measured speed. It is learned along the
  path, since GPS wander makes it err long and long only loosens, and from this
  walk alone, so a restored walk learns the same stride. It is computed only
  when the walk has a gap.
- **A jump is held, not appended.** A fix no one could have reached from the
  path's last trusted point (`FixPlausibility`: `ABSOLUTE_MAX_SPEED_MPS` × time
  plus both accuracies) is held. A fix back near the path → it was a spike (a
  Wi-Fi/cell fix claiming ±25 m from 400 m out) and is dropped with no gap
  flagged. Fixes agreeing with it for `HOLD_CONFIRM_MS` (10 s, ≥3 fixes) → a
  real snap, appended with `hadSignalGap`. **One agreeing fix is not enough**:
  providers deliver the same bad fix repeatedly, and on a device that let every
  spike confirm itself. The hold decides only what reaches the path.
  `LoopVerdict` then runs `PathSpikes` over the claimed points for what got
  through (a bad fix repeated past the hold, or a restored walk).
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
- `Territory.ring` is the boundary as claimed — the walked points, with any
  bridged stretch reduced to its two ends — and never changes. What was recorded
  around it (a leading drive, a bridge, ground after the loop closed) stays on
  the `Walk` row as `trackJson`.
  `Territory.polygons` (a `List<GeoPolygon>`, i.e. a multipolygon) is the claimed
  geometry, which is the ring as its only polygon — claims are layers, so nothing
  cuts into it. **Area and export use `polygons`; GPX uses `ring`; rendering goes
  through `SnapDisplay`** — see "Snapping to real paths".
- **Totals count shared ground once.** Claims can overlap, so every figure that
  adds claims up — the map's summary chip, the list, the profile total,
  `Coverage`, `Passport` — comes from `Footprint` (pure, tested), never from a
  `sumOf { it.areaSqMeters }`. It measures in clusters of claims whose bounding
  boxes touch and takes the overlap JTS finds off the sum of their stored areas,
  so claims that don't overlap total exactly what they did before. It is JTS
  work, so the view models run it on `Dispatchers.Default`, never in composition.
- `Geo` uses an equirectangular projection around the mean latitude plus the
  shoelace formula — accurate enough at city-walk scale, and `GeoClip` projects
  the same way before handing geometry to JTS (which is planar). `GeoClip` runs
  `buffer(0)` to repair self-intersections from GPS noise, and
  `GeoClip.sharedSqMeters` answers zero rather than failing, which reads as every
  claim counted whole. `GeoClip.isSimpleRing` answers whether a ring that came
  from somewhere else crosses itself.

### Snapping to real paths

A recorded loop wobbles across buildings and cuts corners it didn't cut, so a
claim's outline can be matched onto real roads. Three constraints shape all of
it, and none of them are incidental:

- **Display only.** `Territory.ring` stays the boundary of record, `areaSqMeters`
  is measured from it, and `Footprint` measures shared ground with it. A footpath
  missing from the map must never shrink what someone owns. `SnapDisplay` is a
  named object rather than an extension property precisely so a leak into
  `Footprint`, `Coverage`, `Passport` or `OfflineTilePlanner` is visible in a
  diff. **Export stays raw,
  GeoJSON as well as GPX** — `toGeoJson` writes the area into `properties`
  beside the geometry, and shipping a shape whose stated area disagrees with it
  is worse than shipping the honest one.
- **After the claim, never during the walk.** Live snapping is an anti-cheat
  hole: a drive down a road map-matches to that road perfectly, which is exactly
  the signal the anti-cheat exists to reject. Do not "improve" this into a live
  feature.
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

### Persistence

Room (KSP), database version 15, eight entities: `territories`, `walks`, `voided_walks`, a
single-row `profile` (`id = "me"`, auto-created with a random guest name on
first access), `walk_progress` / `walk_progress_points` / `walk_progress_steps`
backing the walk in progress, and `offline_regions`. Geometry is stored as **hand-rolled JSON strings** in
`ringJson`/`geometryJson`, converted in the entity companions — the only Room
`TypeConverter` is for `SyncStatus`.

`TerritoryDao` and `WalkProgressDao` are abstract classes rather than interfaces
so they can host `@Transaction` methods.

**There is no destructive-migration fallback, and none may be added.** A
territory is a walk someone went out and did — it can't be re-entered from the
couch — so dropping tables to land a schema change is never an acceptable
trade, pre-release included.

The rule that follows: **every version bump ships a `Migration`.**
`EncloseDatabase` has eight worked examples — `MIGRATION_5_6` (add a column),
`MIGRATION_6_7` (add nullable columns), `MIGRATION_7_8` (create tables, with the
`CREATE TABLE` copied verbatim from the exported JSON so Room's validation
passes), `MIGRATION_8_9` and `MIGRATION_9_10` (a `NOT NULL` column needs a SQL `DEFAULT`
for existing rows even when the Kotlin property has one — a constructor default
is not a column default), `MIGRATION_13_14` (data only: rewrites derived
columns row by row in Kotlin, and skips a row it can't parse rather than
throwing, since a migration that throws fails every launch), and
`MIGRATION_14_15` (nullable columns across three tables in a loop, plus a new
table). Without one, Room throws when opening the database instead of quietly
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
pure and unit-tested in `CoverageTest`): claimed ground — shared ground counted
once, by `Footprint` — over the bounding box of that city's claims. Measuring across all claims at once — as it did originally —
collapses towards zero the moment someone walks in a second city, because the
box between two cities is mostly countryside.

### Remembered preferences

`UserSettings` (SharedPreferences, file `enclose_ui`) holds **every** preference:
seen-intro, activity type, basemap, territory sort, test mode, snap-to-paths, panel-collapsed,
floating-window, home, the offline
downloader's style and pixel ratio, and the map camera. `SettingsSnapshot` mirrors
the lot for backup — add a preference in three places (the class, `snapshot()`,
`restore()`) or it silently won't be backed up. It exists as one class because the previous ad-hoc `prefs.getString(...)`
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

### Backup and restore

**One file, everything in it.** `BackupRepository.collect` reads every table in
`EncloseDatabase` — territories, walks, voided walks, the profile, the
walk in progress with its points and step reports, the cached-region rows — plus
`UserSettings.snapshot()`, which mirrors the whole preferences file. The check
for "is this complete?" is against the schema, not against memory: a new table or
a new preference means a new field in `BackupData` and `SettingsSnapshot`, and
`SettingsSnapshot` sits next to `snapshot()`/`restore()` so the omission is
visible when you add one.

`Backup` and `Json` (both pure, tested) are the format. Three decisions worth
keeping:

- **The codec is hand-rolled.** `org.json` is stubbed in the mockable
  `android.jar` — the same reason `GpxImporter` scans XML by hand — and a backup
  format is the last place to accept a codec no test can reach. `GeoExporter`
  uses `org.json` and correspondingly has no tests.
- **Columns are carried verbatim, geometry included.** A ring is already a JSON
  string in the column and stays a string in the file. It is uglier and it is the
  point: re-encoding geometry on the way out and back is how a walked outline
  comes back subtly different.
- **Every field is read with a default**, so a file from an older build restores
  into a newer schema — a column added since takes the default a migration would
  have given it. A newer *format* version is refused outright (half-restoring an
  unknown shape is worse than not restoring); a newer *schema* is accepted with a
  note saying what may have been dropped.

**A restore merges and never deletes**, which follows directly from "nothing the
user walked for is ever destroyed": wiping first would delete claims walked since
the backup was taken. Rows land by primary key, so onto a fresh install it is a
complete restore, and the report says how many were new versus replaced. Two
things it will not do: it refuses entirely while a walk is running, and it adopts
the file's unfinished walk **only** when the device has none of its own — those
points exist nowhere else. The database half is one `withTransaction`; the
preferences are written after it commits, and `EncloseViewModel.reloadSettings`
then re-seeds the in-memory flows, without which a restore is invisible until the
app is killed.

**Cached map regions are backed up but not restored**, and that asymmetry is
deliberate: `offline_regions` holds MapLibre's id for a tile pyramid that lives
outside the file, and `OfflineTileSync` skips any city it finds a row for — so
restoring the rows would mean those cities never download again. The report says
so rather than leaving it to be discovered.

Unit tests cover the format; the database half needs `device-check` (the round
trip that matters is export → `pm clear` → restore).

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
- **Tapping the map selects the claim under the finger.** `TerritoryHit` (pure,
  tested) answers which one, and it hit-tests **what is drawn** — i.e. through
  `SnapDisplay`, so a snapped outline is selected where the snapped outline is
  what the eye sees. A tap on open ground clears the selection, which is the
  same gesture read the other way round, and only a tap that *hit* something is
  consumed, so nothing else on the map loses a gesture to this. Claims are
  layers, so a tap can land in several, and it takes **the one drawn on top**:
  the selection if the tap is inside it (the selection layer draws above every
  claim), otherwise the newest (`territoriesToFeatures` sends claims oldest
  first). A claim lying entirely under a newer one is reached from the list,
  whose *Show on map* selects it as well as framing it. Three details
  are load-bearing: the selection is held **by id**, since the territory flow
  hands back a new `Territory` on a rename or a city resolving — and holding the id is also what makes a delete take
  the card and the highlight with it. It lives in **`MainActivity`**, not in
  `MapScreen`, because opening a claim disposes the map screen and a selection
  remembered inside it would be gone on the way back, un-highlighting the claim
  the user just came from. And the card **raises everything anchored to the
  panel** — both rails, the snackbar, and MapLibre's logo and attribution — off
  its measured height: a card that covers the last button on a rail makes that
  control unreachable for as long as a claim is selected, and the attribution is
  a licence requirement. The selection stands down entirely while a suggested
  route is drawn, for the same reason the claims themselves do, and test mode
  keeps the map tap for injecting points. Selecting **centres the claim**
  (`MapController.centerOn`) without touching the zoom — the same rule following
  a walker obeys, since the user has already chosen how much ground they want to
  see — and it centres it in the map that is *visible*, shifting by the panel and
  card through the live projection rather than by a guess in degrees, which a
  pixel is not worth a fixed number of. It is skipped outright while the map is
  following a walk in progress: the next fix would pull the camera straight back,
  so it would be a lurch and nothing else.
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
  Compose lifecycle. State reaches the map by updating named `GeoJsonSource`s
  (claimed polygons, the selected claim, closing zone, live path — each doubtful
  run its own dotted line — start anchor, the saved home, and the kilometre
  markers). Camera actions
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

### Translatable text

**Every string a user can see or hear is a string resource.** Play can only
translate what is in `res/values*/strings*.xml`; a literal in Kotlin ships in
English to everyone. This covers `Text`, button labels, dialog titles,
snackbars, toasts, notifications, `contentDescription`/`onClickLabel` (screen
readers read them aloud), and the watch app. Read them with `stringResource` /
`pluralStringResource` in Compose and `getString` / `getQuantityString` outside it.

- **Whole sentences, never spliced fragments.** `"Start ${noun}"` and
  `"$a of $b" + " used"` can't be translated: word order, articles, gender and
  case all move with the words around them. One resource per complete sentence,
  with positional placeholders (`%1$d`, `%2$s`) so a translation can reorder them.
- **One variant per activity type.** Sentences naming the walk/run/ride come as
  three resources picked by `ActivityType.pick(...)` (`MapScreen.kt`), not with
  the noun interpolated.
- **Counts use `<plurals>`**, even where English only needs one form — other
  languages need more.
- **A short comment on any placeholder** saying what it holds and in what unit;
  a translator sees the string alone.
- **Pure units stay text-free.** `PanelSummary`, `FixWatch`, `StretchJudge`,
  `LoopVerdict` and the rest return enums and numbers; the composable turns them into a resource.
  That keeps them JVM-testable (no `Context`) and keeps the wording in one place.
- **Not user-facing, so stays literal:** log messages, file formats (GPX tags,
  GeoJSON keys, backup field names), Data Layer paths, intent URIs, preference
  keys, and anything a test asserts on as data.

Resources are grouped per surface, one `strings_<surface>.xml` each:
`panel` (the map's control panel), `map` (the rest of the map screen, its
dialogs and the How it works sheet), `claim` (the claim dialog and colour
swatches), `transfer` (GPX import and backup/restore, including the reports
`EncloseViewModel` writes), `profile`, `territory` (the detail screen),
`common` (shared dialog buttons, the territory list, the floating window) and
`units` (everything `Format.kt` writes). The watch has its own
`wear/.../strings_watch.xml`. **Every screen is migrated**; a literal a user can
see is now a bug, not a backlog item.

Deliberately still English, each for a reason:

- **Generated names** (`NameGenerator`, `ProfileNameGenerator`). They become
  stored data — a claim's name, the profile's name — so translating them is a
  product decision (pick the language at creation? keep one shared pool?), and
  "Adjective Noun" doesn't survive gender agreement in most languages anyway.
- **What goes inside exported files** — the GPX track name, GeoJSON property
  keys — is file format, read by other software.
- **"© OpenStreetMap"** on the watch is a licence notice, marked
  `translatable="false"`.

`ActivityType` carries no text: what each type is called comes from
`ActivityType.pick(...)` / `labelRes` / `activeLabelRes` in
`ui/ActivityTypeText.kt`. `Backup.decode` returns `Backup.Problem` and
`NewerSchema` rather than sentences, and `EncloseViewModel.describe` words them.
`:watchlink` has no resources, so `WatchFormat` keeps only elapsed time; units
on the watch come from `wear/.../Units.kt`.

`Format.kt`'s formatters with a unit or a word in them are extensions on
`Resources` — `res.formatDistance(m)`, with `val res = LocalResources.current`
in a composable — so numbers and units follow the device's language and the
same function serves the view model. Don't reintroduce `Locale.US` for
anything a user reads; it is right only for file names, cache keys and file
formats.

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
starting `LocationService`, and is never judged (`LoopVerdict`'s `judged =
false`) — tapped points teleport, carry no clock, and would always read as a
vehicle.

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
never judged, same rule that the loop is only closed when the user presses
Stop.

**An import ends at its last return to the start** (`LoopClosure`, pure,
tested — the rule a live Stop away from the start uses too), not at its last
point. A recorder is stopped whenever it's stopped —
often after walking on to the car — and replayed as-is such a track "ends" out
there and can't close. The replay is cut at the last pass within the closing
radius (the *last*, so a second lap is kept), and the import summary says how
much was left out. The relaxed closing radius is 75 m (was 40 m) for the same
reason: tracks stopped a few dozen metres short. That costs no anti-cheat — the
injected path is never judged anyway. When a track still can't close, the
summary gives the gap and the radius rather than just "doesn't end near".

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
