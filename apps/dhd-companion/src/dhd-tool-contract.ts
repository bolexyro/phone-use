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
    "Reports the phone's current app-access mode. In restricted mode, the response includes the explicitly allowed package names. With Full Access, the default response confirms that any launchable app may be used without enumerating every app. Set includeAll=true only when the complete launchable-app list is needed.",
  dhd_browse_app:
    "Searches the phone's launchable app catalog by app name or package name and returns matching app labels and package names. Use this to identify a specific package for dhd_open_app. In restricted mode, results are limited to the explicit allowlist. This tool does not launch or interact with an app.",
  dhd_get_foreground_app:
    "Reports the Android package, activity, and display context currently in the foreground. This tool is read-only: it does not capture a screenshot, create an observation ID, or authorize an action. Use dhd_observe before sending phone input; dhd_open_app establishes its own launch baseline.",
  dhd_observe:
    `Captures the current physical phone display and returns its screenshot, foreground context, display details, and a new observation ID. Use this when no usable observation is available, after an observation-related failure, or when the screen may have changed independently. Do not call it repeatedly for an unchanged screen or immediately after a successful dhd_open_app, dhd_execute, or dhd_execute_sequence; those tools already return a fresh observation. ${SCREENSHOT_MARKER_GUIDANCE}`,
  dhd_open_app:
    `Launches one Android app without requiring a caller-supplied observation ID; the phone establishes the launch baseline internally. On success, it returns the resulting screenshot and a fresh observation ID. In restricted mode, the requested package must be explicitly allowed; Full Access permits any launchable app. Inspect and reuse the returned observation for the next action unless the screen may have changed after the returned capture. ${SCREENSHOT_MARKER_GUIDANCE}`,
  dhd_execute:
    `Executes one typed phone interaction against the screen identified by metadata.observationId. Supported actions are tap, type, swipe, scroll, back, keypress, and wait. On success, the response includes the resulting screenshot and a fresh observation ID; inspect and reuse that observation for the next action. If the pre-action state is stale, no input is sent. If the post-action observation fails, the outcome is unknown; call dhd_observe before deciding whether to retry or continue. Raw shell commands are not supported. ${STALE_OBSERVATION_GUIDANCE} ${SCREENSHOT_MARKER_GUIDANCE}`,
  dhd_execute_sequence: `Executes up to ${DHD_MAX_SEQUENCE_ACTIONS} typed phone interactions serially from one initial observationId. Each step uses the verified post-action observation from the previous step, so sequence steps must not include observationId. Supported actions are tap, type, swipe, scroll, back, keypress, and wait; open_app, shell commands, semantic targets, and execution modes are not supported. Use this only when every later target is predictable without inspecting intermediate screenshots; use dhd_execute for adaptive or branching work. The phone captures and verifies the screen after every successful step, and the response includes the final screenshot and observation only when the full sequence succeeds. The sequence stops at the first failure. A post-action observation failure means the outcome is unknown; call dhd_observe before deciding whether to retry or continue. ${STALE_OBSERVATION_GUIDANCE} ${SCREENSHOT_MARKER_GUIDANCE}`,
  dhd_request_attention:
    "Sends a notification asking the user to review or take over the phone without opening DHD. Use this when user attention is required, and do not continue phone actions afterward.",
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
