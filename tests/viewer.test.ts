import { appendFile, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

import { describe, expect, it } from "vitest";

import { loadViewerConfig } from "../src/viewer/config.js";
import { mapDevicePointToOverlay } from "../src/viewer/coordinate-mapping.js";
import { NdjsonTailer, parseAuditLine, parseAuditText } from "../src/viewer/ndjson.js";
import { buildOverlayHtml } from "../src/viewer/overlay-html.js";
import { buildScrcpyArgs } from "../src/viewer/scrcpy-args.js";
import { resolveScrcpyPath } from "../src/viewer/scrcpy-path.js";
import { fitDipRectToWorkArea } from "../src/viewer/window-geometry.js";
import {
  createGeometryRecoveryState,
  updateGeometryRecovery
} from "../src/viewer/geometry-recovery.js";
import {
  parseClientWindowRect,
  parseWindowChangedEvent
} from "../src/viewer/win32-window.js";

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
const pendingClickLine = clickLine.replace(
  '"outcome":"success"',
  '"outcome":"pending","phase":"start"'
);
const completedClickLine = clickLine.replace(
  '"outcome":"success"',
  '"outcome":"success","phase":"result"'
);

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

  it("parses pointer-start entries before their completed result", () => {
    expect(parseAuditLine(pendingClickLine)).toMatchObject({ phase: "start" });
    expect(parseAuditLine(completedClickLine)).toMatchObject({ phase: "result" });
  });

  it("does not replay a completed result after a pointer-start entry", async () => {
    const directory = await mkdtemp(join(tmpdir(), "phone-control-viewer-start-"));
    const logPath = join(directory, "actions.ndjson");
    try {
      await writeFile(logPath, `${pendingClickLine}\n${completedClickLine}\n`, "utf8");
      const tailer = new NdjsonTailer(logPath, { startAtEnd: false });
      const events = await tailer.poll();
      expect(events).toHaveLength(1);
      expect(events[0].phase).toBe("start");
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
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
    expect(config.cursorDurationMs).toBe(80);
  });

  it("keeps scrcpy aligned while passing normal input through", () => {
    expect(buildScrcpyArgs("phone-1", {
      x: 60,
      y: 60,
      width: 432,
      height: 936,
      cursorDurationMs: 700,
      auditLogPath: "logs/actions.ndjson",
      auditPollIntervalMs: 100
    })).toEqual(expect.arrayContaining([
      "--render-fit=stretched",
      "--window-x=60",
      "--window-y=60",
      "--window-width=432",
      "--window-height=936",
      "--keyboard=sdk",
      "--mouse=sdk"
    ]));
  });

  it("keeps the cursor visible and animates it instead of fading it out", () => {
    const html = buildOverlayHtml(220);
    expect(html).toContain("opacity: 1");
    expect(html).toContain("left 220ms");
    expect(html).toContain("window.innerWidth");
    expect(html).toContain("window.innerHeight");
    expect(html).toContain("HOTSPOT_X");
    expect(html).toContain("HOTSPOT_Y");
    expect(html).not.toContain("Math.random()");
    expect(html).toContain("cursor-click");
    expect(html).not.toContain("hideTimer");
    expect(html).not.toContain("cursor-pop");
  });

  it("fits the requested mirror into a smaller work area without changing its aspect ratio", () => {
    expect(
      fitDipRectToWorkArea(
        { x: 40, y: 40, width: 288, height: 624 },
        { x: 0, y: 0, width: 853, height: 533 },
        8
      )
    ).toEqual({ x: 40, y: 40, width: 223, height: 485 });
  });

  it("parses the native scrcpy client rectangle and rejects invalid responses", () => {
    expect(parseClientWindowRect('{"ok":true,"x":100,"y":200,"width":432,"height":900}')).toEqual({
      x: 100,
      y: 200,
      width: 432,
      height: 900
    });
    expect(parseClientWindowRect('{"ok":false}')).toBeNull();
  });

  it("parses native window movement notifications", () => {
    expect(
      parseWindowChangedEvent('{"event":"window-changed","processId":4321}')
    ).toEqual({ processId: 4321 });
    expect(parseWindowChangedEvent('{"ok":true,"x":100}')).toBeNull();
    expect(
      parseWindowChangedEvent('{"event":"window-changed","processId":0}')
    ).toBeNull();
  });

  it("hides the overlay while geometry is moving and reattaches after stable samples", () => {
    const first = { x: 100, y: 200, width: 288, height: 624 };
    const second = { x: 140, y: 240, width: 288, height: 624 };
    let state = createGeometryRecoveryState();

    const firstSample = updateGeometryRecovery(state, first, 2);
    state = firstSample.state;
    expect(firstSample.attached).toBe(false);

    const stableSample = updateGeometryRecovery(state, first, 2);
    state = stableSample.state;
    expect(stableSample.attached).toBe(true);

    const movedSample = updateGeometryRecovery(state, second, 2);
    state = movedSample.state;
    expect(movedSample.changed).toBe(true);
    expect(movedSample.attached).toBe(false);

    const recoveredSample = updateGeometryRecovery(state, second, 2);
    expect(recoveredSample.attached).toBe(true);
  });

  it("resets recovery when the native window is unavailable", () => {
    const attached = updateGeometryRecovery(
      createGeometryRecoveryState(),
      { x: 1, y: 2, width: 3, height: 4 },
      1
    ).state;
    const unavailable = updateGeometryRecovery(attached, undefined);
    expect(unavailable.attached).toBe(false);
    expect(unavailable.state.stableSamples).toBe(0);
  });
});
