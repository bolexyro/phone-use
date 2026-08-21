import type { MachineError, PhoneControlErrorCode } from "./errors.js";

export interface PolicyProfile {
  profile: string;
  allowedApps: readonly string[];
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
}

export interface DisplayInfo {
  width: number;
  height: number;
}

export interface DisplaySnapshot {
  display: DisplayInfo;
  rotation: number;
}

export interface ScreenshotDimensions {
  width: number;
  height: number;
}

export interface ForegroundState {
  packageName: string | null;
  activity: string | null;
}

export interface ObservationBinding {
  serial: string;
  packageName: string | null;
  activity: string | null;
  display: DisplayInfo;
  rotation: number;
  uiHash: string;
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
  packageName: string | null;
  activity: string | null;
  display: DisplayInfo;
  rotation: number;
  uiHash: string;
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
  | { type: "scroll"; direction: ScrollDirection; amount: ScrollAmount }
  | { type: "type"; text: string }
  | { type: "keypress"; key: Keypress };

export interface PhoneExecuteRequest {
  observationId: string;
  action: PhoneAction;
}

export interface ClickPointerEvent {
  type: "pointer";
  action: "click";
  x: number;
  y: number;
  coordinateSpace: "display";
  observationId: string;
  serial: string;
  packageName: string | null;
  displayWidth: number;
  displayHeight: number;
  timestamp: number;
}

export interface ScrollPointerEvent {
  type: "pointer";
  action: "scroll";
  direction: ScrollDirection;
  amount: ScrollAmount;
  startX: number;
  startY: number;
  endX: number;
  endY: number;
  durationMs: number;
  coordinateSpace: "display";
  observationId: string;
  serial: string;
  packageName: string | null;
  displayWidth: number;
  displayHeight: number;
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
  packageName: string | null;
  activity: string | null;
  display: DisplayInfo;
  rotation: number;
  uiHash: string;
  screenshot: {
    mimeType: "image/png";
    width: number;
    height: number;
  };
  elements: readonly UiElement[];
  observedAt: number;
}

export interface PhoneStatusData {
  profile: string;
  allowedApps: readonly string[];
  device: DeviceInfo;
  foreground: ForegroundState;
  foregroundAllowed: boolean;
}

export interface AllowedAppsData {
  profile: string;
  allowedApps: readonly string[];
}

export interface ActionData {
  action: PhoneAction["type"];
  textLength?: number;
  pointerEvent?: PointerEvent;
  observation: ObservationSummary;
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
