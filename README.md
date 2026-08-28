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

## Phone-assistant pivot

The standalone `android-assistant/` app is now the phone-side Watch-mode
authority. The companion registers direct `phone_control_*` tools with each
Codex App Server turn (and the reusable `phone_assistant` MCP adapter remains
available for manual MCP clients); setup and the typed action contract are in
[`docs/codex-app-server-phone-assistant.md`](docs/codex-app-server-phone-assistant.md).
Run `pnpm assistant:companion` after forwarding port `8765` to let requests
typed in the phone app start Codex turns automatically.

The phone conversation shows user-facing completion feedback and summarized
tool steps. Completion and attention notifications open the conversation when
tapped; the assistant does not automatically steal the foreground from the
app being operated.
