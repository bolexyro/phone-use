import { createHash } from "node:crypto";

import { PhoneControlError } from "../errors.js";
import type {
  DeviceInfo,
  DisplayInfo,
  DisplaySnapshot,
  ForegroundState,
  ScreenshotDimensions
} from "../types.js";

function parseComponent(line: string): ForegroundState | null {
  const match = line.match(
    /\bu\d+\s+([A-Za-z0-9._]+)\/([A-Za-z0-9_.$/:-]+)/
  ) ?? line.match(/\b([A-Za-z0-9._]+)\/([A-Za-z0-9_.$/:-]+)/);
  if (!match) {
    return null;
  }

  const packageName = match[1];
  const rawActivity = match[2];
  const activity = rawActivity.startsWith(".")
    ? `${packageName}/${rawActivity}`
    : `${packageName}/${rawActivity}`;
  return { packageName, activity };
}

export function parseForegroundOutput(output: string): ForegroundState {
  const priority = [
    /mResumedActivity/i,
    /mCurrentFocus/i,
    /mFocusedApp/i,
    /ActivityRecord/i
  ];

  const lines = output.split(/\r?\n/);
  for (const pattern of priority) {
    for (const line of lines) {
      if (!pattern.test(line)) {
        continue;
      }
      const component = parseComponent(line);
      if (component) {
        return component;
      }
    }
  }

  return { packageName: null, activity: null };
}

function findSize(text: string, labels: readonly string[]): DisplayInfo | null {
  for (const label of labels) {
    const match = text.match(
      new RegExp(`${label}[^\\d]*(\\d+)\\s*[x×]\\s*(\\d+)`, "i")
    );
    if (match) {
      return { width: Number(match[1]), height: Number(match[2]) };
    }
  }
  return null;
}

export function parseDisplayMetrics(sizeOutput: string, windowOutput = ""): DisplayInfo {
  const display =
    findSize(sizeOutput, ["Override size:", "Physical size:"]) ??
    findSize(windowOutput, ["cur=", "app=", "real=", "init="]);
  if (!display || display.width <= 0 || display.height <= 0) {
    throw new PhoneControlError(
      "OBSERVATION_FAILED",
      "ADB did not return usable display metrics."
    );
  }
  return display;
}

export function parseRotation(output: string): number {
  const match =
    output.match(/mCurrentRotation\s*=\s*(?:ROTATION_)?(\d)/i) ??
    output.match(/mRotation\s*=\s*(?:ROTATION_)?(\d)/i) ??
    output.match(/SurfaceOrientation\s*[:=]\s*(\d)/i) ??
    output.match(/\brotation\s*=\s*(?:ROTATION_)?(\d)/i);
  if (!match) {
    throw new PhoneControlError(
      "OBSERVATION_FAILED",
      "ADB did not return a usable display rotation."
    );
  }
  return Number(match[1]);
}

export function parseDisplaySnapshot(
  sizeOutput: string,
  windowOutput: string
): DisplaySnapshot {
  return {
    display: parseDisplayMetrics(sizeOutput, windowOutput),
    rotation: parseRotation(windowOutput)
  };
}

export function parsePngDimensions(bytes: Uint8Array): ScreenshotDimensions {
  const signature = Uint8Array.from([
    0x89,
    0x50,
    0x4e,
    0x47,
    0x0d,
    0x0a,
    0x1a,
    0x0a
  ]);
  if (bytes.length < 24 || !signature.every((value, index) => bytes[index] === value)) {
    throw new PhoneControlError(
      "OBSERVATION_FAILED",
      "ADB screenshot output was not a PNG."
    );
  }

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const chunkType = String.fromCharCode(
    bytes[12],
    bytes[13],
    bytes[14],
    bytes[15]
  );
  if (chunkType !== "IHDR") {
    throw new PhoneControlError(
      "OBSERVATION_FAILED",
      "ADB screenshot PNG did not contain an IHDR chunk."
    );
  }

  const width = view.getUint32(16);
  const height = view.getUint32(20);
  if (width === 0 || height === 0) {
    throw new PhoneControlError(
      "OBSERVATION_FAILED",
      "ADB screenshot PNG dimensions were empty."
    );
  }
  return { width, height };
}

export function parseAdbDevicesOutput(output: string): DeviceInfo[] {
  const devices: DeviceInfo[] = [];
  for (const line of output.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("List of devices attached")) {
      continue;
    }
    const fields = trimmed.split(/\s+/);
    if (fields.length < 2) {
      continue;
    }
    const state: DeviceInfo["state"] =
      fields[1] === "device" ||
      fields[1] === "offline" ||
      fields[1] === "unauthorized"
        ? fields[1]
        : fields[1] === "no" && fields[2] === "permissions"
          ? "no permissions"
          : "unknown";
    devices.push({
      serial: fields[0],
      state,
      authorized: state === "device"
    });
  }
  return devices;
}

export function hashText(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}
