# Codex App Server + Phone Assistant

The desktop side of the pivot has two small local processes:

1. `assistant:companion` watches the phone for a request typed in the Android
   app and starts one Codex App Server turn for it.
2. `assistant:mcp` is the reusable MCP adapter for manual Codex/MCP clients.
   The companion uses the same phone-tool dispatcher directly, so a normal
   companion turn does not depend on a second MCP stdio process.

The Android app remains the authority for Shizuku, app allowlisting,
confirmation boundaries, and stop/pause state. The companion
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
- `phone_assistant_observe` — return the current screenshot and foreground
  context for the next action.
- `phone_assistant_execute` — execute one typed action and return a post-action
  PNG screenshot. A changed screenshot does not by itself reject the action.
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
| Coordinate click | `tap` / `click_coordinate` | Coordinates are checked against the current screenshot bounds; screenshot freshness checks are deferred. |
| Directional scroll | `scroll` | Android derives a bounded swipe from direction + amount. |
| Explicit gesture | `swipe` | Start/end coordinates and duration are bounds checked. |
| Type text | `type` | Uses Android `input text`; text is never copied into the activity log. |
| Keypress | `keypress` | Supports `BACK`, `HOME`, `ENTER`, and `DELETE`. |
| Back shortcut | `back` | Equivalent to `keypress` with `BACK`. |
| Wait | `wait` | Bounded to 30 seconds; the bridge still returns a post-action screenshot when available. |

Each action includes `metadata.purpose`, for example `Searching for jollof
rice` or `Selecting the delivery address`. That purpose is safe to show in the
foreground notification and the in-app conversation timeline. The companion
also forwards the final user-facing Codex message as `feedback` on
`complete_session`; the phone renders it as an assistant message and sends a
separate completion notification. Sensitive action payloads and private
reasoning are not written to the timeline.

Agent messages are kept separate by their App Server `itemId`. Commentary
items remain progress output; only the latest completed `agentMessage` with
`phase: "final_answer"` is forwarded as phone feedback, so an opening note is
not accidentally prefixed to the final result.

The companion treats `turn/completed` as the App Server transport reaching its
terminal state, not as proof that the user's phone task succeeded. The injected
prompt explicitly requires the model to continue past progress states such as
opening an app or reaching a setup screen, and to use fresh observations to
verify every requested step before giving its final message. A terminal-turn
log line therefore means that Codex stopped producing work; the following
`complete_session` call is the point at which the phone timeline is closed.

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
in-app timeline show a compact, independently scrollable stack of each action's
short label, such as “Searching for jollof rice”. Expanding an item reveals its
target and full safe explanation. When the turn finishes, the companion marks
the phone session completed. DHD pins its own App Server turns to `gpt-5.6-luna` with `low`
reasoning by default, independently of the interactive Codex chat's settings.
Set `PHONE_ASSISTANT_CODEX_MODEL` or `PHONE_ASSISTANT_CODEX_REASONING_EFFORT`
only when intentionally overriding that development default. The companion runs
`codex app-server --listen stdio:// --enable code_mode_host` so the App Server's
dynamic-tool router can deliver calls to the direct `phone_control_*` tools.
Set `PHONE_ASSISTANT_CODEX_BIN` when the Codex executable is not available
through the desktop PATH. Set `PHONE_ASSISTANT_ENABLE_CODE_MODE_HOST=false`
only when intentionally testing a configuration without the bundled host; phone
turns will not be able to execute dynamic actions in that mode.
Phone turns have a bounded ten-minute watchdog by default because a request may
need several observe/action cycles. Override it for a development run with
`PHONE_ASSISTANT_TURN_TIMEOUT_MS` (5 seconds to 1 hour); when it expires, the
companion sends `turn/interrupt` with the active thread and turn ids and reports
the last App Server lifecycle event it saw.

While a turn is running, the Android composer changes to **Steer DHD**. Sending
text there appends one instruction to the same in-flight App Server turn; it does
not create a new user-facing conversation or restart the phone session. The
companion claims queued instructions from the phone, sends `turn/steer` with the
active `threadId`, `expectedTurnId`, and text input, then acknowledges delivery.
The steer appears in the local timeline beside the run it modified. Stop remains
the urgent control: it ends the phone session and the companion propagates a
`turn/interrupt` to Codex. A tap or swipe already in progress may finish before
the interrupt is observed, so use Stop when the phone needs immediate attention.

DHD presents one assistant timeline rather than user-facing chat threads. The
phone stores requests and tool activity locally and only renders the most recent
24 hours by default. The companion reuses the stored Codex thread for requests
within three hours of the last local activity. At or after three hours idle,
the phone removes that stored remote thread id before handoff, so the next
request starts a new Codex App Server thread without deleting the local history.

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
