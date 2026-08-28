import { createHash, randomBytes } from "node:crypto";

import { PhoneControlError } from "./errors.js";
import type {
  Observation,
  ObservationBinding,
  ObservationCapture,
  ObservationSummary,
  UiElement
} from "./types.js";
import {
  elementActionStateKey,
  findUniqueElementMatch,
  isElementPotentiallyObscured
} from "./ui-automator.js";

export interface ObservationComparison {
  matches: boolean;
  changed: readonly string[];
}

export interface ElementActionComparison extends ObservationComparison {
  target?: UiElement;
}

type IdFactory = () => string;

function createOpaqueObservationId(): string {
  return `obs_${randomBytes(18).toString("base64url")}`;
}

/** Fingerprint the exact PNG bytes shown to the agent and used for a visual action. */
export function hashScreenshot(screenshot: Uint8Array): string {
  return createHash("sha256").update(screenshot).digest("hex");
}

function bindingFromCapture(capture: ObservationCapture): ObservationBinding {
  const displayId = capture.displayId ?? 0;
  const mode = capture.mode ?? "semantic";
  return {
    serial: capture.serial,
    displayId,
    mode,
    packageName: capture.packageName,
    activity: capture.activity,
    display: {
      ...capture.display,
      displayId: capture.display.displayId ?? displayId
    },
    rotation: capture.rotation,
    ...(capture.uiHash !== undefined ? { uiHash: capture.uiHash } : {}),
    // Derive the fingerprint from the bytes rather than trusting metadata
    // supplied by a caller. The binding must describe the exact PNG retained
    // in the store.
    screenshotHash: hashScreenshot(capture.screenshot),
    screenshotDimensions: { ...capture.screenshotDimensions },
    observedAt: capture.observedAt
  };
}

export class ObservationBuilder {
  public constructor(private readonly idFactory: IdFactory = createOpaqueObservationId) {}

  public build(capture: ObservationCapture): Observation {
    const observationId = this.idFactory();
    return {
      observationId,
      binding: bindingFromCapture(capture),
      elements: capture.elements.map((element) => ({
        ...element,
        states: { ...element.states },
        bounds: element.bounds ? { ...element.bounds } : null
      })),
      screenshot: Uint8Array.from(capture.screenshot)
    };
  }
}

export class ObservationStore {
  readonly #observations = new Map<string, Observation>();
  readonly #builder: ObservationBuilder;

  public constructor(idFactory?: IdFactory) {
    this.#builder = new ObservationBuilder(idFactory);
  }

  public create(capture: ObservationCapture): Observation {
    const observation = this.#builder.build(capture);
    this.#observations.set(observation.observationId, observation);
    return observation;
  }

  public get(observationId: string): Observation | undefined {
    return this.#observations.get(observationId);
  }

  public require(observationId: string): Observation {
    const observation = this.get(observationId);
    if (!observation) {
      throw new PhoneControlError(
        "INVALID_OBSERVATION",
        "The observation ID is unknown or has already been invalidated.",
        { observationId }
      );
    }
    return observation;
  }

  public invalidate(observationId: string): void {
    this.#observations.delete(observationId);
  }

  public compare(
    observation: Observation,
    current: ObservationCapture
  ): ObservationComparison {
    const changed: string[] = [];
    const binding = observation.binding;
    const bindingMode = binding.mode ?? "semantic";
    // Legacy callers may construct a fresh capture without the mode field.
    // Treat that as the reference mode so a visual observation is not rejected
    // merely because semantic metadata was not collected.
    const currentMode = current.mode ?? bindingMode;
    if (binding.serial !== current.serial) changed.push("serial");
    if ((binding.displayId ?? 0) !== (current.displayId ?? 0)) changed.push("displayId");
    if (bindingMode !== currentMode) changed.push("mode");
    if (binding.packageName !== current.packageName) changed.push("packageName");
    if (binding.activity !== current.activity) changed.push("activity");
    if (
      binding.display.width !== current.display.width ||
      binding.display.height !== current.display.height
    ) {
      changed.push("display");
    }
    if (binding.rotation !== current.rotation) changed.push("rotation");
    if (bindingMode === "visual" || currentMode === "visual") {
      const currentScreenshotHash = hashScreenshot(current.screenshot);
      if (binding.screenshotHash !== currentScreenshotHash) {
        changed.push("screenshotHash");
      }
    } else if (
      binding.uiHash !== undefined &&
      current.uiHash !== undefined &&
      binding.uiHash !== current.uiHash
    ) {
      changed.push("uiHash");
    }
    if (
      binding.screenshotDimensions.width !== current.screenshotDimensions.width ||
      binding.screenshotDimensions.height !== current.screenshotDimensions.height
    ) {
      changed.push("screenshotDimensions");
    }
    return { matches: changed.length === 0, changed };
  }

  /**
   * Relax only the UI hash for element-targeted actions. Every hard binding is
   * still checked. Unrelated nodes may be added, removed, reordered, or mutate;
   * the target's semantic identity, ancestor context, state, and hit area remain
   * strict.
   */
  public compareElementAction(
    observation: Observation,
    current: ObservationCapture,
    elementRef: string
  ): ElementActionComparison {
    const hard = this.compare(observation, current);
    const changed = hard.changed.filter((field) => field !== "uiHash");
    const target = observation.elements.find(
      (element) => element.elementRef === elementRef
    );
    if (!target) {
      return { matches: false, changed: [...changed, "target"] };
    }

    const rematched = findUniqueElementMatch(
      target,
      observation.elements,
      current.elements
    );
    if (!rematched) {
      return { matches: false, changed: [...changed, "target"] };
    }
    if (
      elementActionStateKey(rematched) !== elementActionStateKey(target)
    ) {
      return { matches: false, changed: [...changed, "target"] };
    }
    if (
      isElementPotentiallyObscured(
        rematched,
        current.elements,
        target,
        observation.elements
      )
    ) {
      return { matches: false, changed: [...changed, "targetObscured"] };
    }
    return { matches: changed.length === 0, changed, target: rematched };
  }

  /**
   * Coordinate actions are grounded in pixels, so the exact screenshot must
   * remain unchanged even when a semantic observation also has a matching UI
   * tree. This is deliberately stricter than element-targeted actions, which
   * may rematch an unchanged target after unrelated semantic mutations.
   */
  public compareCoordinateAction(
    observation: Observation,
    current: ObservationCapture
  ): ObservationComparison {
    const comparison = this.compare(observation, current);
    const changed = [...comparison.changed];
    const currentScreenshotHash = hashScreenshot(current.screenshot);
    if (
      observation.binding.screenshotHash !== currentScreenshotHash &&
      !changed.includes("screenshotHash")
    ) {
      changed.push("screenshotHash");
    }
    return { matches: changed.length === 0, changed };
  }

  public summary(observation: Observation): ObservationSummary {
    return {
      observationId: observation.observationId,
      serial: observation.binding.serial,
      displayId: observation.binding.displayId ?? 0,
      mode: observation.binding.mode ?? "semantic",
      packageName: observation.binding.packageName,
      activity: observation.binding.activity,
      display: { ...observation.binding.display },
      rotation: observation.binding.rotation,
      ...(observation.binding.uiHash !== undefined
        ? { uiHash: observation.binding.uiHash }
        : {}),
      screenshotHash: observation.binding.screenshotHash,
      screenshot: {
        mimeType: "image/png",
        width: observation.binding.screenshotDimensions.width,
        height: observation.binding.screenshotDimensions.height
      },
      elements: observation.elements.map((element: UiElement) => ({
        ...element,
        states: { ...element.states },
        bounds: element.bounds ? { ...element.bounds } : null
      })),
      observedAt: observation.binding.observedAt
    };
  }
}
