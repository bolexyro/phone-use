import type { PhoneAssistantToolResult } from "./dhd-tools.js";

export type CompanionJsonValue =
  | null
  | boolean
  | number
  | string
  | CompanionJsonValue[]
  | { [key: string]: CompanionJsonValue };

export const COMPANION_TOOL_EVENT_TYPE = "dhd_tool_call" as const;

export interface CompanionToolCallStartedEvent {
  type: typeof COMPANION_TOOL_EVENT_TYPE;
  phase: "started";
  callId: string;
  tool: string;
  arguments: CompanionJsonValue;
  rawArguments?: string;
  timestamp: number;
}

export interface CompanionToolCallCompletedEvent {
  type: typeof COMPANION_TOOL_EVENT_TYPE;
  phase: "completed";
  callId: string;
  tool: string;
  result?: PhoneAssistantToolResult;
  error?: string;
  completedAt: number;
}

export type CompanionToolCallEvent =
  | CompanionToolCallStartedEvent
  | CompanionToolCallCompletedEvent;

/** Public contract name used by worker/dashboard integrations. */
export type CompanionToolEvent = CompanionToolCallEvent;

export function isCompanionToolCallEvent(
  value: unknown,
): value is CompanionToolCallEvent {
  if (!value || typeof value !== "object") return false;
  const event = value as Record<string, unknown>;
  return (
    event.type === COMPANION_TOOL_EVENT_TYPE &&
    (event.phase === "started" || event.phase === "completed") &&
    typeof event.callId === "string" &&
    event.callId.length > 0 &&
    typeof event.tool === "string" &&
    event.tool.length > 0 &&
    (event.phase === "started"
      ? typeof event.timestamp === "number"
      : typeof event.completedAt === "number")
  );
}

/**
 * The dashboard may run the worker as a direct command without an IPC parent.
 * Diagnostics are intentionally best-effort: an unavailable or broken event
 * channel must never change phone-tool behavior.
 */
export function emitCompanionToolCallEvent(
  event: CompanionToolCallEvent,
): void {
  if (typeof process.send !== "function" || process.connected === false) return;
  try {
    process.send(event, () => undefined);
  } catch {
    // The worker must continue even when the dashboard has gone away.
  }
}
