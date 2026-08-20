import { mkdir, appendFile } from "node:fs/promises";
import { dirname } from "node:path";

import type {
  Keypress,
  PhoneAction,
  PointerEvent,
  ScrollAmount,
  ScrollDirection
} from "./types.js";

export type AuditOutcome = "success" | "failed" | "unknown";

export type SanitizedAuditAction =
  | { type: "click"; x: number; y: number }
  | { type: "click_coordinate"; x: number; y: number }
  | { type: "scroll"; direction: ScrollDirection; amount: ScrollAmount }
  | { type: "type"; textLength: number }
  | { type: "keypress"; key: Keypress };

export interface AuditLogEntry {
  at: number;
  serial: string;
  packageName: string | null;
  outcome: AuditOutcome;
  action: SanitizedAuditAction;
  pointerEvent?: PointerEvent;
  errorCode?: string;
}

export interface ActionAuditLogger {
  append(entry: AuditLogEntry): Promise<void>;
}

export function sanitizeAction(
  action: PhoneAction,
  coordinates?: { x: number; y: number }
): SanitizedAuditAction {
  switch (action.type) {
    case "click":
      return {
        type: "click",
        x: coordinates?.x ?? 0,
        y: coordinates?.y ?? 0
      };
    case "click_coordinate":
      return { type: "click_coordinate", x: action.x, y: action.y };
    case "scroll":
      return {
        type: "scroll",
        direction: action.direction,
        amount: action.amount
      };
    case "type":
      return { type: "type", textLength: action.text.length };
    case "keypress":
      return { type: "keypress", key: action.key };
  }
}

export class NdjsonActionLogger implements ActionAuditLogger {
  public constructor(private readonly filePath: string) {}

  public async append(entry: AuditLogEntry): Promise<void> {
    await mkdir(dirname(this.filePath), { recursive: true });
    await appendFile(this.filePath, `${JSON.stringify(entry)}\n`, "utf8");
  }
}
