import { AdbProcessAdapter } from "./adb/process-adapter.js";
import type { FixedAdbAdapter, TapBatchResult, TapPoint } from "./adb/adapter.js";
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
import {
  boundsCenter,
  findUniqueElementByTarget,
  hashUiTree,
  parseUiAutomatorXml
} from "./ui-automator.js";
import { loadPolicy } from "./config.js";
import { resolve } from "node:path";
import { VirtualDisplayManager } from "./virtual-display.js";
import { MAX_SEQUENCE_ACTIONS } from "./types.js";
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
  PhoneExecuteSequenceRequest,
  PhoneSequenceAction,
  PhoneStatusData,
  PolicyProfile,
  PointerEvent,
  SequenceData,
  SequenceExecutionMode,
  SequenceStepTiming,
  SequenceStepOutcome,
  SequenceTiming,
  ToolSuccessResult,
  UiElement,
  UiElementTarget,
  VirtualDisplaySession,
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

interface CaptureOptions {
  includeScreenshot?: boolean;
  fallback?: Observation | ObservationCapture;
}

interface PreparedSequenceAction {
  action: PhoneAction;
  target?: UiElement;
}

interface SequenceActionExecution {
  result: ToolSuccessResult<ActionData>;
  capture: ObservationCapture;
  dispatchMs: number;
  observationMs: number;
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

function elapsedMilliseconds(start: number): number {
  return Math.round(Math.max(0, performance.now() - start) * 100) / 100;
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
    const useVirtualDisplay = options.useVirtualDisplay !== false;

    if (options.newInstance === true && !useVirtualDisplay) {
      throw new PhoneControlError(
        "INVALID_ACTION",
        "newInstance is only supported when useVirtualDisplay is true.",
        { packageName, newInstance: true, useVirtualDisplay: false }
      );
    }

    const device = await this.#selectedDevice();

    let targetDisplayId = 0;
    let ownsVirtualDisplay = false;

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
        const reusableSession =
          options.newInstance === true
            ? undefined
            : this.#virtualDisplays.getSessionByPackage(packageName);
        const session = await this.#virtualDisplays.launch(
          device.serial,
          packageName,
          { newInstance: options.newInstance }
        );
        targetDisplayId = session.displayId;
        ownsVirtualDisplay =
          options.newInstance === true || reusableSession === undefined;
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

    try {
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
    } catch (error) {
      // newInstance always creates a fresh session. If the post-launch
      // foreground/observation check fails, close only that fresh display and
      // leave all existing same-package sessions untouched.
      if (ownsVirtualDisplay) {
        this.#virtualDisplays.close({ displayId: targetDisplayId });
      }
      throw error;
    }
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
      const session = this.#uniquePackageSession(options.packageName);
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
    return this.#executeAction(policy, device, observation, request.action);
  }

  /**
   * Execute several typed actions without returning to the model between
   * steps. The default path keeps semantic rematching and foreground checks,
   * but reuses each post-action UI capture as the next immediate starting
   * state. Only a cheap foreground check is repeated between steps; screenshots
   * are captured for the final observation rather than every intermediate
   * result. The single-action path is intentionally unchanged.
   */
  public async executeSequence(
    request: PhoneExecuteSequenceRequest
  ): Promise<ToolSuccessResult<SequenceData>> {
    if (
      request.actions.length < 1 ||
      request.actions.length > MAX_SEQUENCE_ACTIONS
    ) {
      throw new PhoneControlError(
        "INVALID_ACTION",
        `A sequence must contain between 1 and ${MAX_SEQUENCE_ACTIONS} actions.`,
        { actionCount: request.actions.length, maxActions: MAX_SEQUENCE_ACTIONS }
      );
    }

    const executionMode: SequenceExecutionMode =
      request.executionMode ?? "validated";
    if (executionMode === "stable_surface") {
      return this.#executeStableSurfaceSequence(request);
    }
    return this.#executeValidatedSequence(request);
  }

  async #executeValidatedSequence(
    request: PhoneExecuteSequenceRequest
  ): Promise<ToolSuccessResult<SequenceData>> {
    const sequenceStarted = performance.now();
    const policy = this.#getPolicy();
    const device = await this.#selectedDevice();
    const reference = this.#observations.require(request.observationId);
    const displayId = reference.binding.displayId ?? 0;
    const steps: SequenceStepOutcome[] = [];
    const timings: SequenceStepTiming[] = [];
    let baseline = reference;
    let finalObservation = this.#observations.summary(reference);
    let currentCapture: ObservationCapture | undefined;
    let initialCaptureMs = 0;
    let finalCaptureMs = 0;

    const initialStarted = performance.now();
    try {
      // The initial capture is fresh for foreground, display, and UI metadata.
      // Reuse the supplied screenshot bytes because it is not returned for an
      // intermediate sequence result; the final step gets a native screenshot.
      currentCapture = await this.#capture(device.serial, displayId, {
        includeScreenshot: false,
        fallback: reference
      });
      assertAllowedForeground(policy, currentCapture);
      initialCaptureMs = elapsedMilliseconds(initialStarted);
    } catch (error) {
      initialCaptureMs = elapsedMilliseconds(initialStarted);
      const normalized = asPhoneControlError(error);
      this.#observations.invalidate(baseline.observationId);
      const recoveryStarted = performance.now();
      const recovered = await this.#recoverSequenceObservation(
        policy,
        device,
        baseline
      );
      finalCaptureMs = elapsedMilliseconds(recoveryStarted);
      if (recovered) finalObservation = recovered;
      steps.push(this.#sequenceFailure(0, request.actions[0], normalized));
      return this.#sequenceResult(
        request,
        "validated",
        steps,
        finalObservation,
        timings,
        initialCaptureMs,
        finalCaptureMs,
        sequenceStarted
      );
    }

    for (const [index, sequenceAction] of request.actions.entries()) {
      const stepStarted = performance.now();
      const preflightStarted = performance.now();
      try {
        if (!currentCapture) {
          throw new PhoneControlError(
            "INTERNAL_ERROR",
            "The sequence has no current observation state."
          );
        }
        if (index > 0) {
          // The previous post-action capture was authorized immediately before
          // this step. Recheck only foreground here to catch app takeovers
          // without repeating the expensive UI dump and screenshot.
          const foreground = await this.#adb.getForeground(
            device.serial,
            displayId
          );
          assertAllowedForeground(policy, foreground);
          if (
            foreground.packageName !== baseline.binding.packageName ||
            foreground.activity !== baseline.binding.activity
          ) {
            throw new PhoneControlError(
              "STALE_OBSERVATION",
              "The foreground activity changed between sequence steps; refresh before acting.",
              {
                observationId: baseline.observationId,
                baselinePackage: baseline.binding.packageName,
                currentPackage: foreground.packageName,
                baselineActivity: baseline.binding.activity,
                currentActivity: foreground.activity
              }
            );
          }
        }

        const prepared = this.#prepareSequenceAction(
          sequenceAction,
          baseline,
          currentCapture
        );
        const preflightMs = elapsedMilliseconds(preflightStarted);
        this.#observations.invalidate(baseline.observationId);

        const execution = await this.#executeSequenceAction(
          policy,
          device,
          baseline,
          currentCapture,
          prepared,
          index === request.actions.length - 1
        );
        const dispatchMs = execution.dispatchMs;
        const observationMs = execution.observationMs;
        const stepTiming: SequenceStepTiming = {
          index,
          preflightMs,
          dispatchMs,
          observationMs: Math.max(0, observationMs),
          totalMs: elapsedMilliseconds(stepStarted)
        };
        timings.push(stepTiming);
        if (index === request.actions.length - 1) {
          finalCaptureMs = stepTiming.observationMs;
        }
        steps.push({
          index,
          status: "success",
          action: sequenceAction.type,
          ...(execution.result.data.textLength !== undefined
            ? { textLength: execution.result.data.textLength }
            : {}),
          ...(execution.result.data.pointerEvent
            ? { pointerEvent: execution.result.data.pointerEvent }
            : {}),
          observation: execution.result.data.observation
        });
        finalObservation = execution.result.data.observation;
        baseline = this.#observations.require(
          execution.result.data.observation.observationId
        );
        currentCapture = execution.capture;
      } catch (error) {
        const normalized = asPhoneControlError(error);
        const preflightMs = elapsedMilliseconds(preflightStarted);
        const recoveryStarted = performance.now();
        this.#observations.invalidate(baseline.observationId);
        const recovered = await this.#recoverSequenceObservation(
          policy,
          device,
          baseline
        );
        const recoveryMs = elapsedMilliseconds(recoveryStarted);
        finalCaptureMs = Math.max(finalCaptureMs, recoveryMs);
        if (recovered) finalObservation = recovered;
        timings.push({
          index,
          preflightMs,
          dispatchMs: 0,
          observationMs: recoveryMs,
          totalMs: elapsedMilliseconds(stepStarted)
        });
        steps.push(this.#sequenceFailure(index, sequenceAction, normalized));
        break;
      }
    }

    return this.#sequenceResult(
      request,
      "validated",
      steps,
      finalObservation,
      timings,
      initialCaptureMs,
      finalCaptureMs,
      sequenceStarted
    );
  }

  async #executeStableSurfaceSequence(
    request: PhoneExecuteSequenceRequest
  ): Promise<ToolSuccessResult<SequenceData>> {
    const sequenceStarted = performance.now();
    const policy = this.#getPolicy();
    const device = await this.#selectedDevice();
    const reference = this.#observations.require(request.observationId);
    const displayId = reference.binding.displayId ?? 0;
    const steps: SequenceStepOutcome[] = [];
    const timings: SequenceStepTiming[] = [];
    let finalObservation = this.#observations.summary(reference);
    let initialCaptureMs = 0;
    let finalCaptureMs = 0;
    const initialStarted = performance.now();
    let surface: ObservationCapture;

    try {
      surface = await this.#capture(device.serial, displayId, {
        includeScreenshot: false,
        fallback: reference
      });
      assertAllowedForeground(policy, surface);
      const comparison = this.#observations.compare(reference, surface);
      if (!comparison.matches) {
        throw new PhoneControlError(
          "STALE_OBSERVATION",
          "The stable-surface observation changed before dispatch; refresh before acting.",
          { observationId: reference.observationId, changed: comparison.changed }
        );
      }
      initialCaptureMs = elapsedMilliseconds(initialStarted);
    } catch (error) {
      initialCaptureMs = elapsedMilliseconds(initialStarted);
      const normalized = asPhoneControlError(error);
      this.#observations.invalidate(reference.observationId);
      const recoveryStarted = performance.now();
      const recovered = await this.#recoverSequenceObservation(
        policy,
        device,
        reference
      );
      finalCaptureMs = elapsedMilliseconds(recoveryStarted);
      if (recovered) finalObservation = recovered;
      steps.push(this.#sequenceFailure(0, request.actions[0], normalized));
      return this.#sequenceResult(
        request,
        "stable_surface",
        steps,
        finalObservation,
        timings,
        initialCaptureMs,
        finalCaptureMs,
        sequenceStarted
      );
    }

    const prepared: Array<{
      action: PhoneAction;
      target: UiElement;
      pointerEvent: PointerEvent;
      point: TapPoint;
    }> = [];
    const preparationStarted = performance.now();
    let preparationFailedIndex = 0;
    try {
      for (const [index, sequenceAction] of request.actions.entries()) {
        preparationFailedIndex = index;
        if (sequenceAction.type !== "click") {
          throw new PhoneControlError(
            "INVALID_ACTION",
            "stable_surface accepts click actions only; actions requiring intermediate state use validated mode.",
            { actionType: sequenceAction.type }
          );
        }
        const action = this.#prepareStableSurfaceClick(
          sequenceAction,
          reference,
          surface
        );
        const resolved = this.#resolveAction(
          action.action,
          reference,
          surface,
          action.target
        );
        if (!resolved.pointerEvent || resolved.pointerEvent.action !== "click") {
          throw new PhoneControlError(
            "INTERNAL_ERROR",
            "The stable-surface click did not produce a pointer event."
          );
        }
        prepared.push({
          action: action.action,
          target: action.target,
          pointerEvent: resolved.pointerEvent,
          point: { x: resolved.pointerEvent.x, y: resolved.pointerEvent.y }
        });
      }
    } catch (error) {
      const normalized = asPhoneControlError(error);
      this.#observations.invalidate(reference.observationId);
      const recoveryStarted = performance.now();
      const recovered = await this.#recoverSequenceObservation(
        policy,
        device,
        reference
      );
      finalCaptureMs = elapsedMilliseconds(recoveryStarted);
      if (recovered) finalObservation = recovered;
      steps.push(
        this.#sequenceFailure(
          preparationFailedIndex,
          request.actions[preparationFailedIndex],
          normalized
        )
      );
      timings.push({
        index: preparationFailedIndex,
        preflightMs: elapsedMilliseconds(preparationStarted),
        dispatchMs: 0,
        observationMs: finalCaptureMs,
        totalMs: elapsedMilliseconds(preparationStarted)
      });
      return this.#sequenceResult(
        request,
        "stable_surface",
        steps,
        finalObservation,
        timings,
        initialCaptureMs,
        finalCaptureMs,
        sequenceStarted
      );
    }

    this.#observations.invalidate(reference.observationId);
    const dispatchStarted = performance.now();
    let batchResult: TapBatchResult;
    try {
      batchResult = await this.#adb.tapBatch(
        device.serial,
        prepared.map((item) => item.point),
        displayId,
        {
          beforeTap: async (index) => {
            const item = prepared[index];
            item.pointerEvent = {
              ...item.pointerEvent,
              timestamp: this.#now()
            };
            await this.#appendAudit({
              at: item.pointerEvent.timestamp,
              serial: device.serial,
              packageName: surface.packageName,
              action: sanitizeAction(
                item.action,
                this.#pointerCoordinates(item.pointerEvent)
              ),
              pointerEvent: item.pointerEvent,
              outcome: "pending",
              phase: "start"
            });
          }
        }
      );
      if (batchResult.completed !== prepared.length) {
        throw new PhoneControlError(
          "ADB_COMMAND_FAILED",
          "The tap batch reported an incomplete transport result.",
          { completedSteps: batchResult.completed, outcome: "failed" }
        );
      }
    } catch (error) {
      const normalized = asPhoneControlError(error);
      const completed = this.#completedBatchSteps(normalized, prepared.length);
      const unknown = isTimeout(normalized) || normalized.details.outcome === "unknown";
      const recoveryStarted = performance.now();
      const recovered = await this.#recoverSequenceObservation(
        policy,
        device,
        reference
      );
      finalCaptureMs = elapsedMilliseconds(recoveryStarted);
      if (recovered) finalObservation = recovered;
      for (let index = 0; index < completed; index += 1) {
        await this.#appendAudit({
          at: prepared[index].pointerEvent.timestamp,
          serial: device.serial,
          packageName: surface.packageName,
          action: sanitizeAction(
            prepared[index].action,
            this.#pointerCoordinates(prepared[index].pointerEvent)
          ),
          pointerEvent: prepared[index].pointerEvent,
          outcome: "success",
          phase: "result"
        });
        steps.push({
          index,
          status: "success",
          action: request.actions[index].type,
          observation: finalObservation
        });
      }
      const failedIndex = Math.min(completed, prepared.length - 1);
      const failure = this.#sequenceFailure(
        failedIndex,
        request.actions[failedIndex],
        new PhoneControlError(normalized.code, normalized.message, {
          ...normalized.details,
          completedSteps: completed,
          outcome: unknown ? "unknown" : "failed"
        })
      );
      await this.#appendAudit({
        at: prepared[failedIndex].pointerEvent.timestamp,
        serial: device.serial,
        packageName: surface.packageName,
        action: sanitizeAction(
          prepared[failedIndex].action,
          this.#pointerCoordinates(prepared[failedIndex].pointerEvent)
        ),
        pointerEvent: prepared[failedIndex].pointerEvent,
        outcome: unknown ? "unknown" : "failed",
        phase: "result",
        errorCode: normalized.code
      });
      steps.push(failure);
      timings.push({
        index: failedIndex,
        preflightMs: elapsedMilliseconds(preparationStarted),
        dispatchMs: elapsedMilliseconds(dispatchStarted),
        observationMs: finalCaptureMs,
        totalMs: elapsedMilliseconds(sequenceStarted)
      });
      return this.#sequenceResult(
        request,
        "stable_surface",
        steps,
        finalObservation,
        timings,
        initialCaptureMs,
        finalCaptureMs,
        sequenceStarted
      );
    }

    for (let index = 0; index < prepared.length; index += 1) {
      const item = prepared[index];
      await this.#appendAudit({
        at: item.pointerEvent.timestamp,
        serial: device.serial,
        packageName: surface.packageName,
        action: sanitizeAction(
          item.action,
          this.#pointerCoordinates(item.pointerEvent)
        ),
        pointerEvent: item.pointerEvent,
        outcome: "success",
        phase: "result"
      });
    }

    const finalCaptureStarted = performance.now();
    try {
      const after = await this.#capture(device.serial, displayId);
      assertAllowedForeground(policy, after);
      const observation = this.#observations.create(after);
      finalObservation = this.#observations.summary(observation);
      finalCaptureMs = elapsedMilliseconds(finalCaptureStarted);
      const dispatchMs = elapsedMilliseconds(dispatchStarted);
      for (let index = 0; index < prepared.length; index += 1) {
        steps.push({
          index,
          status: "success",
          action: request.actions[index].type,
          pointerEvent: prepared[index].pointerEvent,
          observation: finalObservation
        });
        timings.push({
          index,
          preflightMs: elapsedMilliseconds(preparationStarted),
          dispatchMs,
          observationMs: finalCaptureMs,
          totalMs: elapsedMilliseconds(sequenceStarted)
        });
      }
    } catch (error) {
      const normalized = asPhoneControlError(error);
      finalCaptureMs = elapsedMilliseconds(finalCaptureStarted);
      const recoveryStarted = performance.now();
      const recovered = await this.#recoverSequenceObservation(
        policy,
        device,
        reference
      );
      finalCaptureMs += elapsedMilliseconds(recoveryStarted);
      if (recovered) finalObservation = recovered;
      const failedIndex = prepared.length - 1;
      steps.push(
        this.#sequenceFailure(
          failedIndex,
          request.actions[failedIndex],
          normalized
        )
      );
      timings.push({
        index: failedIndex,
        preflightMs: elapsedMilliseconds(preparationStarted),
        dispatchMs: elapsedMilliseconds(dispatchStarted),
        observationMs: finalCaptureMs,
        totalMs: elapsedMilliseconds(sequenceStarted)
      });
    }

    return this.#sequenceResult(
      request,
      "stable_surface",
      steps,
      finalObservation,
      timings,
      initialCaptureMs,
      finalCaptureMs,
      sequenceStarted
    );
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
    const packageSession =
      target.displayId === undefined && target.packageName !== undefined
        ? this.#uniquePackageSession(target.packageName)
        : undefined;
    const effectiveTarget =
      packageSession && target.displayId === undefined
        ? { ...target, displayId: packageSession.displayId }
        : target;
    const closed = this.#virtualDisplays.close(effectiveTarget);
    const label =
      target.packageName ??
      (target.displayId !== undefined ? `display ${target.displayId}` : "app");
    return {
      ok: true,
      data: {
        closed,
        packageName: target.packageName,
        displayId: effectiveTarget.displayId,
        message: closed
          ? `Virtual display session for ${label} was terminated.`
          : `No active virtual display session found for ${label}.`
      }
    };
  }

  async #recoverSequenceObservation(
    policy: PolicyProfile,
    device: DeviceInfo,
    baseline: Observation
  ): Promise<ReturnType<ObservationStore["summary"]> | undefined> {
    try {
      const capture = await this.#capture(
        device.serial,
        baseline.binding.displayId ?? 0
      );
      assertAllowedForeground(policy, capture);
      const observation = this.#observations.create(capture);
      return this.#observations.summary(observation);
    } catch {
      // A denied foreground or a disconnected device must not be turned into
      // an apparently safe observation. The sequence result still reports the
      // last known summary in that case.
      return undefined;
    }
  }

  #prepareSequenceAction(
    sequenceAction: PhoneSequenceAction,
    baseline: Observation,
    current: ObservationCapture
  ): PreparedSequenceAction {
    const stale = (changed: readonly string[]): never => {
      throw new PhoneControlError(
        "STALE_OBSERVATION",
        "The screen changed between sequence steps; refresh before acting.",
        { observationId: baseline.observationId, changed }
      );
    };

    const resolveTarget = (target: UiElementTarget): UiElement => {
      const hardChanged = this.#observations
        .compare(baseline, current)
        .changed.filter((field) => field !== "uiHash");
      if (hardChanged.length > 0) stale(hardChanged);
      const resolved = findUniqueElementByTarget(target, current.elements);
      if (!resolved) {
        throw new PhoneControlError(
          "STALE_OBSERVATION",
          "The semantic sequence target is not uniquely present in the current UI.",
          { target }
        );
      }
      this.#assertSequenceTargetActionable(resolved);
      return resolved;
    };

    const resolveRef = (elementRef: string): UiElement => {
      const comparison = this.#observations.compareElementAction(
        baseline,
        current,
        elementRef
      );
      const target = comparison.target;
      if (!comparison.matches) stale(comparison.changed);
      if (!target) {
        throw new PhoneControlError(
          "STALE_OBSERVATION",
          "The sequence target could not be rematched in the current UI.",
          { elementRef }
        );
      }
      this.#assertSequenceTargetActionable(target);
      return target;
    };

    const requireUnchanged = (): void => {
      const comparison = this.#observations.compare(baseline, current);
      if (!comparison.matches) stale(comparison.changed);
    };

    if (sequenceAction.type === "click") {
      const target =
        "target" in sequenceAction
          ? resolveTarget(sequenceAction.target)
          : resolveRef(sequenceAction.elementRef);
      return {
        action: { type: "click", elementRef: target.elementRef },
        target
      };
    }

    if (sequenceAction.type === "scroll") {
      if ("target" in sequenceAction) {
        const target = resolveTarget(sequenceAction.target);
        return {
          action: {
            type: "scroll",
            direction: sequenceAction.direction,
            amount: sequenceAction.amount,
            elementRef: target.elementRef
          },
          target
        };
      }
      if (sequenceAction.elementRef) {
        const target = resolveRef(sequenceAction.elementRef);
        return {
          action: {
            type: "scroll",
            direction: sequenceAction.direction,
            amount: sequenceAction.amount,
            elementRef: target.elementRef
          },
          target
        };
      }
      requireUnchanged();
      return {
        action: {
          type: "scroll",
          direction: sequenceAction.direction,
          amount: sequenceAction.amount
        }
      };
    }

    requireUnchanged();
    if (sequenceAction.type === "type") {
      return { action: { type: "type", text: sequenceAction.text } };
    }
    if (sequenceAction.type === "keypress") {
      return { action: { type: "keypress", key: sequenceAction.key } };
    }
    throw new PhoneControlError(
      "INVALID_ACTION",
      "Coordinate clicks are not allowed in an action sequence."
    );
  }

  #prepareStableSurfaceClick(
    sequenceAction: Extract<PhoneSequenceAction, { type: "click" }>,
    reference: Observation,
    surface: ObservationCapture
  ): { action: PhoneAction; target: UiElement } {
    const target =
      "target" in sequenceAction
        ? findUniqueElementByTarget(sequenceAction.target, surface.elements)
        : this.#observations.compareElementAction(
              reference,
              surface,
              sequenceAction.elementRef
            ).target;
    if (!target) {
      throw new PhoneControlError(
        "STALE_OBSERVATION",
        "The stable-surface click target is not uniquely present in the authorized surface.",
        {
          ...( "target" in sequenceAction
            ? { target: sequenceAction.target }
            : { elementRef: sequenceAction.elementRef })
        }
      );
    }
    this.#assertSequenceTargetActionable(target);
    return { action: { type: "click", elementRef: target.elementRef }, target };
  }

  #assertSequenceTargetActionable(element: UiElement): void {
    if (
      element.states.enabled === false ||
      element.states["visibleToUser"] === false
    ) {
      throw new PhoneControlError(
        "STALE_OBSERVATION",
        "The sequence target is no longer actionable.",
        { elementRef: element.elementRef }
      );
    }
    if (!element.bounds) {
      throw new PhoneControlError(
        "ELEMENT_NO_BOUNDS",
        "The sequence target has no usable bounds.",
        { elementRef: element.elementRef }
      );
    }
  }

  async #executeSequenceAction(
    policy: PolicyProfile,
    device: DeviceInfo,
    observation: Observation,
    current: ObservationCapture,
    prepared: PreparedSequenceAction,
    includeScreenshot: boolean
  ): Promise<SequenceActionExecution> {
    const resolved = this.#resolveAction(
      prepared.action,
      observation,
      current,
      prepared.target
    );
    const auditBase = {
      at: resolved.pointerEvent?.timestamp ?? this.#now(),
      serial: device.serial,
      packageName: current.packageName,
      action: sanitizeAction(
        prepared.action,
        resolved.pointerEvent && resolved.pointerEvent.action === "click"
          ? resolved.pointerEvent
          : undefined
      ),
      ...(resolved.pointerEvent ? { pointerEvent: resolved.pointerEvent } : {})
    } satisfies Omit<AuditLogEntry, "outcome">;

    if (resolved.pointerEvent) {
      // The 150 ms viewer animation delay is intentionally omitted for a
      // sequence. The audit start record is still emitted before transport,
      // and the normal single-action path retains its existing delay.
      await this.#appendAudit({
        ...auditBase,
        outcome: "pending",
        phase: "start"
      });
    }

    const dispatchStarted = performance.now();
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
    const dispatchMs = elapsedMilliseconds(dispatchStarted);

    await this.#appendAudit({ ...auditBase, phase: "result", outcome: "success" });
    const observationStarted = performance.now();
    const after = await this.#capture(device.serial, current.displayId ?? 0, {
      includeScreenshot,
      fallback: observation
    });
    const observationMs = elapsedMilliseconds(observationStarted);
    assertAllowedForeground(policy, after);
    const freshObservation = this.#observations.create(after);
    return {
      capture: after,
      dispatchMs,
      observationMs,
      result: {
        ok: true,
        data: {
          action: prepared.action.type,
          ...(prepared.action.type === "type"
            ? { textLength: prepared.action.text.length }
            : {}),
          ...(resolved.pointerEvent
            ? { pointerEvent: resolved.pointerEvent }
            : {}),
          observation: this.#observations.summary(freshObservation)
        }
      }
    };
  }

  #completedBatchSteps(error: PhoneControlError, max: number): number {
    const value = error.details.completedSteps;
    if (typeof value !== "number" || !Number.isInteger(value)) return 0;
    return Math.min(max, Math.max(0, value));
  }

  #pointerCoordinates(
    pointerEvent: PointerEvent
  ): { x: number; y: number } | undefined {
    return pointerEvent.action === "click"
      ? { x: pointerEvent.x, y: pointerEvent.y }
      : undefined;
  }

  #sequenceFailure(
    index: number,
    action: PhoneSequenceAction,
    error: PhoneControlError
  ): SequenceStepOutcome {
    const unknown = isTimeout(error) || error.details.outcome === "unknown";
    return {
      index,
      status: "failed",
      action: action.type,
      ...(unknown ? { outcome: "unknown" as const } : { outcome: "failed" as const }),
      error: {
        code: error.code,
        message: error.message,
        details: error.details
      }
    };
  }

  #sequenceResult(
    request: PhoneExecuteSequenceRequest,
    mode: SequenceExecutionMode,
    steps: readonly SequenceStepOutcome[],
    finalObservation: ReturnType<ObservationStore["summary"]>,
    timings: readonly SequenceStepTiming[],
    initialCaptureMs: number,
    finalCaptureMs: number,
    started: number
  ): ToolSuccessResult<SequenceData> {
    const timing: SequenceTiming = {
      mode,
      initialCaptureMs,
      finalCaptureMs,
      totalMs: elapsedMilliseconds(started),
      steps: timings
    };
    return {
      ok: true,
      data: {
        executionMode: mode,
        completed:
          steps.length === request.actions.length &&
          steps.every((step) => step.status === "success"),
        requestedSteps: request.actions.length,
        completedSteps: steps.filter((step) => step.status === "success").length,
        steps,
        finalObservation,
        timing
      }
    };
  }

  async #executeAction(
    policy: PolicyProfile,
    device: DeviceInfo,
    observation: Observation,
    action: PhoneAction
  ): Promise<ToolSuccessResult<ActionData>> {
    const displayId = observation.binding.displayId ?? 0;

    assertAllowedForeground(
      policy,
      await this.#adb.getForeground(device.serial, displayId)
    );

    const current = await this.#capture(device.serial, displayId);
    const targetRef =
      action.type === "click"
        ? action.elementRef
        : action.type === "scroll"
          ? action.elementRef
          : undefined;
    const comparison = targetRef
      ? this.#observations.compareElementAction(observation, current, targetRef)
      : this.#observations.compare(observation, current);
    if (!comparison.matches) {
      this.#observations.invalidate(observation.observationId);
      throw new PhoneControlError(
        "STALE_OBSERVATION",
        "The screen changed since the observation was captured; refresh before acting.",
        { observationId: observation.observationId, changed: comparison.changed }
      );
    }

    // Every action attempt consumes its observation, including a rejected
    // action. Sequence callers rely on this just as single-action callers do.
    this.#observations.invalidate(observation.observationId);
    const resolved = this.#resolveAction(
      action,
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
        action,
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
        action: action.type,
        ...(action.type === "type"
          ? { textLength: action.text.length }
          : {}),
        ...(resolved.pointerEvent ? { pointerEvent: resolved.pointerEvent } : {}),
        observation: this.#observations.summary(freshObservation)
      }
    };
  }

  async #selectedDevice(): Promise<DeviceInfo> {
    return selectDeviceFromEnvironment(
      await this.#adb.listDevices(),
      this.#environment
    );
  }

  #uniquePackageSession(
    packageName: string
  ): VirtualDisplaySession | undefined {
    const matches = this.#virtualDisplays.sessions.filter(
      (session) => session.packageName === packageName
    );
    if (matches.length > 1) {
      throw new PhoneControlError(
        "INVALID_ACTION",
        `Multiple active virtual display sessions match '${packageName}'. Use displayId to select one.`,
        {
          packageName,
          displayIds: matches.map((session) => session.displayId)
        }
      );
    }
    return matches[0];
  }

  async #capture(
    serial: string,
    displayId = 0,
    options: CaptureOptions = {}
  ): Promise<ObservationCapture> {
    try {
      const includeScreenshot = options.includeScreenshot !== false;
      const fallback = options.fallback;
      if (!includeScreenshot && !fallback) {
        throw new PhoneControlError(
          "INTERNAL_ERROR",
          "An intermediate capture requires a screenshot fallback."
        );
      }
      const [foreground, display, xml, screenshot] = await Promise.all([
        this.#adb.getForeground(serial, displayId),
        this.#adb.getDisplay(serial, displayId),
        this.#adb.dumpUiAutomatorXml(serial, displayId).catch(() => null),
        includeScreenshot
          ? this.#adb.captureScreenshot(serial, displayId)
          : Promise.resolve<Uint8Array | undefined>(undefined)
      ]);
      const elements = xml ? parseUiAutomatorXml(xml) : [];
      const fallbackScreenshotDimensions = fallback
        ? "binding" in fallback
          ? fallback.binding.screenshotDimensions
          : fallback.screenshotDimensions
        : undefined;
      const screenshotBytes = screenshot ?? fallback?.screenshot;
      if (!screenshotBytes || !fallbackScreenshotDimensions && !screenshot) {
        throw new PhoneControlError(
          "OBSERVATION_FAILED",
          "The Android screen screenshot could not be retained for the observation."
        );
      }
      const screenshotDimensions = screenshot
        ? parsePngDimensions(screenshot)
        : fallbackScreenshotDimensions!;
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
        screenshot: Uint8Array.from(screenshotBytes)
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
