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
import type {
  ActionData,
  AllowedAppsData,
  Bounds,
  DeviceInfo,
  ForegroundState,
  Observation,
  ObservationCapture,
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
  readonly #auditLogger?: ActionAuditLogger;
  readonly #now: () => number;
  readonly #sleep: (milliseconds: number) => Promise<void>;

  public constructor(options: PhoneControlServiceOptions) {
    this.#adb = options.adb;
    this.#policy = options.policy;
    this.#environment = options.environment ?? process.env;
    this.#observations = options.observationStore ?? new ObservationStore();
    this.#auditLogger =
      options.auditLogger ?? new NdjsonActionLogger(DEFAULT_ACTION_LOG_PATH);
    this.#now = options.now ?? Date.now;
    this.#sleep = options.sleep ?? defaultSleep;
  }

  public get observationStore(): ObservationStore {
    return this.#observations;
  }

  #getPolicy(): PolicyProfile {
    try {
      return loadPolicy({ env: this.#environment });
    } catch {
      return this.#policy;
    }
  }

  public async status(): Promise<ToolSuccessResult<PhoneStatusData>> {
    const policy = this.#getPolicy();
    const device = await this.#selectedDevice();
    const foreground = await this.#adb.getForeground(device.serial);
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
        )
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
    packageName: string
  ): Promise<ToolSuccessResult<{ observation: ReturnType<ObservationStore["summary"]> }>> {
    const policy = this.#getPolicy();
    assertAllowedTarget(policy, packageName);
    const device = await this.#selectedDevice();

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

    const startMs = this.#now();
    const timeoutMs = 6000;
    let capture: ObservationCapture | undefined;

    while (this.#now() - startMs <= timeoutMs) {
      const fg = await this.#adb.getForeground(device.serial);
      if (fg.packageName === packageName) {
        capture = await this.#capture(device.serial);
        break;
      }
      await this.#sleep(DEFAULT_POLL_INTERVAL_MS);
    }

    if (!capture) {
      capture = await this.#capture(device.serial);
    }

    assertAllowedForeground(policy, capture);
    if (capture.packageName !== packageName) {
      throw new PhoneControlError(
        "APP_LAUNCH_FAILED",
        `Android did not bring '${packageName}' to the foreground (current foreground is '${capture.packageName}').`,
        { packageName, foregroundPackage: capture.packageName }
      );
    }

    const observation = this.#observations.create(capture);
    return { ok: true, data: { observation: this.#observations.summary(observation) } };
  }

  public async observe(): Promise<
    ToolSuccessResult<{ observation: ReturnType<ObservationStore["summary"]> }>
  > {
    const policy = this.#getPolicy();
    const device = await this.#selectedDevice();
    assertAllowedForeground(
      policy,
      await this.#adb.getForeground(device.serial)
    );
    const capture = await this.#capture(device.serial);
    assertAllowedForeground(policy, capture);
    const observation = this.#observations.create(capture);
    return { ok: true, data: { observation: this.#observations.summary(observation) } };
  }

  public async execute(
    request: PhoneExecuteRequest
  ): Promise<ToolSuccessResult<ActionData>> {
    const policy = this.#getPolicy();
    const device = await this.#selectedDevice();
    assertAllowedForeground(
      policy,
      await this.#adb.getForeground(device.serial)
    );

    const observation = this.#observations.require(request.observationId);
    const current = await this.#capture(device.serial);
    const comparison = this.#observations.compare(observation, current);
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
    const resolved = this.#resolveAction(request.action, observation, current);
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
    const after = await this.#capture(device.serial);
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
    const initialForeground = await this.#adb.getForeground(device.serial);
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
      const capture = await this.#capture(device.serial);
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

  async #selectedDevice(): Promise<DeviceInfo> {
    return selectDeviceFromEnvironment(
      await this.#adb.listDevices(),
      this.#environment
    );
  }

  async #capture(serial: string): Promise<ObservationCapture> {
    try {
      const [foreground, display, xml, screenshot] = await Promise.all([
        this.#adb.getForeground(serial),
        this.#adb.getDisplay(serial),
        this.#adb.dumpUiAutomatorXml(serial).catch(() => null),
        this.#adb.captureScreenshot(serial)
      ]);
      const elements = xml ? parseUiAutomatorXml(xml) : [];
      const screenshotDimensions = parsePngDimensions(screenshot);
      return {
        serial,
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
    current: ObservationCapture
  ): {
    perform: () => Promise<void>;
    pointerEvent?: PointerEvent;
  } {
    if (action.type === "click") {
      const element = observation.elements.find(
        (candidate: UiElement) => candidate.elementRef === action.elementRef
      );
      if (!element) {
        throw new PhoneControlError(
          "ELEMENT_NOT_FOUND",
          "The element reference is not present in the observation.",
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
        packageName: current.packageName,
        displayWidth: current.display.width,
        displayHeight: current.display.height,
        timestamp: this.#now()
      };
      return {
        pointerEvent,
        perform: () => this.#adb.tap(current.serial, center.x, center.y)
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
          packageName: current.packageName,
          displayWidth: current.display.width,
          displayHeight: current.display.height,
          timestamp: this.#now()
        },
        perform: () => this.#adb.tap(current.serial, action.x, action.y)
      };
    }

    if (action.type === "scroll") {
      let bounds: Bounds | null | undefined = undefined;
      if (action.elementRef) {
        const element = observation.elements.find(
          (candidate) => candidate.elementRef === action.elementRef
        );
        if (!element) {
          throw new PhoneControlError(
            "ELEMENT_NOT_FOUND",
            `Element with ref "${action.elementRef}" was not found in observation ${observation.observationId}.`,
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
        packageName: current.packageName,
        displayWidth: current.display.width,
        displayHeight: current.display.height,
        timestamp: this.#now()
      };
      return {
        pointerEvent,
        perform: () => this.#adb.swipe(current.serial, gesture)
      };
    }

    if (action.type === "type") {
      return { perform: () => this.#adb.typeText(current.serial, action.text) };
    }

    if (action.type === "keypress") {
      return { perform: () => this.#adb.keypress(current.serial, action.key) };
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
