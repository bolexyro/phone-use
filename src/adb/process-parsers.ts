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

export function parseForegroundForDisplay(
  output: string,
  displayId = 0
): ForegroundState {
  const priority = [
    /mResumedActivity/i,
    /mCurrentFocus/i,
    /mFocusedApp/i,
    /ActivityRecord/i,
    /topResumedActivity/i
  ];

  // Try to find display-specific block if multi-display output exists
  let scopedOutput = output;
  const displayBlockMatch = output.match(
    new RegExp(
      `(?:Display\\s*#${displayId}\\b|mDisplayId=${displayId}\\b|displayId=${displayId}\\b)([\\s\\S]*?)(?:(?:Display\\s*#\\d+\\b|mDisplayId=\\d+\\b|displayId=\\d+\\b)|$)`,
      "i"
    )
  );
  if (displayBlockMatch && displayBlockMatch[1]) {
    scopedOutput = displayBlockMatch[1];
  }

  const lines = scopedOutput.split(/\r?\n/);
  for (const pattern of priority) {
    for (const line of lines) {
      if (!pattern.test(line)) {
        continue;
      }
      const component = parseComponent(line);
      if (component) {
        return { ...component, displayId };
      }
    }
  }

  // Fallback to searching entire output if display-specific block was empty and displayId is 0
  if (displayId === 0 && scopedOutput !== output) {
    const allLines = output.split(/\r?\n/);
    for (const pattern of priority) {
      for (const line of allLines) {
        if (!pattern.test(line)) {
          continue;
        }
        const component = parseComponent(line);
        if (component) {
          return { ...component, displayId: 0 };
        }
      }
    }
  }

  return { packageName: null, activity: null, displayId };
}

export function parseForegroundOutput(output: string): ForegroundState {
  return parseForegroundForDisplay(output, 0);
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

export interface ParsedDisplayEntry {
  displayId: number;
  width: number;
  height: number;
  rotation: number;
}

export function parseDisplaysList(output: string): ParsedDisplayEntry[] {
  const displays: ParsedDisplayEntry[] = [];
  const displayBlocks = output.split(
    /(?=Display:\s*mDisplayId=\d+|Display\s+id\s+\d+:|DisplayDeviceInfo\{)/i
  );

  for (const block of displayBlocks) {
    const idMatch = block.match(
      /(?:mDisplayId=|Display\s+id\s+|uniqueId="[^"]*:)(\d+)/i
    );
    if (!idMatch) continue;
    const displayId = Number(idMatch[1]);

    const size =
      findSize(block, ["cur=", "app=", "real=", "init=", "Override size:", "Physical size:"]) ??
      (() => {
        const genericMatch = block.match(/(\d+)\s*[x×]\s*(\d+)/i);
        return genericMatch
          ? { width: Number(genericMatch[1]), height: Number(genericMatch[2]) }
          : null;
      })();

    if (!size || size.width <= 0 || size.height <= 0) continue;

    let rotation = 0;
    try {
      rotation = parseRotation(block);
    } catch {
      rotation = 0;
    }

    if (!displays.some((d) => d.displayId === displayId)) {
      displays.push({
        displayId,
        width: size.width,
        height: size.height,
        rotation
      });
    }
  }

  return displays;
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

export function parseDisplaySnapshotForId(
  sizeOutput: string,
  windowOutput: string,
  displayId = 0
): DisplaySnapshot {
  if (displayId === 0) {
    const snapshot = parseDisplaySnapshot(sizeOutput, windowOutput);
    return {
      display: { ...snapshot.display, displayId: 0 },
      rotation: snapshot.rotation,
      displayId: 0
    };
  }
  const displays = parseDisplaysList(windowOutput);
  const found = displays.find((d) => d.displayId === displayId);
  if (found) {
    return {
      display: { width: found.width, height: found.height, displayId },
      rotation: found.rotation,
      displayId
    };
  }
  return {
    display: { ...parseDisplayMetrics(sizeOutput, windowOutput), displayId },
    rotation: parseRotation(windowOutput),
    displayId
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
