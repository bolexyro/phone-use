# Phone Use MCP server

Phone Control is a local TypeScript MCP server for controlling one authorized Android device through a small, allowlisted operation set. It uses display-scoped screenshots for observation and typed ADB operations for device input. It never exposes an ADB shell to MCP callers.

## Visual-first interaction strategy

The initial control flow is visual-only. An agent receives a fresh screenshot for the requested display, identifies a visible target, and returns display coordinates. Phone Control validates the observation and policy context, injects the input into the same display, and captures a fresh screenshot to verify the result.

Each visual observation and action must remain bound to its device, app session, `displayId`, foreground package, rotation, and screenshot dimensions. A capture must fail closed when its display provenance cannot be established; it must never silently use whichever display happens to be active.

This is an intentional product decision:

- Shell `uiautomator dump` cannot select an Android display. With concurrent virtual displays, its hierarchy may describe a different user's or agent's screen, so synchronization cannot make it a trustworthy display-scoped observation source.
- Early coordinate-targeting benchmarks with ChatGPT and Gemini have been accurate, including very small hit targets.

Appium, an Android-side instrumentation bridge, and other semantic-control approaches are deferred. They will be considered only if visual-only benchmarks reveal material accuracy or reliability problems. The benchmark should measure task completion, coordinate error, misclicks, retries, token usage, and end-to-end latency across static, dynamic, scrolled, obscured, and visually ambiguous interfaces.

Use `mode: "visual"` with `phone_observe_app` (or `phone_open_app`) to make this route explicit. The returned observation contains `mode: "visual"`, a screenshot SHA-256 fingerprint, logical `displayId`, foreground package/activity, display geometry, rotation, and screenshot dimensions. A coordinate action must use that observation; Phone Control refreshes the same context before dispatch, rejects changed screenshots or hard context, and stores a fresh screenshot-based observation after the action. Visual captures never call shell `uiautomator dump`; semantic mode remains the default for display 0.

Agent-facing visual responses include a small calibration marker on the first screenshot and a last-tap marker after successful clicks. A successful coordinate click also returns a compact annotated crop from the exact pre-tap observation, followed by the full fresh current screenshot. `screenshotEvidence.crop` maps the crop back to display coordinates; the annotated images are presentation copies and never change the screenshot bytes or hashes used for freshness validation.

When more than one virtual display session is active, visual observation requires an explicit `displayId`. Coordinate screenshots must have the exact requested display dimensions; there is no implicit scaling or rotation transform.

## Windows setup

Requirements:

- Node.js 22.13 or newer
- pnpm
- Android platform-tools, or the bundled `scrcpy-win64-v4.1\adb.exe`
- An Android phone with USB debugging or wireless debugging enabled

From PowerShell:

```powershell
Copy-Item config/phone-control.example.json config/phone-control.json
pnpm install
pnpm typecheck
pnpm test
pnpm build
```

The ADB executable is resolved in this order:

1. `PHONE_CONTROL_ADB_PATH`
2. `scrcpy-win64-v4.1\adb.exe` in the project directory
3. `adb.exe`/`adb` found through `PATH`

Use `PHONE_CONTROL_DEVICE_SERIAL` when more than one authorized device is connected. Without it, exactly one device in the ADB `device` state is required. `PHONE_CONTROL_PROFILE` defaults to `local`; `PHONE_CONTROL_CONFIG_PATH` can point at another server-side policy file.

To permit every non-empty Android package, set the active profile's
`allowAllApps` flag and omit `allowedApps`:

```json
{
  "profiles": {
    "local": {
      "allowAllApps": true
    }
  }
}
```

The MCP still exposes only its typed phone-control actions; this setting removes
the package-level allowlist, not the observation and input validation checks.

## Phone-assistant pivot

The standalone `apps/dhd-android/` app is now the phone-side Watch-mode
authority. The companion registers direct `dhd_*` tools with each Codex App
Server turn (and the reusable DHD MCP adapter remains available for manual MCP
clients); setup and the typed action contract are in
[`docs/codex-app-server-phone-assistant.md`](docs/codex-app-server-phone-assistant.md).
The normal development path is now wireless: open DHD Settings → Companion
connection, copy the short pairing code, and enter it in the companion
dashboard. The companion broadcasts that code locally, the phone bridge
answers with its current route, and the worker connects without manual IP,
port, or token copying. `adb forward` remains available as a loopback-only
fallback.

The desktop companion dashboard is available with `pnpm companion:dashboard`.
It pairs with the phone by short code, checks the phone link, starts or stops
the existing companion worker, and shows local activity. The discovered
connection is saved locally so the companion can rediscover the phone when its
local network address changes. No Docker build or dependency install is part
of this command.

### DHD Codex home and authentication

DHD keeps its Codex identity separate from the normal desktop Codex home:

- `%USERPROFILE%\.dhd\codex-home` stores DHD's Codex configuration and login.
- `%USERPROFILE%\.dhd\codex-runtime` is the App Server working directory and
  contains DHD's `AGENTS.md`.

Authenticate the DHD home once from PowerShell. The environment override below
is restored when the command finishes; it does not change Windows or the
desktop Codex process:

```powershell
$dhdHome = Join-Path $env:USERPROFILE ".dhd\codex-home"
New-Item -ItemType Directory -Force -Path $dhdHome | Out-Null

$oldCodexHome = $env:CODEX_HOME
try {
  $env:CODEX_HOME = $dhdHome
  codex login
  codex login status
} finally {
  if ($null -eq $oldCodexHome) {
    Remove-Item Env:CODEX_HOME -ErrorAction SilentlyContinue
  } else {
    $env:CODEX_HOME = $oldCodexHome
  }
}
```

Choose **Sign in with ChatGPT**. Login is normally a one-time setup; repeat it
only if the credentials expire or are revoked, you log out, or the DHD home is
deleted. Do not copy credentials from `%USERPROFILE%\.codex`.

After authentication, run:

```powershell
pnpm companion:worker
```

The companion passes `CODEX_HOME` only to its App Server child, so no manual
environment override is needed for normal DHD use. Set
`PHONE_ASSISTANT_CODEX_HOME` to use another authenticated home, or
`PHONE_ASSISTANT_CODEX_CWD` to use another runtime directory. The
`codex:app-server` script starts Codex directly and does not apply this DHD
child-process wiring.

See the [official Codex CLI sign-in documentation](https://learn.chatgpt.com/docs/codex/cli)
for the general login flow.

The phone conversation shows user-facing completion feedback and summarized
tool steps. Completion and attention notifications open the conversation when
tapped; the assistant does not automatically steal the foreground from the
app being operated.
