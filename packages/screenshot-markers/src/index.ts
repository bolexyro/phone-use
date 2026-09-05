import { randomInt } from "node:crypto";

import { PNG } from "pngjs";

export const POINTER_ARROW_PATH = "M 4 4 L 38 16 L 24 24 L 16 38 Z";
export const POINTER_ARROW_FILL = "#2b8cdb";

/** Shared prompt text for every agent-facing phone screenshot surface. */
export const SCREENSHOT_MARKER_GUIDANCE =
  "Screenshots may contain a small blue arrow with a contrasting black or white outline and glow. This arrow is injected by the phone-control tool for coordinate calibration; it is not part of the Android app UI and is not a control. The arrow tip is the exact display-pixel coordinate. On the first screenshot it marks a calibration point. After a successful tap it marks the last executed tap. A coordinate-tap response may also include a compact before-tap evidence crop followed by the full current screenshot; screenshotEvidence gives the crop bounds and tap coordinate. The separate screenshotMarker metadata has only kind, x, y, and coordinateSpace; observationId, displayId, and screenshot dimensions remain in the surrounding observation. Use screenshotMarker and screenshotEvidence for their meaning and coordinates, and ignore the arrow when identifying app controls.";

/** Shared prompt text for stale-action diagnostics across phone surfaces. */
export const STALE_OBSERVATION_GUIDANCE =
  "If an action is rejected with STALE_OBSERVATION, inputSent is false and reasons explain every detected difference. Reasons may include ROTATION_CHANGED, DISPLAY_CHANGED, DISPLAY_SIZE_CHANGED, PACKAGE_CHANGED, ACTIVITY_CHANGED, GUARD_REGION_CHANGED, or OBSERVATION_REPLACED. GUARD_REGION_CHANGED means a configured visual guard fingerprint changed; it does not mean that every screenshot pixel was compared. Refresh the observation before retrying.";

/** Keep the extra before-action image small enough to avoid doubling vision cost. */
export const ACTION_EVIDENCE_CROP_SIZE = 320;

export type ScreenshotMarkerKind = "calibration" | "last_tap";

export interface ScreenshotMarker {
  kind: ScreenshotMarkerKind;
  x: number;
  y: number;
  coordinateSpace: "display";
}

export interface ScreenshotMarkerObservation {
  observationId: string;
  displayId?: number;
  packageName?: string | null;
  rotation?: number;
  screenshotDimensions: {
    width: number;
    height: number;
  };
}

export interface ScreenshotMarkerPoint {
  x: number;
  y: number;
}

export interface ScreenshotCropBounds {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface ScreenshotEvidenceCrop {
  screenshot: Uint8Array;
  bounds: ScreenshotCropBounds;
}

export interface ScreenshotEvidenceMetadata {
  kind: "before_tap_crop";
  sourceObservationId: string;
  tap: ScreenshotMarkerPoint;
  coordinateSpace: "display";
  crop: ScreenshotCropBounds;
}

export interface ScreenshotMarkerRender {
  screenshot: Uint8Array;
  marker: ScreenshotMarker;
}

export interface ScreenshotMarkerOptions {
  chooseAnchor?: (
    dimensions: ScreenshotMarkerObservation["screenshotDimensions"]
  ) => ScreenshotMarkerPoint;
}

interface MarkerState {
  packageName: string | null | undefined;
  rotation: number | undefined;
  width: number;
  height: number;
  marker: ScreenshotMarker;
}

const ANCHOR_POSITIONS = [
  [0.18, 0.16],
  [0.82, 0.16],
  [0.18, 0.84],
  [0.82, 0.84]
] as const;

function defaultChooseAnchor(
  dimensions: ScreenshotMarkerObservation["screenshotDimensions"]
): ScreenshotMarkerPoint {
  const [xRatio, yRatio] = ANCHOR_POSITIONS[randomInt(0, ANCHOR_POSITIONS.length)];
  return {
    x: clamp(Math.round(dimensions.width * xRatio), 0, dimensions.width - 1),
    y: clamp(Math.round(dimensions.height * yRatio), 0, dimensions.height - 1)
  };
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value));
}

function normalizeDisplayId(displayId: number | undefined): number {
  return displayId ?? 0;
}

function sameObservation(
  state: MarkerState,
  observation: ScreenshotMarkerObservation
): boolean {
  return (
    state.packageName === observation.packageName &&
    state.rotation === observation.rotation &&
    state.width === observation.screenshotDimensions.width &&
    state.height === observation.screenshotDimensions.height
  );
}

function pointInBounds(
  point: ScreenshotMarkerPoint,
  dimensions: ScreenshotMarkerObservation["screenshotDimensions"]
): boolean {
  return (
    Number.isInteger(point.x) &&
    Number.isInteger(point.y) &&
    point.x >= 0 &&
    point.y >= 0 &&
    point.x < dimensions.width &&
    point.y < dimensions.height
  );
}

/**
 * Owns presentation state only. It never changes the screenshot bytes stored
 * in an observation or any screenshot fingerprint used for action safety.
 */
export class ScreenshotMarkerPresenter {
  readonly #states = new Map<number, MarkerState>();
  readonly #chooseAnchor: NonNullable<ScreenshotMarkerOptions["chooseAnchor"]>;

  public constructor(options: ScreenshotMarkerOptions = {}) {
    this.#chooseAnchor = options.chooseAnchor ?? defaultChooseAnchor;
  }

  public reset(displayId?: number): void {
    if (displayId === undefined) {
      this.#states.clear();
      return;
    }
    this.#states.delete(displayId);
  }

  public render(
    screenshot: Uint8Array,
    observation: ScreenshotMarkerObservation,
    options: {
      reset?: boolean;
      lastTap?: ScreenshotMarkerPoint;
    } = {}
  ): ScreenshotMarkerRender {
    const displayId = normalizeDisplayId(observation.displayId);
    const prior = this.#states.get(displayId);
    const compatible = prior !== undefined && sameObservation(prior, observation);
    const dimensions = observation.screenshotDimensions;
    let state: MarkerState;
    if (options.reset || !compatible) {
      state = {
        packageName: observation.packageName,
        rotation: observation.rotation,
        width: dimensions.width,
        height: dimensions.height,
        marker: {
          kind: "calibration" as const,
          ...this.#chooseAnchor(dimensions),
          coordinateSpace: "display" as const
        }
      };
    } else {
      state = prior;
    }

    const marker =
      options.lastTap && pointInBounds(options.lastTap, dimensions)
        ? {
            kind: "last_tap" as const,
            x: options.lastTap.x,
            y: options.lastTap.y,
            coordinateSpace: "display" as const
          }
        : state.marker;

    const nextState: MarkerState = {
      packageName: observation.packageName,
      rotation: observation.rotation,
      width: dimensions.width,
      height: dimensions.height,
      marker
    };
    const annotated = annotateScreenshotPng(screenshot, dimensions, marker);
    this.#states.set(displayId, nextState);
    return { screenshot: annotated, marker };
  }
}

interface Rgba {
  r: number;
  g: number;
  b: number;
  a: number;
}

interface Point {
  x: number;
  y: number;
}

function parseHexColor(value: string, alpha = 255): Rgba {
  const normalized = value.replace("#", "");
  return {
    r: Number.parseInt(normalized.slice(0, 2), 16),
    g: Number.parseInt(normalized.slice(2, 4), 16),
    b: Number.parseInt(normalized.slice(4, 6), 16),
    a: alpha
  };
}

function blendPixel(png: PNG, x: number, y: number, color: Rgba): void {
  if (x < 0 || y < 0 || x >= png.width || y >= png.height) return;
  const index = (y * png.width + x) * 4;
  const sourceAlpha = color.a / 255;
  const destinationAlpha = png.data[index + 3] / 255;
  const outputAlpha = sourceAlpha + destinationAlpha * (1 - sourceAlpha);
  if (outputAlpha <= 0) return;

  png.data[index] = Math.round(
    (color.r * sourceAlpha + png.data[index] * destinationAlpha * (1 - sourceAlpha)) /
      outputAlpha
  );
  png.data[index + 1] = Math.round(
    (color.g * sourceAlpha + png.data[index + 1] * destinationAlpha * (1 - sourceAlpha)) /
      outputAlpha
  );
  png.data[index + 2] = Math.round(
    (color.b * sourceAlpha + png.data[index + 2] * destinationAlpha * (1 - sourceAlpha)) /
      outputAlpha
  );
  png.data[index + 3] = Math.round(outputAlpha * 255);
}

function distanceToSegment(point: Point, start: Point, end: Point): number {
  const dx = end.x - start.x;
  const dy = end.y - start.y;
  if (dx === 0 && dy === 0) {
    return Math.hypot(point.x - start.x, point.y - start.y);
  }
  const t = clamp(
    ((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy),
    0,
    1
  );
  return Math.hypot(point.x - (start.x + t * dx), point.y - (start.y + t * dy));
}

function drawLine(
  png: PNG,
  start: Point,
  end: Point,
  width: number,
  color: Rgba
): void {
  const radius = width / 2;
  const left = Math.floor(Math.min(start.x, end.x) - radius - 1);
  const right = Math.ceil(Math.max(start.x, end.x) + radius + 1);
  const top = Math.floor(Math.min(start.y, end.y) - radius - 1);
  const bottom = Math.ceil(Math.max(start.y, end.y) + radius + 1);
  for (let y = top; y <= bottom; y += 1) {
    for (let x = left; x <= right; x += 1) {
      if (distanceToSegment({ x: x + 0.5, y: y + 0.5 }, start, end) <= radius) {
        blendPixel(png, x, y, color);
      }
    }
  }
}

function pointInPolygon(point: Point, polygon: readonly Point[]): boolean {
  let inside = false;
  for (let index = 0, previous = polygon.length - 1; index < polygon.length; previous = index++) {
    const currentPoint = polygon[index];
    const previousPoint = polygon[previous];
    const intersects =
      currentPoint.y > point.y !== previousPoint.y > point.y &&
      point.x <
        ((previousPoint.x - currentPoint.x) * (point.y - currentPoint.y)) /
          (previousPoint.y - currentPoint.y) +
          currentPoint.x;
    if (intersects) inside = !inside;
  }
  return inside;
}

function fillPolygon(png: PNG, polygon: readonly Point[], color: Rgba): void {
  const left = Math.floor(Math.min(...polygon.map((point) => point.x)) - 1);
  const right = Math.ceil(Math.max(...polygon.map((point) => point.x)) + 1);
  const top = Math.floor(Math.min(...polygon.map((point) => point.y)) - 1);
  const bottom = Math.ceil(Math.max(...polygon.map((point) => point.y)) + 1);
  for (let y = top; y <= bottom; y += 1) {
    for (let x = left; x <= right; x += 1) {
      if (pointInPolygon({ x: x + 0.5, y: y + 0.5 }, polygon)) {
        blendPixel(png, x, y, color);
      }
    }
  }
}

function localLuminance(png: PNG, point: ScreenshotMarkerPoint, radius: number): number {
  let total = 0;
  let count = 0;
  const left = Math.floor(point.x - radius);
  const right = Math.ceil(point.x + radius);
  const top = Math.floor(point.y - radius);
  const bottom = Math.ceil(point.y + radius);
  for (let y = top; y <= bottom; y += 1) {
    for (let x = left; x <= right; x += 1) {
      if (x < 0 || y < 0 || x >= png.width || y >= png.height) continue;
      const index = (y * png.width + x) * 4;
      total +=
        0.2126 * png.data[index] +
        0.7152 * png.data[index + 1] +
        0.0722 * png.data[index + 2];
      count += 1;
    }
  }
  return count === 0 ? 0 : total / count;
}

function drawArrow(png: PNG, marker: ScreenshotMarker): void {
  const size = clamp(Math.round(Math.min(png.width, png.height) * 0.04), 32, 56);
  const scale = size / 48;
  const polygon: Point[] = [
    { x: marker.x, y: marker.y },
    { x: marker.x + 34 * scale, y: marker.y + 12 * scale },
    { x: marker.x + 20 * scale, y: marker.y + 20 * scale },
    { x: marker.x + 12 * scale, y: marker.y + 34 * scale }
  ];
  const outline = localLuminance(png, marker, Math.max(4, size * 0.18)) > 145
    ? parseHexColor("#111111")
    : parseHexColor("#ffffff");
  const halo = { ...outline, a: 190 };
  const segments = polygon.map((point, index) => [point, polygon[(index + 1) % polygon.length]] as const);
  for (const [start, end] of segments) drawLine(png, start, end, Math.max(6, size * 0.18), halo);
  fillPolygon(png, polygon, parseHexColor(POINTER_ARROW_FILL));
  for (const [start, end] of segments) drawLine(png, start, end, Math.max(2, size * 0.055), outline);
}

export function annotateScreenshotPng(
  screenshot: Uint8Array,
  dimensions: ScreenshotMarkerObservation["screenshotDimensions"],
  marker: ScreenshotMarker
): Uint8Array {
  const png = PNG.sync.read(Buffer.from(screenshot));
  if (png.width !== dimensions.width || png.height !== dimensions.height) {
    throw new Error(
      `Screenshot dimensions ${png.width}x${png.height} do not match ${dimensions.width}x${dimensions.height}.`
    );
  }
  drawArrow(png, marker);
  return Uint8Array.from(PNG.sync.write(png));
}

/**
 * Extract a small context window around an already-annotated display
 * screenshot. The returned bounds map the crop back to display coordinates;
 * the source screenshot is never modified.
 */
export function cropScreenshotPng(
  screenshot: Uint8Array,
  dimensions: ScreenshotMarkerObservation["screenshotDimensions"],
  point: ScreenshotMarkerPoint,
  size = ACTION_EVIDENCE_CROP_SIZE
): ScreenshotEvidenceCrop {
  const png = PNG.sync.read(Buffer.from(screenshot));
  if (png.width !== dimensions.width || png.height !== dimensions.height) {
    throw new Error(
      `Screenshot dimensions ${png.width}x${png.height} do not match ${dimensions.width}x${dimensions.height}.`
    );
  }
  if (!pointInBounds(point, dimensions)) {
    throw new Error(
      `Screenshot crop point ${point.x},${point.y} is outside ${dimensions.width}x${dimensions.height}.`
    );
  }
  if (!Number.isInteger(size) || size < 1) {
    throw new Error(`Screenshot crop size must be a positive integer, got ${size}.`);
  }

  const width = Math.min(size, png.width);
  const height = Math.min(size, png.height);
  const left = clamp(point.x - Math.floor(width / 2), 0, png.width - width);
  const top = clamp(point.y - Math.floor(height / 2), 0, png.height - height);
  const crop = new PNG({ width, height });
  for (let y = 0; y < height; y += 1) {
    const sourceStart = ((top + y) * png.width + left) * 4;
    const targetStart = y * width * 4;
    png.data.copy(crop.data, targetStart, sourceStart, sourceStart + width * 4);
  }
  return {
    screenshot: Uint8Array.from(PNG.sync.write(crop)),
    bounds: { left, top, width, height }
  };
}
