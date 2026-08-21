import { open, stat } from "node:fs/promises";

import type { PointerEvent } from "../types.js";

export interface SuccessfulClickAuditEvent {
  at: number;
  serial: string;
  packageName: string | null;
  pointerEvent: PointerEvent;
  phase: "start" | "result";
}

interface JsonRecord {
  [key: string]: unknown;
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

function isPointerEvent(value: unknown): value is PointerEvent {
  if (!isRecord(value)) return false;
  if (value.type !== "pointer" || value.coordinateSpace !== "display") return false;
  if (typeof value.observationId !== "string" || typeof value.serial !== "string") return false;
  if (typeof value.packageName !== "string" && value.packageName !== null) return false;
  if (!isNumber(value.displayWidth) || !isNumber(value.displayHeight) || !isNumber(value.timestamp)) return false;

  if (value.action === "click") {
    return isNumber(value.x) && isNumber(value.y);
  }
  if (value.action === "scroll") {
    return (
      typeof value.direction === "string" &&
      typeof value.amount === "string" &&
      isNumber(value.startX) &&
      isNumber(value.startY) &&
      isNumber(value.endX) &&
      isNumber(value.endY) &&
      isNumber(value.durationMs)
    );
  }
  return false;
}

export function parseAuditLine(line: string): SuccessfulClickAuditEvent | null {
  if (!line.trim()) return null;

  let value: unknown;
  try {
    value = JSON.parse(line) as unknown;
  } catch {
    return null;
  }
  if (!isRecord(value) || !isPointerEvent(value.pointerEvent)) {
    return null;
  }

  const isStart = value.phase === "start" && value.outcome === "pending";
  const isSuccessfulResult =
    (value.phase === "result" || value.phase === undefined) &&
    value.outcome === "success";
  if (!isStart && !isSuccessfulResult) return null;

  const action = value.action;
  if (!isRecord(action) || (action.type !== "click" && action.type !== "click_coordinate" && action.type !== "scroll")) {
    return null;
  }

  const packageName =
    typeof value.packageName === "string" || value.packageName === null
      ? value.packageName
      : value.pointerEvent.packageName;
  return {
    at: isNumber(value.at) ? value.at : value.pointerEvent.timestamp,
    serial: value.pointerEvent.serial,
    packageName,
    pointerEvent: value.pointerEvent,
    phase: isStart ? "start" : "result"
  };
}

export function parseAuditText(text: string): SuccessfulClickAuditEvent[] {
  return text
    .split(/\r?\n/)
    .map(parseAuditLine)
    .filter((event): event is SuccessfulClickAuditEvent => event !== null);
}

export interface NdjsonTailerOptions {
  startAtEnd?: boolean;
}

export class NdjsonTailer {
  readonly #filePath: string;
  readonly #startAtEnd: boolean;
  #offset = 0;
  #partial = "";
  #initialized = false;
  #startedObservationIds = new Set<string>();

  public constructor(filePath: string, options: NdjsonTailerOptions = {}) {
    this.#filePath = filePath;
    this.#startAtEnd = options.startAtEnd ?? true;
  }

  public async poll(): Promise<SuccessfulClickAuditEvent[]> {
    let fileSize: number;
    try {
      fileSize = (await stat(this.#filePath)).size;
    } catch (error) {
      if (isMissingFile(error)) {
        this.#initialized = true;
        return [];
      }
      throw error;
    }

    if (!this.#initialized) {
      this.#initialized = true;
      this.#offset = this.#startAtEnd ? fileSize : 0;
      if (this.#startAtEnd) return [];
    }

    if (fileSize < this.#offset) {
      this.#offset = 0;
      this.#partial = "";
      this.#startedObservationIds.clear();
    }
    if (fileSize === this.#offset) return [];

    const length = fileSize - this.#offset;
    const buffer = Buffer.alloc(length);
    const handle = await open(this.#filePath, "r");
    try {
      await handle.read(buffer, 0, length, this.#offset);
    } finally {
      await handle.close();
    }
    this.#offset = fileSize;

    const lines = `${this.#partial}${buffer.toString("utf8")}`.split(/\r?\n/);
    this.#partial = lines.pop() ?? "";
    return lines
      .map(parseAuditLine)
      .filter((event): event is SuccessfulClickAuditEvent => event !== null)
      .filter((event) => {
        const observationId = event.pointerEvent.observationId;
        if (event.phase === "start") {
          this.#startedObservationIds.add(observationId);
          return true;
        }
        if (this.#startedObservationIds.has(observationId)) {
          return false;
        }
        return true;
      });
  }
}

function isMissingFile(error: unknown): boolean {
  return isRecord(error) && error.code === "ENOENT";
}
