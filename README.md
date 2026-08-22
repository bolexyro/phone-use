# Phone Use MCP server

Phone Control is a local TypeScript MCP server for controlling one authorized Android device through a small, allowlisted operation set. It uses ADB for device I/O and UI Automator for visible controls. It never exposes an ADB shell to MCP callers.

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

## MCP client configuration

Build the server, then add an entry like this to the client configuration. Escape Windows backslashes as required by the client’s JSON format.

```json
{
  "mcpServers": {
    "phone-control": {
      "command": "node",
      "args": ["C:\\Users\\USER\\Documents\\ChatGPT\\Project Phone Control\\dist\\index.js"],
      "env": {
        "PHONE_CONTROL_PROFILE": "local",
        "PHONE_CONTROL_DEVICE_SERIAL": "R5CT...",
        "PHONE_CONTROL_CONFIG_PATH": "C:\\Users\\USER\\Documents\\ChatGPT\\Project Phone Control\\config\\phone-control.json",
        "PHONE_CONTROL_AUDIT_LOG_PATH": "C:\\Users\\USER\\Documents\\ChatGPT\\Project Phone Control\\logs\\phone-control.actions.ndjson"
      }
    }
  }
}
```

The server writes JSON-RPC only to stdout. Startup diagnostics and recoverable tool diagnostics go to stderr.

## Tools

- `phone_status` reports the selected device, primary foreground package, and all active virtual display sessions.
- `phone_list_allowed_apps` reports the active server-side allowlist.
- `phone_open_app({ packageName, useVirtualDisplay? })` launches an allowlisted package and returns a fresh observation. Defaults to running in an isolated virtual display on Android 10+ (`useVirtualDisplay: true`). If virtual display creation fails or is unsupported (device API < 29), returns a descriptive error; ask the user before calling with `useVirtualDisplay: false` to launch directly on the primary display.
- `phone_close_app({ packageName?, displayId? })` terminates an active virtual display session.
- `phone_observe({ displayId?, packageName?, includeScreenshot? })` captures UI Automator metadata and a native PNG from the target display. When `includeScreenshot` is true, the PNG is returned as MCP `image/png` content.
- `phone_execute({ observationId, action })` executes exactly one action against the supplied observation, routing input directly to the observation's bound display.
- `phone_wait_for({ observationId, condition, timeoutMs? })` waits for a bounded condition on the bound display and returns a fresh observation when it matches.

The public action contract is:

```json
{ "type": "click", "elementRef": "..." }
{ "type": "click_coordinate", "x": 100, "y": 200 }
{ "type": "scroll", "direction": "up", "amount": "small" }
{ "type": "type", "text": "123" }
{ "type": "keypress", "key": "BACK" }
```

`key` is one of `BACK`, `HOME`, `ENTER`, and `DELETE`. Wait conditions are `foreground_package`, `visible_text`, `visible_resource_id`, and `ui_tree_changed`. Every wait call supplies the baseline `observationId` separately from the condition.

### Virtual Display Architecture

When `phone_open_app` is called on Android 10+ (API 29+), Phone Control uses `scrcpy` with the following flags to establish an isolated virtual display:

- `--new-display`: Creates an isolated secondary display without disrupting the phone's primary screen.
- `--start-app=com.example.app`: Strictly launches only the allowlisted package in that virtual display.
- `--no-vd-system-decorations`: Removes navigation and status bars for a clean app canvas.
- `--mouse=disabled`: Disables host mouse events inside the mirrored window to prevent accidental user interference with agent automation.
- `--no-audio`: Prevents audio stream overhead.
- `--stay-awake`: Prevents display timeout while the session is active.

Multiple virtual displays can run concurrently for multi-app automation. Observations and actions automatically bind to the specific `displayId` via `observationId`.

The policy is loaded by the server and is not an MCP input. The sample local profile allows only `com.sec.android.app.popupcalculator`. Foreground policy is checked before and after app launches and actions. Observation IDs are opaque, tied to the device, package/activity, display, rotation, UI hash, screenshot dimensions, and capture time. Any attempted action consumes its observation; state changes return `STALE_OBSERVATION`.

Click results include a pointer event with the resolved coordinates. The NDJSON audit log defaults to `logs/phone-control.actions.ndjson` and can be changed with `PHONE_CONTROL_AUDIT_LOG_PATH`. It records action metadata and text length, never typed text values. Screenshots are passed through as native PNG bytes.

## Optional scrcpy viewer

The server does not require scrcpy. To watch or manually take over the selected device, run the bundled viewer separately:

```powershell
.\scrcpy-win64-v4.1\scrcpy.exe -s R5CT...
```

## Visible cursor viewer

The Windows-only Electron viewer manages a movable scrcpy window and a transparent, always-on-top, click-through overlay. The overlay never moves the real Windows mouse or sends input itself; normal mouse and keyboard input can still go to scrcpy and the phone. The overlay tails pointer-start records from the NDJSON audit log and keeps a small black/white/cyan cursor visible, animating it to each device coordinate before the corresponding tap is sent. The action result is logged separately after the tap completes.

Build and run it from PowerShell:

```powershell
pnpm build
pnpm viewer
```

The requested default S23-friendly geometry is `x=60`, `y=60`, `width=432`, `height=936` in physical screen pixels. If that rectangle does not fit the current display work area, the viewer scales it down while preserving the phone aspect ratio. scrcpy uses the fitted rectangle, while the overlay converts it to Electron DIP coordinates so Windows display scaling does not offset the cursor. scrcpy is launched with `--render-fit=stretched` for stable v1 mapping. Override it with:

- `PHONE_CONTROL_VIEWER_X`
- `PHONE_CONTROL_VIEWER_Y`
- `PHONE_CONTROL_VIEWER_WIDTH`
- `PHONE_CONTROL_VIEWER_HEIGHT`
- `PHONE_CONTROL_VIEWER_CURSOR_DURATION_MS` (default `700`)
- `PHONE_CONTROL_VIEWER_AUDIT_POLL_INTERVAL_MS` (default `100`)
- `PHONE_CONTROL_AUDIT_LOG_PATH` (shared audit path)
- `PHONE_CONTROL_SCRCPY_PATH`

scrcpy resolution is `PHONE_CONTROL_SCRCPY_PATH`, then `scrcpy-win64-v4.1\scrcpy.exe`, then PATH. The viewer validates the configured serial with the same authorized-device selection used by MCP; without a configured serial it requires exactly one authorized device. The native scrcpy title bar can be dragged normally. The viewer listens for native Win32 move/resize notifications and keeps a 250 ms watchdog as a recovery path. While scrcpy is moving, minimized, unavailable, or has not produced two stable client-rectangle samples, the transparent cursor layer is hidden; it becomes visible again after the overlay catches up. This prevents a stale cursor from remaining on the desktop after a fast drag. The viewer tracks scrcpy's native client rectangle while it runs, so moving or resizing the window moves the transparent cursor layer with it.

## Checks

```powershell
pnpm typecheck
pnpm test
pnpm build
git diff --check
docker build -t phone-control-mcp:check .
```

The Dockerfile runs typecheck, tests, and build without USB access. A Docker container cannot perform the physical phone smoke test unless ADB/device access is deliberately provided separately.

## Exact Samsung S23 Calculator smoke test

1. On the S23, enable Developer options and USB debugging. Connect the phone, accept the RSA prompt, and confirm one `device` entry with `adb devices -l`.
2. Copy the sample policy to `config/phone-control.json` and start the configured MCP client with the S23 serial in `PHONE_CONTROL_DEVICE_SERIAL`.
3. Call `phone_status`; confirm the selected serial and an allowlisted foreground package.
4. Call `phone_list_allowed_apps`; confirm `com.sec.android.app.popupcalculator` is present.
5. Call `phone_open_app({"packageName":"com.sec.android.app.popupcalculator"})`. Save the returned fresh `observationId`.
6. Call `phone_observe({"includeScreenshot":true})`. From its elements, locate the visible `1` button by its returned `elementRef`.
7. Call `phone_execute` with that observation ID and `{ "type":"click", "elementRef":"<1-ref>" }`. Use the returned fresh observation for the next action.
8. Repeat observe-then-click for `+`, `2`, and `=`. Never reuse an observation after an attempted action.
9. Call `phone_wait_for` with the latest observation ID and `{ "type":"visible_text", "text":"3" }`, plus `timeoutMs:3000`. Confirm it returns a fresh observation.
10. Inspect `logs/phone-control.actions.ndjson`; confirm click coordinates are present and no typed text value appears. Optionally run scrcpy in a separate window to watch the flow.
