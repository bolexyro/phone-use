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
  Input actions carry a purpose, target description, and observation ID;
  `open_app` establishes its launch baseline internally and returns a fresh
  observation for the next input action.
  The DHD companion can also submit up to 16 non-`open_app` typed actions as
  one observation-safe sequence; the phone advances only through verified
  post-action observations.
- Phone-authoritative `PolicyEngine` for app allowlisting and confirmation
  categories: send, purchase, transfer, delete and submit.
- DHD-owned Android 11+ Wireless Debugging ADB lifecycle, one-time pairing,
  encrypted device identity storage, automatic reconnect, and a typed
  transport for `am start`, `input tap`, `input text`, `input swipe`, and
  `input keyevent`. The app builds those argv arrays itself; no raw
  provider/model shell command is accepted. Before an input action, the phone
  captures a current ADB screenshot for foreground binding and coordinate
  bounds. Structural observation fields are compared before input.

Run now creates a phone-owned request that the desktop Codex companion can
claim. An authenticated LAN development bridge carries that handoff and the
typed execution path: the companion starts a Codex App Server turn, while the
configured MCP adapter sends allowlist, foreground-context, observation,
single-action, and sequence requests back to this app over NDJSON. The legacy
`demo_run` request remains available for the open -> observe -> tap smoke test.

When an action is refused because its observation is stale, the bridge response
sets `inputSent: false` and includes the approved/current observation IDs plus
one reason for each detected delta. `GUARD_REGION_CHANGED` identifies a
configured guard fingerprint difference; rotation, display identity/size,
package, activity, and observation replacement have separate reason codes.

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

On Android 11 and newer, DHD connects directly to the phone's Wireless
Debugging ADB service. DHD cannot silently enable that protected setting. Do
this once:

1. Open Android Developer options and turn on **Wireless debugging**.
2. In DHD, open **Settings → DHD phone access → Pair DHD once**.
3. In Android Wireless debugging, choose **Pair device with pairing code**.
4. Enter Android's six-digit code in DHD and tap **Pair**.

After pairing, DHD reconnects by itself whenever Wireless debugging is turned
on. You do not need a second app or a separate **Start** button. If Android
stops DHD's process, open DHD once so its controller can restart; it will then
continue reconnecting automatically. The direct path still needs a physical
Samsung S23/One UI smoke test before it is considered device-validated.

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
stored in shell history. The phone is still the authority: it checks DHD's Wireless Debugging connection, the per-app allowlist,
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
        -> DHD-owned Wireless Debugging ADB transport
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
