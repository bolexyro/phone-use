import { EventEmitter } from "node:events";
import type { ChildProcess } from "node:child_process";

import { describe, expect, it, vi } from "vitest";

import { ElectronCursorOverlayController } from "../src/cursor-overlay-controller.js";

class FakeOverlayProcess extends EventEmitter {
  killed = false;
  exitCode: number | null = null;
  readonly stderr = new EventEmitter();

  kill(): boolean {
    this.killed = true;
    return true;
  }
}

describe("ElectronCursorOverlayController", () => {
  it("starts the viewer in overlay-only mode and reuses it", async () => {
    const child = new FakeOverlayProcess();
    const spawnProcess = vi.fn(
      (_file: string, _args: readonly string[], _options: unknown) => {
        queueMicrotask(() => child.emit("spawn"));
        return child as unknown as ChildProcess;
      }
    );
    const viewerMainPath = "C:\\workspace\\apps\\phone-viewer\\dist\\viewer\\main.js";
    const controller = new ElectronCursorOverlayController({
      platform: "win32",
      environment: {},
      resolveElectronPath: () => "C:\\workspace\\apps\\phone-viewer\\node_modules\\electron\\electron.exe",
      resolveViewerMainPath: () => viewerMainPath,
      spawnProcess
    });

    await Promise.all([controller.start(), controller.start()]);

    expect(spawnProcess).toHaveBeenCalledTimes(1);
    expect(spawnProcess.mock.calls[0]?.[0]).toContain("electron.exe");
    expect(spawnProcess.mock.calls[0]?.[1]).toEqual([
      viewerMainPath,
      "--overlay-only"
    ]);
    expect(spawnProcess.mock.calls[0]?.[2]).toMatchObject({
      shell: false,
      windowsHide: true,
      stdio: ["ignore", "ignore", "pipe"]
    });

    controller.stop();
    expect(child.killed).toBe(true);
  });

  it("can be disabled without spawning Electron", async () => {
    const spawnProcess = vi.fn();
    const controller = new ElectronCursorOverlayController({
      platform: "win32",
      environment: { PHONE_CONTROL_CURSOR_OVERLAY: "false" },
      spawnProcess
    });

    await controller.start();

    expect(spawnProcess).not.toHaveBeenCalled();
  });
});
