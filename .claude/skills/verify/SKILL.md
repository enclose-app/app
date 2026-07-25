---
name: verify
description: Run and interpret this repo's local checks — build, unit tests, and Android lint — including the known-failing lint baseline, so pre-existing errors are not mistaken for regressions and not "fixed" by accident. Use after any code change and before reporting work as complete.
---

# Verifying a change

## Run

```bash
./gradlew testDebugUnitTest assembleDebug     # the everyday check
./gradlew lintDebug                            # only when you touched Android APIs
```

Tests and build should both end in `BUILD SUCCESSFUL`. Per-suite results:

```bash
grep -h "<testsuite " app/build/test-results/testDebugUnitTest/*.xml |
  sed -E 's/.*name="([^"]+)".*tests="([0-9]+)".*failures="([0-9]+)".*/\1: \2 tests, \3 failures/'
```

## Lint fails on this repo by design — read before reacting

`./gradlew lint` **exits non-zero and always has.** The baseline as of database
v8 is **3 errors, 31 warnings**, and all three errors are the same pre-existing
`MissingPermission` issue:

| File | Issue |
|---|---|
| `tracking/ActivityMonitor.kt` (×2) | `MissingPermission` |
| `ui/EncloseMap.kt` | `MissingPermission` |

If you see exactly those three, **you introduced nothing — move on.** Do not
"fix" them as a side quest; they are untouched legacy and outside the scope of
whatever you are doing.

Compare by **file and issue id, not line number** — line numbers drift with every
edit:

```bash
grep -E "^/home.*Error:" \
  app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt |
  sed -E 's|.*/(app/src/[^:]+):[0-9]+: Error:.*\[([A-Za-z]+)\]|\1 [\2]|' | sort
```

A **fourth** error, or an error in a file you edited, is yours. The usual cause
is calling a permission-gated API where lint can't see the check — if the check
is genuinely there (and wrapped in `runCatching` for the revoke-mid-flight race),
`@Suppress("MissingPermission")` with a comment saying *why* is the honest fix.
That is what `LocationService.requestUpdates()` does.

Worth knowing: the project has no ktlint or detekt, and no lint baseline file.
Adding `lint { baseline = file("lint-baseline.xml") }` to `app/build.gradle.kts`
would make lint pass cleanly and turn any new error into a real signal — a good
idea, but it is a build-config change, so propose it rather than doing it in
passing.

## Notes

- Gradle's **configuration cache is on**. Editing `build.gradle.kts` or
  `libs.versions.toml` invalidates it and the next run is slower; that is normal,
  not a failure. Don't add configuration-phase side effects to build scripts.
- `./gradlew test` runs debug *and* release variants. `testDebugUnitTest` is what
  you want.
- Single test or single method:
  ```bash
  ./gradlew testDebugUnitTest --tests "io.app.enclose.data.ConquestTest"
  ./gradlew testDebugUnitTest --tests "*.ConquestTest.a claim never conquers itself"
  ```
  Test names are backtick-quoted Kotlin sentences; quote them as-is.
- Unit tests are plain JVM JUnit4 — **no Robolectric**, so nothing touching
  `Context`, `SharedPreferences`, or Room can be unit tested. The convention is to
  extract the decision into pure Kotlin and test that: `Conquest`, `Coverage`,
  `MotionGate`, `Place`, and `WalkProgressRecorder` (via the `WalkProgressStore`
  interface) all exist in that shape. JTS is pure Java and works fine in tests.
- `TrackingManager` is a singleton `object`; tests that touch it must reset state
  in `@After` (see `TrackingManagerRestoreTest`).
- Unit tests cannot reach the location service, the geocoder, the map, or the
  migrations. When a change lands in those, say so plainly and use `device-check`
  rather than implying the tests covered it.
