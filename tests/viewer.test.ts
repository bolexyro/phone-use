import { appendFile, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

import { describe, expect, it } from "vitest";

import { loadViewerConfig } from "../src/viewer/config.js";
import { mapDevicePointToOverlay } from "../src/viewer/coordinate-mapping.js";
import { NdjsonTailer, parseAuditLine, parseAuditText } from "../src/viewer/ndjson.js";
import { buildScrcpyArgs } from "../src/viewer/scrcpy-args.js";
import { resolveScrcpyPath } from "../src/viewer/scrcpy-path.js";

const clickLine = JSON.stringify({
  at: 123,
  serial: "phone-1",
  packageName: "com.sec.android.app.popupcalculator",
  outcome: "success",
  action: { type: "click", x: 540, y: 1170 },
  pointerEvent: {
    type: "pointer",
    action: "click",
    x: 540,
    y: 1170,
    coordinateSpace: "display",
    observationId: "obs_1",
    serial: "phone-1",
    packageName: "com.sec.android.app.popupcalculator",
    displayWidth: 1080,
    displayHeight: 2340,
    timestamp: 123
  }
});

describe("visible cursor viewer helpers", () => {
  it("maps device coordinates using event display dimensions", () => {
    const mapped = mapDevicePointToOverlay(
      {
        x: 540,
        y: 1170,
        displayWidth: 1080,
        displayHeight: 2340
      },
      { x: 60, y: 60, width: 432, height: 936 }
    );
    expect(mapped).toEqual({ localX: 216, localY: 468, screenX: 276, screenY: 528 });
  });

  it("parses only successful click entries with complete pointer metadata", () => {
    const event = parseAuditLine(clickLine);
    expect(event).toMatchObject({
      at: 123,
      serial: "phone-1",
      pointerEvent: { displayWidth: 1080, displayHeight: 2340 }
    });
    expect(parseAuditLine(clickLine.replace('"outcome":"success"', '"outcome":"failed"'))).toBeNull();
    expect(parseAuditText(`${clickLine}\nnot-json\n`)).toHaveLength(1);
  });

  it("tails complete and split NDJSON lines without replaying old entries", async () => {
    const directory = await mkdtemp(join(tmpdir(), "phone-control-viewer-"));
    const logPath = join(directory, "actions.ndjson");
    try {
      await writeFile(logPath, `${clickLine}\n`, "utf8");
      const tailer = new NdjsonTailer(logPath, { startAtEnd: false });
      expect(await tailer.poll()).toHaveLength(1);
      expect(await tailer.poll()).toHaveLength(0);

      await appendFile(logPath, clickLine.slice(0, 80), "utf8");
      expect(await tailer.poll()).toHaveLength(0);
      await appendFile(logPath, `${clickLine.slice(80)}\n`, "utf8");
      expect(await tailer.poll()).toHaveLength(1);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });

  it("uses the requested viewer path order and stable S23 defaults", () => {
    const cwd = resolve("viewer-path-test");
    const configured = resolve(cwd, "custom", "scrcpy.exe");
    expect(
      resolveScrcpyPath({
        cwd,
        platform: "win32",
        env: { PHONE_CONTROL_SCRCPY_PATH: configured },
        fileExists: (candidate) => candidate === configured
      })
    ).toBe(configured);

    const config = loadViewerConfig({});
    expect(config.width).toBe(432);
    expect(config.height).toBe(936);
    expect(config.cursorDurationMs).toBe(700);
  });

  it("keeps scrcpy visual-only and aligned to the configured rectangle", () => {
    expect(buildScrcpyArgs("phone-1", {
      x: 60,
      y: 60,
      width: 432,
      height: 936,
      cursorDurationMs: 700,
      auditLogPath: "logs/actions.ndjson",
      auditPollIntervalMs: 100
    })).toEqual(expect.arrayContaining([
      "--window-borderless",
      "--render-fit=stretched",
      "--window-x=60",
      "--window-y=60",
      "--window-width=432",
      "--window-height=936",
      "--always-on-top",
      "--mouse=disabled"
    ]));
  });
});
