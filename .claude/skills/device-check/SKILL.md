---
name: device-check
description: Exercise the app on a connected device or emulator — install, grant permissions, feed mock GPS to walk a loop, read the on-device database, and simulate the low-memory process kill the walk-restore path exists for. Use when a change touches location, the map, the geocoder, or persistence, since unit tests cannot reach any of those.
---

# Checking on a device

Everything below is verified working against an API 36 emulator with
`adb` on `PATH`. `minSdk` is 35, so anything older won't install.

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
must leave the start zone (>80 m), cover ≥200 m, and come back within 10 m of the
start — see `TrackingManager`'s tuning constants. Step a few hundred metres out
and back, pausing a few seconds between calls:

```bash
for c in "23.7275 37.9838" "23.7275 37.9848" "23.7285 37.9848" "23.7285 37.9838" "23.7275 37.9838"; do
  adb emu geo fix $c; sleep 5
done
```

Beware the anti-cheat: jumping too far between fixes reads as a vehicle and
`MotionGate` will void the walk after `GRACE_MS` (30 s). Small steps, real
pauses. The in-app **test mode** (map taps, relaxed thresholds, gate bypassed) is
the quicker path if you only need a claim to exist.

## Reading the database

`sqlite3` is **not** on the device. Pull the files and query on the host — take
all three, since Room runs in WAL mode and recent writes live in `-wal`:

```bash
cd "$(mktemp -d)"
for f in enclose.db enclose.db-wal enclose.db-shm; do
  adb exec-out run-as io.app.enclose cat "databases/$f" > "$f" 2>/dev/null
done
sqlite3 enclose.db "SELECT name FROM sqlite_master WHERE type='table';"
sqlite3 enclose.db "SELECT id, name, city, conqueredAtEpochMs FROM territories;"
sqlite3 enclose.db "SELECT count(*) FROM walk_progress_points;"
```

Useful checks: `conqueredAtEpochMs IS NOT NULL` proves conquest archived rather
than deleted; rows in `walk_progress_points` mean a walk is being mirrored to
disk as it happens.

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
