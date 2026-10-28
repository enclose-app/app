---
name: device-check
description: Exercise the app on a connected device or emulator — install, grant permissions, feed mock GPS to walk a loop, read the on-device database, and simulate the low-memory process kill the walk-restore path exists for. Use when a change touches location, the map, the geocoder, or persistence, since unit tests cannot reach any of those.
---

# Checking on a device

Everything below is verified working against an API 36 emulator with
`adb` on `PATH`. `minSdk` is 33 (Android 13), so anything older won't install. Test the lowest
version on an API 33 emulator — the AVDs here are all API 36+.

```bash
adb devices                          # expect a device, e.g. emulator-5554
adb shell getprop ro.build.version.sdk   # must be >= 35
```

If several devices are attached, add `-s <serial>` to every `adb` call.

## Install and launch

```bash
./gradlew installDebug                            # keeps existing data
adb shell am start -n io.app.enclose/.MainActivity
adb exec-out screencap -p > /tmp/enclose.png      # look at it with Read
```

Do **not** `adb uninstall` when the point is to test a migration — a fresh
install creates the newest schema directly and never runs one.

## Permissions

The app degrades quietly when these are missing, so grant them explicitly rather
than wondering why nothing records:

```bash
adb shell pm grant io.app.enclose android.permission.ACCESS_FINE_LOCATION
adb shell pm grant io.app.enclose android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant io.app.enclose android.permission.ACTIVITY_RECOGNITION
adb shell pm grant io.app.enclose android.permission.POST_NOTIFICATIONS
```

To test the revoke-mid-walk path (`LocationService.requestUpdates` must survive
it without crashing), `pm revoke` the same permission while a walk is running.

## Mock GPS — walking a loop

Emulator only. **Longitude comes first**, which is the opposite of every
`LatLng` in this codebase and an easy way to end up in the sea:

```bash
adb emu geo fix 23.7275 37.9838        # lng lat  → Athens
```

Fixes arrive every ~3 s (`UPDATE_INTERVAL_MS`). To claim a real loop the walk
must leave the start zone (>80 m), cover ≥200 m on foot, and come back within
10 m of the start — see `TrackingManager`'s tuning constants. Walk it at walking
pace: a fix every 3 s, about 6.7 m apart (≈2.2 m/s), a 530 m square:

```bash
walk() { # from-lng from-lat to-lng to-lat fixes
  for i in $(seq 1 "$5"); do
    adb emu geo fix \
      "$(awk -v a="$1" -v b="$3" -v i="$i" -v n="$5" 'BEGIN { printf "%.6f", a + (b - a) * i / n }')" \
      "$(awk -v a="$2" -v b="$4" -v i="$i" -v n="$5" 'BEGIN { printf "%.6f", a + (b - a) * i / n }')"
    sleep 3
  done
}
adb emu geo fix 23.7275 37.9838; sleep 3
walk 23.7275 37.9838 23.7275 37.9850 20   # north
walk 23.7275 37.9850 23.7290 37.9850 20   # east
walk 23.7290 37.9850 23.7290 37.9838 20   # south
walk 23.7290 37.9838 23.7275 37.9838 20   # west, back to the start
```

Mind the anti-cheat, which never ends a walk but does judge it at Stop, in 30 s
stretches. At or below 2.5 m/s a stretch counts with no proof; up to the walk's
5 m/s ceiling it counts on steps — or, with no step data at all, unless the
classifier said vehicle; past the ceiling it is doubtful whatever. So the old
habit of hopping ~100 m between fixes (≈20 m/s) now makes every stretch
doubtful, and Stop keeps the walk under "Walks that didn't count"
(`TOO_MUCH_BRIDGED`) instead of offering a claim. To see a bridge, send part of
the loop well past 5 m/s (fewer fixes over the same leg) and Stop at the start:
up to 20% of the boundary bridged is claimed, more is kept without a claim. The
in-app **test mode** (map taps, relaxed thresholds, never judged) is quicker
still if you only need a claim to exist.

### Steps

The phone's step counter (`PhoneStepCounter`) needs `ACTIVITY_RECOGNITION` and a
`TYPE_STEP_COUNTER` sensor; without either it reports nothing and the walk is
judged without phone steps. Emulators rarely count, so step evidence needs a
physical phone. While a walk records, a report lands about every 10 s, zero-step
ones included — the rows are the proof the heartbeat is running, screen off
included (pull the database as in *Reading the database* below):

```bash
sqlite3 enclose.db "SELECT source, (toEpochMs - fromEpochMs) / 1000 AS s, steps
  FROM walk_progress_steps ORDER BY fromEpochMs DESC LIMIT 10;"
adb shell dumpsys sensorservice | grep -i -A2 "step counter"   # the registration
```

### The watch's steps

Needs a phone and a watch paired through the Wear OS app (for emulators,
Android Studio's Wear OS pairing assistant), with both apps installed — the
watch's with `ANDROID_SERIAL` set, since `installDebug` alone hits every device.
The watch app asks for `ACTIVITY_RECOGNITION` when it opens; grant it there or:

```bash
W=<watch serial>
adb -s $W shell pm grant io.app.enclose android.permission.ACTIVITY_RECOGNITION
```

Start a walk on the phone, then read what the watch holds. `registered` is set
while it counts (boot count, then versionCode), `walk` is the walk's start once
its first fix has reached the watch, and `intervals` is what it sends:

```bash
adb -s $W exec-out run-as io.app.enclose cat shared_prefs/step_witness.xml
```

On the phone, the watch's reports land beside the phone's (pull the database as
in *Reading the database* below):

```bash
sqlite3 enclose.db "SELECT source, count(*), sum(steps) FROM walk_progress_steps GROUP BY source;"
```

Health Services batches passive data, so with the watch screen off the first
`WATCH` rows can take minutes; Stop is where the rest is asked for. Real steps
need a physical watch. On Wear OS 4 and up an emulator's synthetic data comes
from Android Studio's Wear Health Services panel, and the `whs.synthetic.*` adb
broadcasts older guides give are Wear OS 3 only, below this app's `minSdk`.
Whether either feeds *passive* monitoring is unverified.

To see Stop wait for the watch, the walk needs a stretch in doubt. Without phone
steps that means faster than the walk's 5 m/s ceiling, so send one leg at about
8 m/s. Stop then shows "Asking your watch for its last steps…" for up to 10 s,
less when the watch answers. No step count rescues a leg past the ceiling, so
this checks the round trip, not the verdict.

## Reading the database

`sqlite3` is **not** on the device. Pull the files and query on the host — take
all three, since Room runs in WAL mode and recent writes live in `-wal`:

```bash
cd "$(mktemp -d)"
for f in enclose.db enclose.db-wal enclose.db-shm; do
  adb exec-out run-as io.app.enclose cat "databases/$f" > "$f" 2>/dev/null
done
sqlite3 enclose.db "SELECT name FROM sqlite_master WHERE type='table';"
sqlite3 enclose.db "SELECT id, name, city, areaSqMeters FROM territories;"
sqlite3 enclose.db "SELECT count(*) FROM walk_progress_points;"
```

Useful checks: rows in `walk_progress_points` mean a walk is being mirrored to
disk as it happens. After an upgrade across `MIGRATION_13_14`, no claim carries
a carve or conquest stamp — nothing writes them any more, and the migration
clears them as it puts each cut claim back to its ring — and every claim's area
is its walk's again, since both are measured from the ring:

```bash
sqlite3 enclose.db "SELECT count(*) FROM territories WHERE conqueredAtEpochMs IS NOT NULL
  OR conqueredById IS NOT NULL OR carvedAtEpochMs IS NOT NULL;"   # expect 0
sqlite3 enclose.db "SELECT t.name, t.areaSqMeters, w.areaSqMeters FROM territories t
  JOIN walks w ON w.id = t.id WHERE abs(t.areaSqMeters - w.areaSqMeters) > 1;"   # expect no rows
```

## Simulating process death — the walk-restore path

This is the one that matters and the one nothing else covers.
`WalkProgressRecorder` + `TrackingManager.restore` exist entirely for it.

```bash
# 1. start a walk in the app and feed it a few fixes (above)
adb shell run-as io.app.enclose cat databases/enclose.db-wal | wc -c   # sanity: growing

# 2. background the app, then SIGKILL the process
adb shell input keyevent KEYCODE_HOME
adb shell kill "$(adb shell pidof io.app.enclose)"

# 3. the system restarts the START_STICKY service; watch it come back
adb logcat -d | grep -iE "enclose|LocationService" | tail -30
```

Use `kill` on the pid, not `am force-stop` — force-stop deliberately prevents the
service restart, so it tests the opposite of what you want. `am kill` often
refuses while a foreground service makes the process perceptible.

**Expected:** the walk resumes with its path intact and keeps recording. The old
bug was the notification still claiming to record while every fix was dropped, so
check that new fixes actually extend the path (`walk_progress_points` keeps
growing) rather than just that the app is alive.

## Logs

```bash
adb logcat -c                                    # clear first
adb logcat -d --pid="$(adb shell pidof io.app.enclose)" | tail -50
adb logcat -d | grep -iE "EncloseSync|SQLite|Room|FATAL" | tail -20
```
