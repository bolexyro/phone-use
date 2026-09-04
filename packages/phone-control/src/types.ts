import type {
  ErrorDetails,
  MachineError,
  PhoneControlErrorCode
} from "./errors.js";

export interface PolicyProfile {
  profile: string;
  allowedApps: readonly string[];
  /** When true, every non-empty Android package is permitted. */
  allowAllApps?: boolean;
}

export type DeviceState =
  | "device"
  | "offline"
  | "unauthorized"
  | "no permissions"
  | "unknown";

export interface DeviceInfo {
  serial: string;
  state: DeviceState;
  authorized: boolean;
}

export interface Bounds {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface UiElementStates {
  [state: string]: boolean;
}

export interface UiElement {
  elementRef: string;
  text: string;
  contentDescription: string;
  resourceId: string;
  class: string;
  states: UiElementStates;
  bounds: Bounds | null;
  /** Index of this node's parent in the flattened capture, when known. */
  parentIndex?: number | null;
}

export interface DisplayInfo {
  width: number;
  height: number;
  displayId?: number;
}

export interface DisplaySnapshot {
  display: DisplayInfo;
  rotation: number;
  displayId?: number;
}

export interface ScreenshotDimensions {
  width: number;
  height: number;
}

/** The source of semantic metadata associated with an observation. */
export type ObservationMode = "semantic" | "visual";

export interface ForegroundState {
  packageName: string | null;
  activity: string | null;
  displayId?: number;
}

export interface ObservationBinding {
  serial: string;
  displayId?: number;
  /** Defaults to semantic for observations created by older callers. */
  mode?: ObservationMode;
  packageName: string | null;
  activity: string | null;
  display: DisplayInfo;
  rotation: number;
  /** Absent when this is an explicit screenshot-only observation. */
  uiHash?: string;
  /** SHA-256 of the exact PNG bytes used for this observation. */
  screenshotHash: string;
  screenshotDimensions: ScreenshotDimensions;
  observedAt: number;
}

export interface Observation {
  observationId: string;
  binding: ObservationBinding;
  elements: readonly UiElement[];
  screenshot: Uint8Array;
}

export interface ObservationCapture {
  serial: string;
  displayId?: number;
  /** Defaults to semantic for captures created by older callers. */
  mode?: ObservationMode;
  packageName: string | null;
  activity: string | null;
  display: DisplayInfo;
  rotation: number;
  /** Absent when this is an explicit screenshot-only observation. */
  uiHash?: string;
  /** Filled by the capture path; the store computes it for legacy callers. */
  screenshotHash?: string;
  screenshotDimensions: ScreenshotDimensions;
  observedAt: number;
  elements: readonly UiElement[];
  screenshot: Uint8Array;
}

export type ScrollDirection = "up" | "down" | "left" | "right";

export type ScrollAmount = "small" | "medium" | "large";

export type Keypress = "BACK" | "HOME" | "ENTER" | "DELETE";

export type PhoneAction =
  | { type: "click"; elementRef: string }
  | { type: "click_coordinate"; x: number; y: number }
  | {
      type: "scroll";
      direction: ScrollDirection;
      amount: ScrollAmount;
      elementRef?: string;
    }
  | { type: "type"; text: string }
  | { type: "keypress"; key: Keypress };

/**
 * A semantic selector used by bounded sequences. Element refs are intentionally
 * opaque and are regenerated for every observation, so a sequence can also
 * select a control by stable UI metadata when a previous action reveals a new
 * screen. Every supplied field must match exactly; the server requires a
 * unique actionable match before sending input.
 */
export interface UiElementTarget {
  text?: string;
  contentDescription?: string;
  resourceId?: string;
  class?: string;
}

export type PhoneSequenceAction =
  | { type: "click"; elementRef: string }
  | { type: "click"; target: UiElementTarget }
  | {
      type: "scroll";
      direction: ScrollDirection;
      amount: ScrollAmount;
      elementRef?: string;
    }
  | {
      type: "scroll";
      direction: ScrollDirection;
      amount: ScrollAmount;
      target: UiElementTarget;
    }
  | { type: "type"; text: string }
  | { type: "keypress"; key: Keypress };

export type SequenceExecutionMode = "validated" | "stable_surface";

/** Maximum number of server-validated actions in one MCP call. */
export const MAX_SEQUENCE_ACTIONS = 32;

export interface PhoneExecuteRequest {
  observationId: string;
  action: PhoneAction;
}

export interface PhoneExecuteSequenceRequest {
  observationId: string;
  actions: readonly PhoneSequenceAction[];
  executionMode?: SequenceExecutionMode;
}

export interface ClickPointerEvent {
  type: "pointer";
  action: "click";
  x: number;
  y: number;
  coordinateSpace: "display";
  observationId: string;
  serial: string;
  displayId?: number;
  mode: ObservationMode;
  packageName: string | null;
  activity: string | null;
  rotation: number;
  displayWidth: number;
  displayHeight: number;
  screenshotDimensions: ScreenshotDimensions;
  screenshotHash: string;
  timestamp: number;
}

export interface ScrollPointerEvent {
  type: "pointer";
  action: "scroll";
  direction: ScrollDirection;
  amount: ScrollAmount;
  elementRef?: string;
  startX: number;
  startY: number;
  endX: number;
  endY: number;
  durationMs: number;
  coordinateSpace: "display";
  observationId: string;
  serial: string;
  displayId?: number;
  mode: ObservationMode;
  packageName: string | null;
  activity: string | null;
  rotation: number;
  displayWidth: number;
  displayHeight: number;
  screenshotDimensions: ScreenshotDimensions;
  screenshotHash: string;
  timestamp: number;
}

export type PointerEvent = ClickPointerEvent | ScrollPointerEvent;

export type WaitCondition =
  | { type: "foreground_package"; packageName: string }
  | { type: "visible_text"; text: string }
  | { type: "visible_resource_id"; resourceId: string }
  | { type: "ui_tree_changed" };

export interface WaitOptions {
  timeoutMs?: number;
  pollIntervalMs?: number;
}

export interface ObservationSummary {
  observationId: string;
  serial: string;
  displayId?: number;
  mode: ObservationMode;
  packageName: string | null;
  activity: string | null;
  display: DisplayInfo;
  rotation: number;
  uiHash?: string;
  screenshotHash: string;
  screenshot: {
    mimeType: "image/png";
    width: number;
    height: number;
  };
  elements: readonly UiElement[];
  observedAt: number;
}

export interface VirtualDisplaySession {
  displayId: number;
  packageName: string;
  activity: string | null;
  width: number;
  height: number;
  startedAt: number;
}

export interface CloseAppData {
  closed: boolean;
  packageName?: string;
  displayId?: number;
  message: string;
}

export interface OpenAppOptions {
  useVirtualDisplay?: boolean;
  newInstance?: boolean;
  mode?: ObservationMode;
}

export interface PhoneStatusData {
  device: DeviceInfo;
  foreground: ForegroundState;
  foregroundAllowed: boolean;
}

export interface AllowedAppsData {
  profile: string;
  allowedApps: readonly string[];
  allowAllApps: boolean;
}

export interface ActiveAppsData {
  activeSessions: readonly VirtualDisplaySession[];
  count: number;
}

export interface ActionData {
  action: PhoneAction["type"];
  textLength?: number;
  pointerEvent?: PointerEvent;
  timing?: ActionTiming;
  observation: ObservationSummary;
}

export interface ActionTiming {
  mode: ObservationMode;
  preflightMs: number;
  dispatchMs: number;
  observationMs: number;
  totalMs: number;
}

export interface SequenceStepSuccess {
  index: number;
  status: "success";
  action: PhoneSequenceAction["type"];
  observation: ObservationSummary;
  textLength?: number;
  pointerEvent?: PointerEvent;
}

export interface SequenceStepFailure {
  index: number;
  status: "failed";
  action: PhoneSequenceAction["type"];
  /** A timeout means the transport outcome is unknown, not that no input ran. */
  outcome?: "failed" | "unknown";
  error: {
    code: PhoneControlErrorCode;
    message: string;
    details: ErrorDetails;
  };
}

export type SequenceStepOutcome = SequenceStepSuccess | SequenceStepFailure;

export interface SequenceStepTiming {
  index: number;
  preflightMs: number;
  dispatchMs: number;
  observationMs: number;
  totalMs: number;
}

export interface SequenceTiming {
  mode: SequenceExecutionMode;
  initialCaptureMs: number;
  finalCaptureMs: number;
  totalMs: number;
  steps: readonly SequenceStepTiming[];
}

export interface SequenceData {
  executionMode: SequenceExecutionMode;
  completed: boolean;
  requestedSteps: number;
  completedSteps: number;
  steps: readonly SequenceStepOutcome[];
  finalObservation: ObservationSummary;
  timing: SequenceTiming;
}

export interface ToolSuccessResult<TData extends object = object> {
  ok: true;
  data: TData;
}

export type ToolErrorResult = MachineError;

export type ToolResult<TData extends object = object> =
  | ToolSuccessResult<TData>
  | ToolErrorResult;

export interface ErrorCodeReference {
  code: PhoneControlErrorCode;
}
