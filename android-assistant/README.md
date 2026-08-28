# Phone Control Assistant (v0 foundation)

This directory is a standalone native Android app for the Phone Control pivot.
It is intentionally separate from `benchmark-app/` and the TypeScript MCP
server. The app is the phone-side authority for permissions, session state,
notifications, request handoff, and observation/action execution.

## Current v0 surface

- Jetpack Compose screen with a typed natural-language request field, Run
  control, current session state and a live user-facing Activity timeline.
- Settings screen that lists launchable non-system user apps. Every package is
  disabled by default and each toggle is persisted locally. There is no global
  enable-all control.
- Foreground service with a persistent notification showing the current
  purpose, Pause/Resume and Stop actions. Opening the notification returns to
  the Activity timeline.
- Typed action models for `open_app`, `tap`, `type`, `swipe`, `scroll`,
  `keypress`, `back` and `wait`.
  Each action carries a purpose, observation ID, target description and
  optional screenshot guard regions.
- Phone-authoritative `PolicyEngine` skeleton for app allowlisting, fresh
  observation IDs and confirmation categories: send, purchase, transfer,
  delete and submit.
- Official Shizuku API lifecycle and capability detection, plus a typed
  transport for `am start`, `input tap`, `input text`, `input swipe`, and
  `input keyevent`. The app builds those argv arrays itself; no raw
  provider/model shell command is accepted. Before every input action, the
  phone captures a fresh shell screenshot and rejects stale package, activity,
  display, full-screen, or declared guard-region state.

Run now creates a phone-owned request that the desktop Codex companion can
claim. A localhost-only development bridge carries that handoff and the typed
execution path: the companion starts a Codex App Server turn, while the
configured MCP adapter sends allowlist, observation, and action requests back
to this app over NDJSON. The legacy `demo_run` request remains available for
the open -> observe -> tap smoke test.

## Build

Use the included Gradle wrapper from this directory:

```powershell
cd android-assistant
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. The build needs an Android SDK
with API 35 and an installed JDK 17. No Android device is required for the
unit tests.

For the container-first verification path, run this from the repository root:

```powershell
docker build -t phone-control-android-assistant:check android-assistant
```

The first run downloads the Android SDK base image; subsequent checks reuse
Docker's layer cache.

## Sideload/development setup

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install and start the [Shizuku app](https://github.com/RikkaApps/Shizuku),
start its service using the device-supported wireless-debugging or ADB path,
launch Phone Control Assistant, and open Settings. The app reports binder
availability and permission state and can request the Shizuku API permission.

The typed open -> observe -> tap path has been physically smoke-tested on a
Samsung S23 with Shizuku using the Coordinate Benchmark package. A Shizuku
service being detected is not evidence that all input types work on a
particular One UI/device build; the benchmark smoke path should be rerun after
transport changes.

## Dummy desktop bridge (open -> observe -> tap)

The Android app starts a loopback-only NDJSON listener on port `8765` while its
process is alive. Keep the app open, connect it to the development machine,
and forward that port:

```powershell
adb forward tcp:8765 tcp:8765
```

From the repository root, send the deterministic demo plan (the benchmark
package is the safe default; pass another package only after enabling it in
the app's Approved apps settings):

```powershell
pnpm bridge:demo -- --package com.phonecontrol.coordinatebenchmark --x 500 --y 900
```

Optional flags are `--host`, `--port`, `--purpose`, and `--target`. The phone
is still the authority: it checks Shizuku state, the per-app allowlist, the
fresh observation binding, coordinate bounds, and screen/guard freshness
before it sends `input tap`. Use the Coordinate Benchmark app for repeatable
tests; it is the only package enabled in the current physical smoke setup.
The desktop script is only a hard-coded demo.
The real Codex-facing adapter is `pnpm assistant:mcp`; run
`pnpm assistant:companion` to pick up requests typed in the phone app. Both are
documented in `../docs/codex-app-server-phone-assistant.md`.

## Bridge architecture

The current flow is:

```text
Phone typed request
        -> desktop companion
        -> Codex App Server
        -> local MCP adapter
        -> authenticated phone link
        -> this app's SessionCoordinator and PolicyEngine
        -> observation/guard validation
        -> Shizuku transport
```

The desktop side uses the Codex CLI's existing authentication and subscription
through App Server. The phone remains the final authority for per-app
permissions, confirmation, stale observations and cancellation. Raw
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
