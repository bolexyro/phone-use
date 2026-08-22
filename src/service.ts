import { AdbProcessAdapter } from "./adb/process-adapter.js";
import type { FixedAdbAdapter } from "./adb/adapter.js";
import { selectDeviceFromEnvironment } from "./adb/device-selection.js";
import { parsePngDimensions } from "./adb/process-parsers.js";
import {
  NdjsonActionLogger,
  sanitizeAction,
  type ActionAuditLogger,
  type AuditLogEntry
} from "./audit-log.js";
import { assertCoordinateInBounds, calculateScrollGesture } from "./coordinates.js";
import {
  asPhoneControlError,
  PhoneControlError
} from "./errors.js";
import { ObservationStore } from "./observation-store.js";
import {
  assertAllowedForeground,
  assertAllowedTarget,
  isAllowedPackage
} from "./policy-guard.js";
import { hashUiTree, parseUiAutomatorXml, boundsCenter } from "./ui-automator.js";
import { loadPolicy } from "./config.js";
import { resolve } from "node:path";
import { VirtualDisplayManager } from "./virtual-display.js";
import type {
  ActionData,
  AllowedAppsData,
  Bounds,
  CloseAppData,
  DeviceInfo,
  ForegroundState,
  Observation,
  ObservationCapture,
  OpenAppOptions,
  PhoneAction,
  PhoneExecuteRequest,
  PhoneStatusData,
  PolicyProfile,
  PointerEvent,
  ToolSuccessResult,
  UiElement,
  WaitCondition,
  WaitOptions
} from "./types.js";

export interface PhoneControlServiceOptions {
  adb: FixedAdbAdapter;
  policy: PolicyProfile;
  environment?: NodeJS.ProcessEnv;
  observationStore?: ObservationStore;
  virtualDisplayManager?: VirtualDisplayManager;
  auditLogger?: ActionAuditLogger;
  now?: () => number;
  sleep?: (milliseconds: number) => Promise<void>;
}

const DEFAULT_WAIT_TIMEOUT_MS = 10_000;
const MAX_WAIT_TIMEOUT_MS = 30_000;
const DEFAULT_POLL_INTERVAL_MS = 250;
const POINTER_START_DELAY_MS = 150;
const DEFAULT_ACTION_LOG_PATH = resolve(
  process.cwd(),
  "logs",
  "phone-control.actions.ndjson"
);

function defaultSleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function isTimeout(error: unknown): boolean {
  return error instanceof PhoneControlError && error.code === "ADB_TIMEOUT";
}

export class PhoneControlService {
  readonly #adb: FixedAdbAdapter;
  readonly #policy: PolicyProfile;
  readonly #environment: NodeJS.ProcessEnv;
  readonly #observations: ObservationStore;
  readonly #virtualDisplays: VirtualDisplayManager;
  readonly #auditLogger?: ActionAuditLogger;
  readonly #now: () => number;
  readonly #sleep: (milliseconds: number) => Promise<void>;

  public constructor(options: PhoneControlServiceOptions) {
    this.#adb = options.adb;
    this.#policy = options.policy;
    this.#environment = options.environment ?? process.env;
    this.#observations = options.observationStore ?? new ObservationStore();
    this.#sleep = options.sleep ?? defaultSleep;
    this.#virtualDisplays =
      options.virtualDisplayManager ??
      new VirtualDisplayManager(this.#adb, {
        environment: this.#environment,
        sleep: this.#sleep
      });
    this.#auditLogger =
      options.auditLogger ?? new NdjsonActionLogger(DEFAULT_ACTION_LOG_PATH);
    this.#now = options.now ?? Date.now;
  }

  public get observationStore(): ObservationStore {
    return this.#observations;
  }

  public get virtualDisplayManager(): VirtualDisplayManager {
    return this.#virtualDisplays;
  }

  #getPolicy(): PolicyProfile {
    if (process.env.NODE_ENV === "test" || this.#environment.NODE_ENV === "test") {
      return this.#policy;
    }
    try {
      return loadPolicy({ env: this.#environment });
    } catch {
      return this.#policy;
    }
  }

  public async status(): Promise<ToolSuccessResult<PhoneStatusData>> {
    const policy = this.#getPolicy();
    const device = await this.#selectedDevice();
    const foreground = await this.#adb.getForeground(device.serial, 0);
    return {
      ok: true,
      data: {
        profile: policy.profile,
        allowedApps: policy.allowedApps,
        device,
        foreground,
        foregroundAllowed: isAllowedPackage(
          policy,
          foreground.packageName
        ),
        virtualDisplays: this.#virtualDisplays.sessions
      }
    };
  }

  public allowedApps(): ToolSuccessResult<AllowedAppsData> {
    const policy = this.#getPolicy();
    return {
      ok: true,
      data: {
        profile: policy.profile,
        allowedApps: policy.allowedApps
      }
    };
  }

  public async openApp(
    packageName: string,
    options: OpenAppOptions = {}
  ): Promise<ToolSuccessResult<{ observation: ReturnType<ObservationStore["summary"]> }>> {
    const policy = this.#getPolicy();
    assertAllowedTarget(policy, packageName);
    const device = await this.#selectedDevice();
    const useVirtualDisplay = options.useVirtualDisplay !== false;

    let targetDisplayId = 0;

    if (useVirtualDisplay) {
      const apiLevel = await this.#adb.getApiLevel(device.serial);
      if (apiLevel < 29) {
        throw new PhoneControlError(
          "VIRTUAL_DISPLAY_UNSUPPORTED",
          `Virtual display creation is unsupported on this device because Android 10+ (API 29+) is required (device is running Android API ${apiLevel}). Please confirm with the user before launching on the primary screen by calling phone_open_app with useVirtualDisplay: false.`,
          { packageName, apiLevel }
        );
      }

      try {
        const session = await this.#virtualDisplays.launch(
          device.serial,
          packageName
        );
        targetDisplayId = session.displayId;
      } catch (error) {
        if (
          error instanceof PhoneControlError &&
          error.code === "VIRTUAL_DISPLAY_FAILED"
        ) {
          throw new PhoneControlError(
            "VIRTUAL_DISPLAY_FAILED",
            `${error.message} Please confirm with the user before launching on the primary screen by calling phone_open_app with useVirtualDisplay: false.`,
            error.details
          );
        }
        throw error;
      }
    } else {
      try {
        await this.#adb.launchApp(device.serial, packageName);
      } catch (error) {
        if (isTimeout(error)) {
          throw error;
        }
        const normalized = asPhoneControlError(error, "APP_LAUNCH_FAILED");
        if (normalized.code === "APP_LAUNCH_FAILED") {
          throw normalized;
        }
        throw new PhoneControlError(
          "APP_LAUNCH_FAILED",
          `Android failed to launch '${packageName}'.`,
          { packageName, cause: normalized.message, code: normalized.code }
        );
      }
    }

    const startMs = this.#now();
    const timeoutMs = 6000;
    let capture: ObservationCapture | undefined;

    while (this.#now() - startMs <= timeoutMs) {
      const fg = await this.#adb.getForeground(device.serial, targetDisplayId);
      if (fg.packageName === packageName) {
        capture = await this.#capture(device.serial, targetDisplayId);
        break;
      }
      await this.#sleep(DEFAULT_POLL_INTERVAL_MS);
    }

    if (!capture) {
      capture = await this.#capture(device.serial, targetDisplayId);
    }

    assertAllowedForeground(policy, capture);
    if (capture.packageName !== packageName) {
      throw new PhoneControlError(
        "APP_LAUNCH_FAILED",
        `Android did not bring '${packageName}' to the foreground on display ${targetDisplayId} (current foreground is '${capture.packageName}').`,
        { packageName, foregroundPackage: capture.packageName, displayId: targetDisplayId }
      );
    }

    const observation = this.#observations.create(capture);
    return { ok: true, data: { observation: this.#observations.summary(observation) } };
  }

  public async observe(options?: {
    displayId?: number;
    packageName?: string;
  }): Promise<
    ToolSuccessResult<{ observation: ReturnType<ObservationStore["summary"]> }>
  > {
    const policy = this.#getPolicy();
    const device = await this.#selectedDevice();

    let displayId = options?.displayId;
    if (displayId === undefined && options?.packageName) {
      const session = this.#virtualDisplays.getSessionByPackage(options.packageName);
      if (session) {
        displayId = session.displayId;
      }
    }
    if (displayId === undefined) {
      const activeSessions = this.#virtualDisplays.sessions;
      displayId =
        activeSessions.length > 0
          ? activeSessions[activeSessions.length - 1].displayId
          : 0;
    }

    assertAllowedForeground(
      policy,
      await this.#adb.getForeground(device.serial, displayId)
    );
    const capture = await this.#capture(device.serial, displayId);
    assertAllowedForeground(policy, capture);
    const observation = this.#observations.create(capture);
    return { ok: true, data: { observation: this.#observations.summary(observation) } };
  }

  public async execute(
    request: PhoneExecuteRequest
  ): Promise<ToolSuccessResult<ActionData>> {
    const policy = this.#getPolicy();
    const device = await this.#selectedDevice();
    const observation = this.#observations.require(request.observationId);
    const displayId = observation.binding.displayId ?? 0;

    assertAllowedForeground(
      policy,
      await this.#adb.getForeground(device.serial, displayId)
    );

    const current = await this.#capture(device.serial, displayId);
    const targetRef =
      request.action.type === "click"
        ? request.action.elementRef
        : request.action.type === "scroll"
          ? request.action.elementRef
          : undefined;
    const comparison = targetRef
      ? this.#observations.compareElementAction(observation, current, targetRef)
      : this.#observations.compare(observation, current);
    if (!comparison.matches) {
      this.#observations.invalidate(request.observationId);
      throw new PhoneControlError(
        "STALE_OBSERVATION",
        "The screen changed since the observation was captured; refresh before acting.",
        { observationId: request.observationId, changed: comparison.changed }
      );
    }

    // Every action attempt consumes its observation, including a rejected action.
    this.#observations.invalidate(request.observationId);
    const resolved = this.#resolveAction(
      request.action,
      observation,
      current,
      "target" in comparison
        ? (comparison as { target?: UiElement }).target
        : undefined
    );
    const auditBase = {
      at: resolved.pointerEvent?.timestamp ?? this.#now(),
      serial: device.serial,
      packageName: current.packageName,
      action: sanitizeAction(
        request.action,
        resolved.pointerEvent && resolved.pointerEvent.action === "click"
          ? resolved.pointerEvent
          : undefined
      ),
      ...(resolved.pointerEvent ? { pointerEvent: resolved.pointerEvent } : {})
    } satisfies Omit<AuditLogEntry, "outcome">;

    if (resolved.pointerEvent) {
      await this.#appendAudit({
        ...auditBase,
        outcome: "pending",
        phase: "start"
      });
      await this.#sleep(POINTER_START_DELAY_MS);
    }

    try {
      await resolved.perform();
    } catch (error) {
      const normalized = asPhoneControlError(error);
      await this.#appendAudit({
        ...auditBase,
        phase: "result",
        outcome: isTimeout(normalized) ? "unknown" : "failed",
        errorCode: normalized.code
      });
      throw normalized;
    }

    await this.#appendAudit({ ...auditBase, phase: "result", outcome: "success" });
    const after = await this.#capture(device.serial, displayId);
    assertAllowedForeground(policy, after);
    const freshObservation = this.#observations.create(after);
    return {
      ok: true,
      data: {
        action: request.action.type,
        ...(request.action.type === "type"
          ? { textLength: request.action.text.length }
          : {}),
        ...(resolved.pointerEvent ? { pointerEvent: resolved.pointerEvent } : {}),
        observation: this.#observations.summary(freshObservation)
      }
    };
  }

  public async waitFor(
    observationId: string,
    condition: WaitCondition,
    options: WaitOptions = {}
  ): Promise<ToolSuccessResult<{ observation: ReturnType<ObservationStore["summary"]> }>> {
    const policy = this.#getPolicy();
    const requestedTimeout = options.timeoutMs ?? DEFAULT_WAIT_TIMEOUT_MS;
    const requestedPollInterval =
      options.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS;
    if (!Number.isFinite(requestedTimeout) || !Number.isFinite(requestedPollInterval)) {
      throw new PhoneControlError(
        "INVALID_ACTION",
        "Wait timeout and poll interval must be finite numbers."
      );
    }
    const timeoutMs = Math.min(Math.max(0, requestedTimeout), MAX_WAIT_TIMEOUT_MS);
    const pollIntervalMs = Math.min(Math.max(0, requestedPollInterval), 2_000);
    const device = await this.#selectedDevice();
    const baseline = this.#observations.require(observationId);
    const displayId = baseline.binding.displayId ?? 0;

    if (baseline.binding.serial !== device.serial) {
      throw new PhoneControlError(
        "INVALID_OBSERVATION",
        "The baseline observation belongs to a different device.",
        {
          observationId,
          observationSerial: baseline.binding.serial,
          selectedSerial: device.serial
        }
      );
    }
    if (!baseline.binding.packageName) {
      throw new PhoneControlError(
        "INVALID_OBSERVATION",
        "The baseline observation has no authorized package context.",
        { observationId }
      );
    }
    assertAllowedTarget(policy, baseline.binding.packageName);
    const initialForeground = await this.#adb.getForeground(device.serial, displayId);
    assertAllowedForeground(policy, initialForeground);
    if (initialForeground.packageName !== baseline.binding.packageName) {
      throw new PhoneControlError(
        "STALE_OBSERVATION",
        "The baseline package changed before the wait condition began.",
        {
          observationId,
          baselinePackage: baseline.binding.packageName,
          currentPackage: initialForeground.packageName
        }
      );
    }
    const deadline = this.#now() + timeoutMs;

    while (true) {
      const capture = await this.#capture(device.serial, displayId);
      assertAllowedForeground(policy, capture);
      const matched = this.#waitConditionMatches(condition, capture, baseline);
      if (matched) {
        const observation = this.#observations.create(capture);
        return {
          ok: true,
          data: { observation: this.#observations.summary(observation) }
        };
      }
      if (this.#now() >= deadline) {
        throw new PhoneControlError(
          "WAIT_TIMEOUT",
          "The wait condition was not met before the bounded timeout.",
          { timeoutMs, conditionType: condition.type }
        );
      }
      await this.#sleep(Math.min(pollIntervalMs, Math.max(0, deadline - this.#now())));
    }
  }

  public async closeApp(
    target: { packageName?: string; displayId?: number }
  ): Promise<ToolSuccessResult<CloseAppData>> {
    const closed = this.#virtualDisplays.close(target);
    const label =
      target.packageName ??
      (target.displayId !== undefined ? `display ${target.displayId}` : "app");
    return {
      ok: true,
      data: {
        closed,
        packageName: target.packageName,
        displayId: target.displayId,
        message: closed
          ? `Virtual display session for ${label} was terminated.`
          : `No active virtual display session found for ${label}.`
      }
    };
  }

  async #selectedDevice(): Promise<DeviceInfo> {
    return selectDeviceFromEnvironment(
      await this.#adb.listDevices(),
      this.#environment
    );
  }

  async #capture(serial: string, displayId = 0): Promise<ObservationCapture> {
    try {
      const [foreground, display, xml, screenshot] = await Promise.all([
        this.#adb.getForeground(serial, displayId),
        this.#adb.getDisplay(serial, displayId),
        this.#adb.dumpUiAutomatorXml(serial, displayId).catch(() => null),
        this.#adb.captureScreenshot(serial, displayId)
      ]);
      const elements = xml ? parseUiAutomatorXml(xml) : [];
      const screenshotDimensions = parsePngDimensions(screenshot);
      return {
        serial,
        displayId,
        packageName: foreground.packageName,
        activity: foreground.activity,
        display: display.display,
        rotation: display.rotation,
        uiHash: hashUiTree(elements),
        screenshotDimensions,
        observedAt: this.#now(),
        elements,
        screenshot: Uint8Array.from(screenshot)
      };
    } catch (error) {
      const normalized = asPhoneControlError(error, "OBSERVATION_FAILED");
      if (normalized.code === "ADB_TIMEOUT") {
        throw normalized;
      }
      if (normalized.code === "OBSERVATION_FAILED") {
        throw normalized;
      }
      throw new PhoneControlError(
        "OBSERVATION_FAILED",
        "The Android screen could not be captured consistently.",
        { cause: normalized.message, code: normalized.code }
      );
    }
  }

  #resolveAction(
    action: PhoneAction,
    observation: Observation,
    current: ObservationCapture,
    rematchedTarget?: UiElement
  ): {
    perform: () => Promise<void>;
    pointerEvent?: PointerEvent;
  } {
    const displayId = current.displayId ?? 0;
    if (action.type === "click") {
      const element = rematchedTarget ?? observation.elements.find(
        (candidate: UiElement) => candidate.elementRef === action.elementRef
      );
      if (!element) {
        throw new PhoneControlError(
          "STALE_OBSERVATION",
          "The referenced element is no longer present in the current UI.",
          { elementRef: action.elementRef }
        );
      }
      if (element.states.enabled === false || element.states["visibleToUser"] === false) {
        throw new PhoneControlError(
          "STALE_OBSERVATION",
          "The referenced element is no longer actionable.",
          { elementRef: action.elementRef }
        );
      }
      if (!element.bounds) {
        throw new PhoneControlError(
          "ELEMENT_NO_BOUNDS",
          "The referenced element has no usable bounds.",
          { elementRef: action.elementRef }
        );
      }
      const center = boundsCenter(element.bounds);
      assertCoordinateInBounds(center.x, center.y, current.display);
      const pointerEvent = {
        type: "pointer" as const,
        action: "click" as const,
        x: center.x,
        y: center.y,
        coordinateSpace: "display" as const,
        observationId: observation.observationId,
        serial: current.serial,
        displayId,
        packageName: current.packageName,
        displayWidth: current.display.width,
        displayHeight: current.display.height,
        timestamp: this.#now()
      };
      return {
        pointerEvent,
        perform: () =>
          this.#adb.tap(current.serial, center.x, center.y, displayId)
      };
    }

    if (action.type === "click_coordinate") {
      assertCoordinateInBounds(action.x, action.y, current.display);
      return {
        pointerEvent: {
          type: "pointer",
          action: "click",
          x: action.x,
          y: action.y,
          coordinateSpace: "display",
          observationId: observation.observationId,
          serial: current.serial,
          displayId,
          packageName: current.packageName,
          displayWidth: current.display.width,
          displayHeight: current.display.height,
          timestamp: this.#now()
        },
        perform: () =>
          this.#adb.tap(current.serial, action.x, action.y, displayId)
      };
    }

    if (action.type === "scroll") {
      let bounds: Bounds | null | undefined = undefined;
      if (action.elementRef) {
        const element = rematchedTarget ?? observation.elements.find(
          (candidate) => candidate.elementRef === action.elementRef
        );
        if (!element) {
          throw new PhoneControlError(
            "STALE_OBSERVATION",
            `Element with ref "${action.elementRef}" is no longer present in the current UI.`,
            { elementRef: action.elementRef }
          );
        }
        if (element.states.enabled === false || element.states["visibleToUser"] === false) {
          throw new PhoneControlError(
            "STALE_OBSERVATION",
            `Element with ref "${action.elementRef}" is no longer actionable.`,
            { elementRef: action.elementRef }
          );
        }
        if (!element.bounds) {
          throw new PhoneControlError(
            "ELEMENT_NO_BOUNDS",
            `Element "${action.elementRef}" does not have valid bounds for scrolling.`,
            { elementRef: action.elementRef }
          );
        }
        bounds = element.bounds;
      }

      const gesture = calculateScrollGesture(
        current.display,
        action.direction,
        action.amount,
        bounds
      );
      const pointerEvent: PointerEvent = {
        type: "pointer",
        action: "scroll",
        direction: action.direction,
        amount: action.amount,
        ...(action.elementRef ? { elementRef: action.elementRef } : {}),
        startX: gesture.x1,
        startY: gesture.y1,
        endX: gesture.x2,
        endY: gesture.y2,
        durationMs: gesture.durationMs,
        coordinateSpace: "display",
        observationId: observation.observationId,
        serial: current.serial,
        displayId,
        packageName: current.packageName,
        displayWidth: current.display.width,
        displayHeight: current.display.height,
        timestamp: this.#now()
      };
      return {
        pointerEvent,
        perform: () => this.#adb.swipe(current.serial, gesture, displayId)
      };
    }

    if (action.type === "type") {
      return {
        perform: () =>
          this.#adb.typeText(current.serial, action.text, displayId)
      };
    }

    if (action.type === "keypress") {
      return {
        perform: () => this.#adb.keypress(current.serial, action.key, displayId)
      };
    }

    throw new PhoneControlError("INVALID_ACTION", "Unsupported phone action.");
  }

  #waitConditionMatches(
    condition: WaitCondition,
    capture: ObservationCapture,
    baseline: Observation | undefined
  ): boolean {
    switch (condition.type) {
      case "foreground_package":
        return capture.packageName === condition.packageName;
      case "visible_text":
        return capture.elements.some((element) => element.text.includes(condition.text));
      case "visible_resource_id":
        return capture.elements.some(
          (element) => element.resourceId === condition.resourceId
        );
      case "ui_tree_changed":
        return Boolean(baseline && baseline.binding.uiHash !== capture.uiHash);
    }
  }

  async #appendAudit(entry: AuditLogEntry): Promise<void> {
    if (!this.#auditLogger) {
      return;
    }
    try {
      await this.#auditLogger.append(entry);
    } catch (error) {
      console.error(
        `[phone-control] audit log write failed: ${
          error instanceof Error ? error.message : String(error)
        }`
      );
    }
  }
}

export function createDefaultPhoneControlService(
  policy: PolicyProfile
): PhoneControlService {
  return new PhoneControlService({ adb: new AdbProcessAdapter(), policy });
}
