# Coordinate Benchmark

This is a small Jetpack Compose app for measuring screenshot-to-coordinate
tap accuracy with Phone Control. It displays one green target and two same-size
gray decoys on a dark arena. The circles default to 20 dp in diameter,
intentionally smaller than a standard touch target, and their radius is
configurable from 6–24 dp (12–48 dp diameter) before or during a session. A
target stays in place until it is hit.

- Successful taps increment `Hit`, clear the failure state, and randomize the
  next target and decoys.
- Background and decoy taps increment `Miss`, leave the target in place, and
  keep a visible `FAILED` message until the next successful tap.
- Before the benchmark starts, enter the coding agent and reasoning level.
- The target-size slider remains available during a session; changes are
  recorded as `radius_change` events.
- A background-noise slider adds 0–80 low-contrast gray dots. These are
  decorative only and are not counted as decoys; changes are recorded as
  `noise_change` events.
- A decoy slider controls 0–50 muted, non-green decoy points. Changing it during a session
  regenerates the decoys while preserving the current target and records a
  `decoy_change` event.
- Every session writes `session_start`, `attempt`, and `session_end` records to
  the app-private file `coordinate-benchmark.ndjson`. Records include the
  session ID, timestamps (epoch and ISO-8601), agent, reasoning level,
  coordinates, outcome, target distance/radius, target center, and totals.

The app package is `com.phonecontrol.coordinatebenchmark`. Build it with
`gradlew.bat :app:assembleDebug` from this directory and install the generated
`app/build/outputs/apk/debug/app-debug.apk` with ADB.

To retrieve the data after a run:

```powershell
adb shell run-as com.phonecontrol.coordinatebenchmark cat files/coordinate-benchmark.ndjson > coordinate-benchmark.ndjson
```
