# DHD (v0 phone assistant)

This directory is a standalone native Android app for the Phone Control pivot.
It is intentionally separate from `apps/coordinate-benchmark-android/` and the TypeScript MCP
server. The app is the phone-side authority for permissions, session state,
notifications, request handoff, and observation/action execution.

## Current v0 surface

- ChatGPT-inspired Jetpack Compose client branded DHD: one continuous assistant
  timeline, a compact typed request composer, and Settings. Local request,
  run, message and activity metadata is persisted in Room. The UI shows recent
  activity from the last 24 hours while the underlying Codex context may span
  much longer.
- Activity rows are purpose-first and use the supplied connected-nodes icon.
  Each task groups its tool activity into a compact stack. The stack can be
  expanded and independently scrolled to show the purpose, target, status and
  timestamp; screenshots, raw arguments and private reasoning are never
  persisted.
- Settings screen that lists launchable non-system user apps. Every package is
  disabled by default and each toggle is persisted locally. There is no global
  enable-all control.
- Foreground service with a persistent notification showing the current
  purpose, Pause/Resume and Stop actions. Opening the notification returns to
  the DHD assistant timeline.
- Typed action models for `open_app`, `tap`, `type`, `swipe`, `scroll`,
  `keypress`, `back` and `wait`.
  Each action carries a purpose and target description. Observation IDs and
  screenshot guard regions remain in the internal model for a future safety
  pass, but are not required by the current assistant bridge.
- Phone-authoritative `PolicyEngine` for app allowlisting and confirmation
  categories: send, purchase, transfer, delete and submit.
- Official Shizuku API lifecycle and capability detection, plus a typed
  transport for `am start`, `input tap`, `input text`, `input swipe`, and
  `input keyevent`. The app builds those argv arrays itself; no raw
  provider/model shell command is accepted. Before an input action, the phone
  captures a current shell screenshot for foreground binding and coordinate
  bounds. Screenshot fingerprint/guard freshness rejection is deferred while
  the first assistant prototype is tuned.

Run now creates a phone-owned request that the desktop Codex companion can
claim. An authenticated LAN development bridge carries that handoff and the
typed execution path: the companion starts a Codex App Server turn, while the
configured MCP adapter sends allowlist, observation, and action requests back
to this app over NDJSON. The legacy `demo_run` request remains available for
the open -> observe -> tap smoke test.

DHD keeps one local assistant conversation. The stored Codex thread is reused
when a new request arrives within three hours of the last activity. After three
hours of inactivity, the phone clears only that remote thread binding; the next
request starts a fresh Codex thread while the local activity history remains
available in the recent-history window.

## Build

Use the included Gradle wrapper from this directory:

```powershell
cd apps/dhd-android
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. The build needs an Android SDK
with API 35 and an installed JDK 17. No Android device is required for the
unit tests.

For the container-first verification path, run this from the repository root:

```powershell
docker build -t phone-control-android-assistant:check apps/dhd-android
```

The first run downloads the Android SDK base image; subsequent checks reuse
Docker's layer cache.

## Sideload/development setup

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install and start the [Shizuku app](https://github.com/RikkaApps/Shizuku),
start its service using the device-supported wireless-debugging or ADB path,
launch DHD, and open Settings. The app reports binder
availability and permission state and can request the Shizuku API permission.

The typed open -> observe -> tap path has been physically smoke-tested on a
Samsung S23 with Shizuku using the Coordinate Benchmark package. A Shizuku
service being detected is not evidence that all input types work on a
particular One UI/device build; the benchmark smoke path should be rerun after
transport changes.

## Dummy desktop bridge (open -> observe -> tap)

The Android app starts an authenticated NDJSON listener on TCP port `8765` and
a short-code discovery listener on UDP port `8766` while its process is alive.
For the wireless path, keep the phone and development machine on the same
reachable Wi-Fi, open DHD Settings → Companion connection, and copy the short
pairing code into the companion dashboard:

```powershell
pnpm companion:dashboard
```

Open `http://127.0.0.1:8766`, enter the code, and choose **Pair phone**. The
companion broadcasts the code locally; this phone bridge answers with its
current address, port, and credential. The dashboard stores those details so
you do not have to copy them individually. The code remains valid until you
refresh it in DHD Settings.

`adb forward` remains a loopback fallback for local development:

```powershell
adb forward tcp:8765 tcp:8765
```

From the repository root, send the deterministic demo plan (the benchmark
package is the safe default; pass another package only after enabling it in
the app's Approved apps settings):

```powershell
pnpm companion:bridge-demo -- --package com.phonecontrol.coordinatebenchmark --x 500 --y 900
```

Optional flags are `--host`, `--port`, `--token`, `--purpose`, and `--target`.
For wireless use, prefer `PHONE_ASSISTANT_BRIDGE_TOKEN` so the token is not
stored in shell history. The phone is still the authority: it checks Shizuku state, the per-app allowlist,
foreground binding, and coordinate bounds
before it sends `input tap`. Use the Coordinate Benchmark app for repeatable
tests; it is the only package enabled in the current physical smoke setup.
The desktop script is only a hard-coded demo.
The real Codex-facing adapter is `pnpm companion:tools`; run
`pnpm companion:worker` to pick up requests typed in the phone app. Both are
documented in `../../docs/codex-app-server-phone-assistant.md`.

## Bridge architecture

The current flow is:

```text
Phone typed request
        -> desktop companion
        -> Codex App Server
        -> local MCP adapter
        -> authenticated phone link
        -> this app's SessionCoordinator and PolicyEngine
        -> screenshot context and foreground/bounds validation
        -> Shizuku transport
```

The desktop side uses the Codex CLI's existing authentication and subscription
through App Server. The phone remains the final authority for per-app
permissions, confirmation and cancellation. Raw
model/provider payloads must not be treated as phone commands.

The MCP adapter exercises the same typed phone protocol today. Codex App
Server loads it as a configured local MCP server; it does not need a raw ADB
shell or an experimental dynamic-tool registration.

## Explicitly out of scope for this milestone

- Voice or wake-word activation.
- Virtual displays or background operation on a second display.
- Play Store distribution.
- Claude or Antigravity integrations.
- A claim of successful physical S23 operation.
