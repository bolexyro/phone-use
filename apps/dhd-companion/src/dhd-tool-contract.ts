import {
  SCREENSHOT_MARKER_GUIDANCE,
  STALE_OBSERVATION_GUIDANCE
} from "@dhd/screenshot-markers";

export const GUARD_REGIONS_FEATURE_FLAG = "PHONE_ASSISTANT_ENABLE_GUARD_REGIONS";

export const DHD_MAX_SEQUENCE_ACTIONS = 16;
export const DHD_MAX_TEXT_CHARS = 240;
export const DHD_MAX_GUARD_REGIONS = 8;
export const DHD_MAX_TYPE_TEXT_CHARS = 4096;
export const DHD_MAX_SWIPE_DURATION_MS = 10_000;
export const DHD_MAX_WAIT_DURATION_MS = 30_000;

export const DHD_ACTION_TYPES = {
  tap: "tap",
  type: "type",
  swipe: "swipe",
  scroll: "scroll",
  back: "back",
  keypress: "keypress",
  wait: "wait",
} as const;

export const DHD_SCROLL_DIRECTIONS = ["up", "down", "left", "right"] as const;
export const DHD_SCROLL_AMOUNTS = ["small", "medium", "large"] as const;
export const DHD_KEYPRESS_KEYS = ["BACK", "HOME", "ENTER", "DELETE"] as const;

const ENABLED_FEATURE_VALUES = new Set(["1", "true", "yes", "on"]);

export function isGuardRegionsEnabled(
  environment: NodeJS.ProcessEnv = process.env,
): boolean {
  return ENABLED_FEATURE_VALUES.has(
    (environment[GUARD_REGIONS_FEATURE_FLAG] ?? "").trim().toLowerCase(),
  );
}

export const DHD_TOOL_NAMES = [
  "dhd_list_allowed_apps",
  "dhd_browse_app",
  "dhd_list_displays",
  "dhd_close_display",
  "dhd_get_foreground_app",
  "dhd_observe",
  "dhd_open_app",
  "dhd_execute",
  "dhd_execute_sequence",
  "dhd_request_attention",
] as const;

export type DhdToolName = (typeof DHD_TOOL_NAMES)[number];

export function isDhdToolName(value: string): value is DhdToolName {
  return (DHD_TOOL_NAMES as readonly string[]).includes(value);
}

const GUARD_REGIONS_GUIDANCE =
  "When guardRegions are available, include them in the corresponding dhd_execute action or sequence step only when that target must remain visually unchanged; the phone compares them with the preceding observation screenshot.";

const DHD_TOOL_DESCRIPTIONS: Record<DhdToolName, string> = {
  dhd_list_allowed_apps:
    "Reports the phone's current app-access mode. In restricted mode, the response includes the explicitly allowed package names. With Full Access, the default response confirms that any launchable app may be used without enumerating every app. Set includeAll=true to list every launchable app available under the current access mode: the complete phone catalog with Full Access, or the complete allowlist in restricted mode.",
  dhd_browse_app:
    "Searches the phone's launchable app catalog by app names or package names and returns each match's app label, package name, and whether DHD can use it. Full Access makes every match usable; restricted mode makes only explicitly allowlisted packages usable. Use this to identify a specific package for dhd_open_app. This tool does not launch or interact with an app.",
  dhd_list_displays:
    "Lists the active and retained DHD virtual displays. Each entry includes a generation-safe displayRef, app label, package, geometry, lifecycle status, current or last purpose, and remaining retention time. Use this when more than one display is available or when a displayRef has expired, ended, or become unavailable.",
  dhd_close_display:
    "Ends one DHD virtual display by its displayRef. Use dhd_list_displays first, then pass the exact reference. Closing an active display is rejected until its active DHD run is stopped; this never targets the physical display 0.",
  dhd_get_foreground_app:
    "Reports the Android package, activity, display context, and screenProtection metadata in a task virtual display. Pass displayRef to choose among multiple displays. If a display is expired, ended, or unavailable, follow the returned recovery message; never fall back to physical display 0. This tool is read-only and does not authorize input.",
  dhd_observe:
    `Captures a selected task virtual display and returns its screenshot, foreground context, display details, screenProtection metadata, and a new observation ID. Pass displayRef when working with more than one display. If the display is expired, ended, unavailable, or another display must be selected, follow the returned message and use dhd_list_displays or dhd_open_app as directed; DHD never falls back to display 0. If screenProtection.requiresUserAttention is true, call dhd_request_attention and wait for the user's Done acknowledgement before sending more input. Use this when no usable observation is available, after an observation-related failure, or when the screen may have changed independently. Do not call it repeatedly for an unchanged screen or immediately after a successful dhd_open_app, dhd_execute, or dhd_execute_sequence; those tools already return a fresh observation. ${SCREENSHOT_MARKER_GUIDANCE}`,
  dhd_open_app:
    `Launches one Android app on a task virtual display, creating that display if needed, without requiring a caller-supplied observation ID. Pass displayRef to reuse one of several retained displays; if a display has expired or the session limit is reached, follow the descriptive recovery message. On success, it returns the resulting screenshot and a fresh observation ID. In restricted mode, the requested package must be explicitly allowed; Full Access permits any launchable app. Inspect and reuse the returned observation for the next action unless the screen may have changed after the returned capture. ${SCREENSHOT_MARKER_GUIDANCE}`,
  dhd_execute:
    `Executes one typed phone interaction against the screen identified by metadata.observationId. Optionally pass displayRef to select a display explicitly; the observation must belong to that same display. Supported actions are tap, type, swipe, scroll, back, keypress, and wait. A scroll may include both x and y task-display coordinates to choose the center of the gesture; omit both to scroll at the display center. On success, the response includes the resulting screenshot and a fresh observation ID; inspect and reuse that observation for the next action. If the display is expired, ended, unavailable, or the pre-action state is stale, no input is sent; follow the recovery message and obtain a fresh observation. If the post-action observation fails, the outcome is unknown; call dhd_observe before deciding whether to retry or continue. Raw shell commands are not supported. ${STALE_OBSERVATION_GUIDANCE} ${SCREENSHOT_MARKER_GUIDANCE}`,
  dhd_execute_sequence: `Executes up to ${DHD_MAX_SEQUENCE_ACTIONS} typed phone interactions serially from one initial observationId. Optionally pass displayRef to select the observation's display explicitly. Each step uses the verified post-action observation from the previous step, so sequence steps must not include observationId. Supported actions are tap, type, swipe, scroll, back, keypress, and wait. A scroll may include both x and y task-display coordinates to choose the center of the gesture; omit both to scroll at the display center. open_app, shell commands, semantic targets, and execution modes are not supported. Use this only when every later target is predictable without inspecting intermediate screenshots; use dhd_execute for adaptive or branching work. The phone captures and verifies the screen after every successful step, and the response includes the final screenshot and observation only when the full sequence succeeds. If the display is expired, ended, unavailable, or a post-action observation fails, follow the returned recovery message and call dhd_observe before deciding whether to retry or continue. ${STALE_OBSERVATION_GUIDANCE} ${SCREENSHOT_MARKER_GUIDANCE}`,
  dhd_request_attention:
    "Blocks the Codex turn until the user reviews the selected task display and taps Done in DHD. Pass displayRef when multiple displays exist. Use this when observation.screenProtection.requiresUserAttention is true, when the task preview is blank because of protected content, or when the app requires a biometric, PIN, passcode, or other user-only step. The task display stays alive while waiting. Never guess or send typed input for biometric/PIN authentication. If the display is unavailable, follow the recovery message. After this tool returns, use its fresh observation when present or call dhd_observe before continuing.",
};

export function dhdToolDescription(
  name: DhdToolName,
  enableGuardRegions = false,
): string {
  const description = DHD_TOOL_DESCRIPTIONS[name];
  if (
    enableGuardRegions &&
    (name === "dhd_execute" ||
      name === "dhd_execute_sequence")
  ) {
    return `${description} ${GUARD_REGIONS_GUIDANCE}`;
  }
  return description;
}
