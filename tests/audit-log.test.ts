import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

import { NdjsonActionLogger, sanitizeAction } from "../src/audit-log.js";

describe("NDJSON action audit logging", () => {
  it("writes one line and redacts typed text values", async () => {
    const directory = await mkdtemp(join(tmpdir(), "phone-control-audit-"));
    const logPath = join(directory, "actions.ndjson");
    try {
      const logger = new NdjsonActionLogger(logPath);
      await logger.append({
        at: 1,
        serial: "phone-1",
        packageName: "com.example.app",
        outcome: "success",
        action: sanitizeAction({ type: "type", text: "secret value" })
      });

      const text = await readFile(logPath, "utf8");
      expect(text.trim().split("\n")).toHaveLength(1);
      expect(text).not.toContain("secret value");
      expect(JSON.parse(text)).toMatchObject({ action: { type: "type", textLength: 12 } });
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
});
