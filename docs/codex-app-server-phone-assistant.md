# Codex App Server + Phone Assistant

The desktop side of the pivot has two small local processes:

1. `assistant:companion` watches the phone for a request typed in the Android
   app and starts one Codex App Server turn for it.
2. `assistant:mcp` is the reusable MCP adapter for manual Codex/MCP clients.
   The companion uses the same phone-tool dispatcher directly, so a normal
   companion turn does not depend on a second MCP stdio process.

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
        | direct dynamic phone_control_* tools
        v
phone-tool dispatcher (shared with assistant:mcp)
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
- `phone_assistant_request_attention` — post an attention notification without
  opening the assistant Activity.
- `phone_assistant_status` — read the phone session state/current purpose.
- `phone_assistant_pending_request` — inspect whether a phone request is
  waiting for the companion (read-only; the companion performs the claim).
- `phone_assistant_stop` — cancel the active session.

The configured MCP server remains available for direct MCP clients and for
backwards-compatible/manual use. The phone companion additionally registers
`phone_control_*` dynamic tools on each App Server thread and maps them to the
same dispatcher. In current Codex App Server builds those calls are delivered
through the bundled Code Mode host, which the companion enables by default.
Setting `PHONE_ASSISTANT_ENABLE_CODE_MODE_HOST=false` intentionally disables
that route and therefore prevents dynamic phone actions from executing.

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
foreground notification and the in-app conversation timeline. The companion
also forwards the final user-facing Codex message as `feedback` on
`complete_session`; the phone renders it as an assistant message and sends a
separate completion notification. Sensitive action payloads and private
reasoning are not written to the timeline.

When the model needs a human to look at the phone, it can call
`phone_control_request_attention` with a short reason. The phone posts a
high-priority notification and updates the conversation, but does not force the
assistant Activity over whatever app is visible in Watch mode.

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
request, starts a Codex App Server turn, and the model calls the phone tools.
The phone stays in Watch mode: the target app opens and receives visible taps,
typing, swipes, keypresses, and waits. The foreground notification and the
in-app timeline show each action's `metadata.purpose`, such as “Searching for
jollof rice”. When the turn finishes, the companion marks the phone session
completed. Set `PHONE_ASSISTANT_CODEX_MODEL` if you want to pin a model; when
unset, App Server uses the Codex CLI's configured default. The companion runs
`codex app-server --listen stdio:// --enable code_mode_host` so the App Server's
dynamic-tool router can deliver calls to the direct `phone_control_*` tools.
Set `PHONE_ASSISTANT_CODEX_BIN` when the Codex executable is not available
through the desktop PATH. Set `PHONE_ASSISTANT_ENABLE_CODE_MODE_HOST=false`
only when intentionally testing a configuration without the bundled host; phone
turns will not be able to execute dynamic actions in that mode.

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
