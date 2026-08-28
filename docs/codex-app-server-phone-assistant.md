# Codex App Server + Phone Assistant

The desktop side of the pivot has two small local processes:

1. `assistant:companion` watches the phone for a request typed in the Android
   app and starts one Codex App Server turn for it.
2. Codex App Server loads `assistant:mcp`, which forwards the model's typed
   actions over the USB/ADB-forwarded loopback socket to the Android app.

The Android app remains the authority for Shizuku, app allowlisting, stale
observations, confirmation boundaries, and stop/pause state. The companion
claims a request before starting a turn and releases it if the desktop side
fails, so a temporary disconnect does not silently lose the user's request.

```text
Android app (typed request)
        |
        | adb forward / localhost NDJSON
        v
pnpm assistant:companion
        |
        | initialize -> thread/start -> turn/start
        v
Codex App Server (ChatGPT/Codex login)
        |
        | configured local MCP server
        v
pnpm assistant:mcp (desktop adapter)
        |
        | adb forward tcp:8765 tcp:8765
        v
Phone Control Assistant (NDJSON bridge)
        |
        v
SessionCoordinator -> PolicyEngine -> Shizuku typed argv
```

## Configure Codex

Add an entry to the local Codex config (`%USERPROFILE%\.codex\config.toml`).
Use forward slashes in the Windows paths if desired:

```toml
[mcp_servers.phone_assistant]
command = "C:/Program Files/nodejs/pnpm.cmd"
args = ["assistant:mcp"]
cwd = "C:/Users/USER/Documents/ChatGPT/Project Phone Control"

[mcp_servers.phone_assistant.env]
PHONE_ASSISTANT_BRIDGE_HOST = "127.0.0.1"
PHONE_ASSISTANT_BRIDGE_PORT = "8765"
```

If `pnpm.cmd` is not at that location, use the absolute path returned by
`Get-Command pnpm`. Keeping the command and working directory explicit avoids
depending on the PATH inherited by the Codex process.

Start the phone-side app and forward its loopback port:

```powershell
adb forward tcp:8765 tcp:8765
```

Codex App Server can then discover these tools from the `phone_assistant`
MCP server:

- `phone_assistant_start` — create a user-requested session.
- `phone_assistant_list_allowed_apps` — show the phone-side packages currently
  enabled in the per-app allowlist (all apps start disabled).
- `phone_assistant_observe` — return a fresh observation ID and PNG screenshot.
- `phone_assistant_execute` — execute one typed action and return a fresh PNG
  observation after it.
- `phone_assistant_status` — read the phone session state/current purpose.
- `phone_assistant_pending_request` — inspect whether a phone request is
  waiting for the companion (read-only; the companion performs the claim).
- `phone_assistant_stop` — cancel the active session.

This uses the stable configured-MCP path. Codex App Server's dynamic tool
registration is experimental and is not needed for this adapter.

## Action coverage

The bridge preserves the pre-pivot MCP primitives in phone-owned form:

| MCP-stage interaction | Phone action | Notes |
| --- | --- | --- |
| Open app | `open_app` | Launch component is resolved by Android; allowlist is enforced on the phone. |
| Coordinate click | `tap` / `click_coordinate` | Requires the exact observation ID and bounds check. |
| Directional scroll | `scroll` | Android derives a bounded swipe from direction + amount. |
| Explicit gesture | `swipe` | Start/end coordinates and duration are bounds checked. |
| Type text | `type` | Uses Android `input text`; text is never copied into the activity log. |
| Keypress | `keypress` | Supports `BACK`, `HOME`, `ENTER`, and `DELETE`. |
| Back shortcut | `back` | Equivalent to `keypress` with `BACK`. |
| Wait | `wait` | Bounded to 30 seconds; the bridge still returns a fresh observation. |

Each action includes `metadata.purpose`, for example `Searching for jollof
rice` or `Selecting the delivery address`. That purpose is safe to show in the
foreground notification and the in-app activity timeline. Sensitive text and
provider payloads are not written to that timeline.

The old semantic `elementRef` click/scroll path is intentionally not claimed by
this phone-local visual bridge yet. The Android observer currently returns a
screen PNG and foreground binding, not a display-scoped UI Automator tree. A
future accessibility adapter can add semantic targets without weakening the
typed action boundary.

## Phone-first run

Install the sideloaded Android debug build, enable the apps you want in its
allowlist, and forward the phone bridge:

```powershell
adb forward tcp:8765 tcp:8765
```

Start the companion in a second terminal:

```powershell
pnpm assistant:companion
```

Now type a request in the Android app and press Run. The companion claims that
request, starts a Codex App Server turn, and the model calls the MCP tools. The
phone stays in Watch mode: the target app opens and receives visible taps,
typing, swipes, keypresses, and waits. The foreground notification and the
in-app timeline show each action's `metadata.purpose`, such as “Searching for
jollof rice”. When the turn finishes, the companion marks the phone session
completed. Set `PHONE_ASSISTANT_CODEX_MODEL` if you want to pin a model; when
unset, App Server uses the Codex CLI's configured default. The companion runs
`codex app-server --listen stdio://`; set `PHONE_ASSISTANT_CODEX_BIN` when the
Codex executable is not available through the desktop PATH.

This route uses the Codex CLI/App Server's existing ChatGPT-managed login and
subscription. It does not copy cookies, call private ChatGPT endpoints, or
assume native Codex execution on Android. See the official
[Codex App Server documentation](https://learn.chatgpt.com/docs/app-server) for
the JSON-RPC lifecycle used by the companion.

## Run a local smoke check

Build the desktop adapter, then ask the MCP client to call
`phone_assistant_status` or run it directly to verify that it starts and waits
for MCP input:

```powershell
pnpm build
pnpm assistant:mcp
```

The process communicates on stdio; its phone connection is opened only when a
tool is called. Do not expose port `8765` directly on the LAN.

For a deterministic bridge-only check, the older development stub remains
available:

```powershell
pnpm bridge:demo -- --package com.phonecontrol.coordinatebenchmark --x 500 --y 900
```
