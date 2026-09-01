# Codex App Server + Phone Assistant

The desktop side of the pivot has two small local processes:

1. `companion:worker` watches the phone for a request typed in the Android
   app and drives turns through one prewarmed Codex App Server process.
2. `companion:tools` is the reusable DHD tool adapter for manual Codex/MCP clients.
   The companion uses the same phone-tool dispatcher directly, so a normal
   companion turn does not depend on a second MCP stdio process.

The Android app remains the authority for Shizuku, app allowlisting,
confirmation boundaries, and stop/pause state. The companion
claims a request before starting a turn and releases it if the desktop side
fails, so a temporary disconnect does not silently lose the user's request.

```text
Android app (typed request)
        |
        | Wi-Fi TCP/NDJSON + pairing token
        v
pnpm companion:worker
        |
        | prewarm -> initialize once -> thread/start or thread/resume -> turn/start (x N)
        v
Codex App Server (one persistent process; ChatGPT/Codex login)
        |
        | direct dynamic dhd_* tools
        v
phone-tool dispatcher (shared with companion:tools)
        |
        | same authenticated phone link
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
[mcp_servers.dhd]
command = "C:/Program Files/nodejs/pnpm.cmd"
args = ["companion:tools"]
cwd = "C:/Users/USER/Documents/ChatGPT/Project Phone Control"

[mcp_servers.dhd.env]
PHONE_ASSISTANT_BRIDGE_HOST = "192.168.1.42"
PHONE_ASSISTANT_BRIDGE_PORT = "8765"
PHONE_ASSISTANT_BRIDGE_TOKEN = "copy-the-token-from-dhd-settings"
```

If `pnpm.cmd` is not at that location, use the absolute path returned by
`Get-Command pnpm`. Keeping the command and working directory explicit avoids
depending on the PATH inherited by the Codex process.

For the wireless path, open DHD Settings → Companion connection and copy one
of the shown phone addresses and the pairing token. Put them in the companion
shell before starting it. The phone and laptop must be on the same reachable
Wi-Fi network:

```powershell
$env:PHONE_ASSISTANT_BRIDGE_HOST = "192.168.1.42"
$env:PHONE_ASSISTANT_BRIDGE_PORT = "8765"
$env:PHONE_ASSISTANT_BRIDGE_TOKEN = "copy-the-token-from-dhd-settings"
pnpm companion:worker
```

For a desktop dashboard around the same worker, run this from the repository
root:

```powershell
pnpm companion:dashboard
```

The Electron companion lets you save the bridge host, port, and token, check
the phone link, start or stop the existing Codex companion worker, and inspect
its local activity timeline. It does not replace the phone-owned policy or
action layer. The command uses the repository's installed Electron dependency
and does not require Docker or a new dependency download.

The token is required for non-loopback connections. It is a bearer token for
this development bridge, so use it only on a trusted local network and do not
forward port `8765` from the router to the internet.

If a local USB/ADB fallback is useful, start the phone-side app and forward
its loopback port instead:

```powershell
adb forward tcp:8765 tcp:8765
```

Codex App Server can then discover this five-tool DHD surface from the `dhd`
MCP server:

- `dhd_list_allowed_apps` — show the phone-side packages currently enabled in
  the per-app allowlist (all apps start disabled).
- `dhd_observe` — return the current screenshot and foreground context for the
  next action.
- `dhd_open_app` — open one allowlisted app and return a post-action PNG
  screenshot.
- `dhd_execute` — execute one typed interaction and return a post-action PNG
  screenshot. It handles tap, type, swipe, scroll, back, keypress, and wait.
- `dhd_request_attention` — post an attention notification without opening the
  assistant Activity.

The phone companion registers the same `dhd_*` dynamic tools on each App
Server thread and maps them to the same dispatcher. Session lifecycle
operations remain internal to the companion and phone bridge. In current Codex
App Server builds these calls are delivered through the bundled Code Mode
host, which the companion enables by default.
Setting `PHONE_ASSISTANT_ENABLE_CODE_MODE_HOST=false` intentionally disables
that route and therefore prevents dynamic phone actions from executing.

## Persistent App Server and isolated context

The companion creates one `CodexAppServerClient`, prewarms it (including the
`initialize` handshake) before it begins polling, and closes it only when the
companion exits. If startup fails, it retries once and keeps polling; the next
phone request retries startup again. When the DHD Activity becomes visible, it
sets a one-shot warmup bit in the phone bridge. The next poll consumes that bit
and warms the same connection in the background, so opening DHD can hide a
desktop companion restart or crash recovery. Sending a request while warmup is
in progress simply awaits the same idempotent startup operation.

The first request after each companion process starts creates a fresh DHD thread
even when the phone supplies a stored thread id. This establishes the current
DHD tool contract instead of reviving a thread created by an older companion
version. Successful later requests reuse that newly established loaded thread;
`thread/resume` is used only for a current-contract thread that is not loaded
in the active connection. At a three-hour DHD inactivity rotation, the
companion sends `thread/unsubscribe` for the superseded loaded thread before
starting the replacement, preventing old subscriptions from accumulating.

The App Server child starts in a dedicated user runtime directory rather than
the Phone Control repository: `%USERPROFILE%\\.dhd\\codex-runtime` by default.
Override it with `PHONE_ASSISTANT_CODEX_CWD` when a different empty directory is
needed. It also uses a dedicated Codex home at
`%USERPROFILE%\\.dhd\\codex-home` by default; override it with
`PHONE_ASSISTANT_CODEX_HOME` when a different authenticated home is needed.
Authenticate that home once with `CODEX_HOME` pointing to it before starting
the companion. The companion passes `CODEX_HOME` only to the App Server child,
so it does not change the desktop Codex process or the parent environment.
The companion passes minimal App Server config overrides that disable configured
MCP servers, shell execution, apps, browser use, computer use, memories,
multi-agent tools, plugins, remote plugins, skill search, unified exec, hooks,
and dependency installation. Project instructions are not capped: the runtime
can provide its own small `AGENTS.md` for DHD-specific guidance, without
inheriting the Phone Control repository's project instructions.
It also turns off goals, shell snapshots, image generation, the in-app browser,
tool suggestions, image viewing, and workspace dependencies for this child.
ChatGPT authentication is stored in DHD's dedicated Codex home; the isolated
home and working directory keep DHD from inheriting the coding workspace's
global configuration, project context, and tool catalog. Code Mode host remains
enabled because it is the transport that delivers the direct `dhd_*` calls.
Because table-valued CLI overrides can merge with a user's config, the
companion also reads only the names of MCP sections configured in the selected
DHD Codex home and adds an `enabled=false` override for each one. It never
reads or logs their credentials, commands, URLs, or headers.

As a smoke measurement on 31 August 2026, a fresh isolated diagnostic rollout
contained a 41,285-character `world_state` payload. The comparable pre-change
DHD rollout contained 48,316 characters, a reduction of about 14.5%. The
remaining state is primarily Codex permission metadata and host-skill metadata;
the feature overrides are a boundary and latency reduction, not a claim that
the App Server has no global user metadata at all.

Lifecycle timing is written to stderr as `[dhd-timing]` records with
`tsMs` (wall-clock milliseconds) and `elapsedMs`. The records cover
`claim:start/complete`, `spawn:start/complete`,
`initialize:start/complete`, `thread/start`, `resume`, `turn/start`,
`thread/unsubscribe`, `turn/started`, `userMessage`, `turn/completed`, and
timeout/error paths. Idle `poll:start/complete` timing is suppressed by
default; set `PHONE_ASSISTANT_DEBUG_TIMING=true` when diagnosing poll latency.
They contain ids and phase metadata only, not request text, tool arguments,
screenshots, or model output.

## Action coverage

The bridge preserves the pre-pivot MCP primitives in phone-owned form:

| MCP-stage interaction | Phone action | Notes |
| --- | --- | --- |
| Open app | `dhd_open_app` / `open_app` internally | Launch component is resolved by Android; allowlist is enforced on the phone. |
| Coordinate tap | `tap` | Coordinates are checked against the current screenshot bounds; screenshot freshness checks are deferred. |
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
separate completion notification. That notification shows the first useful
sentence of the final answer as its preview, while tapping it opens the full
conversation. If no final message payload is available, it shows `Your DHD
task is ready to review.` Sensitive action payloads and private
reasoning are not written to the timeline.

Agent messages are kept separate by their App Server `itemId`. Commentary
items remain progress output; only the latest completed `agentMessage` with
`phase: "final_answer"` is forwarded as phone feedback, so an opening note is
not accidentally prefixed to the final result.

The companion treats `turn/completed` as the App Server transport reaching its
terminal state, not as independent proof that the user's phone task succeeded.
The request itself is passed to App Server unchanged; persistent DHD behavior
belongs in the runtime `AGENTS.md`, while phone capabilities and argument
constraints belong in the direct tool contracts. A terminal-turn log line means
that Codex stopped producing work; the following `complete_session` call is the
point at which the phone timeline is closed.

When the model needs a human to look at the phone, it can call
`dhd_request_attention` with a short reason. The phone posts a
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
pnpm companion:worker
```

Now type a request in the Android app and press Run. The companion has already
prewarmed the shared Codex App Server connection (or will retry if startup was
temporarily unavailable), claims the request, and the model calls the phone
tools. Later requests on the same companion reuse the initialized connection
and loaded DHD thread. Opening DHD also sends the best-effort warmup signal
described above.
The phone stays in Watch mode: the target app opens and receives visible taps,
typing, swipes, keypresses, and waits. The foreground notification and the
in-app timeline show a compact, independently scrollable stack of each action's
short label, such as “Searching for jollof rice”. Expanding an item reveals its
target and full safe explanation. When the turn finishes, the companion marks
the phone session completed. DHD pins its own App Server turns to `gpt-5.6-luna` with `max`
reasoning by default, independently of the interactive Codex chat's settings.
Set `PHONE_ASSISTANT_CODEX_MODEL` or `PHONE_ASSISTANT_CODEX_REASONING_EFFORT`
only when intentionally overriding that development default. The companion runs
`codex app-server --listen stdio:// --enable code_mode_host` so the App Server's
dynamic-tool router can deliver calls to the direct `dhd_*` tools.
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

This route uses the Codex CLI/App Server's ChatGPT-managed login stored in the
DHD Codex home and the user's subscription. It does not copy cookies, call
private ChatGPT endpoints, or assume native Codex execution on Android. See the official
[Codex App Server documentation](https://learn.chatgpt.com/docs/app-server) for
the JSON-RPC lifecycle used by the companion.

## Run a local smoke check

Build the desktop adapter, then ask the MCP client to call
`dhd_list_allowed_apps` or run it directly to verify that it starts and waits
for MCP input:

```powershell
pnpm build
pnpm companion:tools
```

The process communicates on stdio; its phone connection is opened only when a
tool is called. For wireless use, set the same host/token environment variables
shown above. The bridge is intended for a trusted local network and is not a
TLS or internet-facing production protocol.

For a deterministic bridge-only check, the older development stub remains
available:

```powershell
pnpm companion:bridge-demo -- --host 192.168.1.42 --token copy-the-token-from-dhd-settings --package com.phonecontrol.coordinatebenchmark --x 500 --y 900
```
