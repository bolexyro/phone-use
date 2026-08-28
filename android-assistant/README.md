# Phone Control Assistant (v0 foundation)

This directory is a standalone native Android app for the Phone Control pivot.
It is intentionally separate from `benchmark-app/` and the TypeScript MCP
server. The app is the phone-side authority for permissions, session state,
notifications and (eventually) observation/action execution.

## Current v0 surface

- Jetpack Compose screen with a typed natural-language request field, Run
  control, current session state and a live user-facing Activity timeline.
- Settings screen that lists launchable non-system user apps. Every package is
  disabled by default and each toggle is persisted locally. There is no global
  enable-all control.
- Foreground service with a persistent notification showing the current
  purpose, Pause/Resume and Stop actions. Opening the notification returns to
  the Activity timeline.
- Typed action models for `open_app`, `tap`, `type`, `swipe`, `back` and `wait`.
  Each action carries a purpose, observation ID, target description and
  optional screenshot guard regions.
- Phone-authoritative `PolicyEngine` skeleton for app allowlisting, fresh
  observation IDs and confirmation categories: send, purchase, transfer,
  delete and submit.
- Official Shizuku API lifecycle and capability detection, plus a typed
  transport boundary. The transport currently fails closed after detection;
  it does not inject input or execute raw shell commands.

The current Run flow records a session and displays that it is waiting for the
desktop Codex bridge. Codex App Server, the desktop companion, screenshot
capture, regional freshness comparison and real Shizuku input injection are
not implemented in this milestone.

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

This repository has not validated physical execution on an S23. A Shizuku
service being detected is not evidence that taps, typing or swipes work on a
particular One UI/device build. The transport therefore returns an explicit
unsupported result for every action until a separate device-validation step
proves the exact operation safely.

## Planned bridge

The intended flow is:

```text
Codex App Server
        -> desktop companion / local MCP adapter
        -> authenticated phone link
        -> this app's SessionCoordinator and PolicyEngine
        -> observation/guard validation
        -> Shizuku transport
```

The desktop side will provide Codex authentication and the agent loop. The
phone will remain the final authority for per-app permissions, confirmation,
stale observations and cancellation. Raw model/provider payloads must not be
treated as phone commands.

## Explicitly out of scope for this milestone

- Voice or wake-word activation.
- Virtual displays or background operation on a second display.
- Play Store distribution.
- Claude or Antigravity integrations.
- A claim of successful physical S23 operation.
