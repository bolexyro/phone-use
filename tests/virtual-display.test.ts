import { EventEmitter } from "node:events";
import type { ChildProcess } from "node:child_process";

import { describe, expect, it } from "vitest";

import type { FixedAdbAdapter } from "../src/adb/adapter.js";
import { VirtualDisplayManager } from "../src/virtual-display.js";
import type {
  DeviceInfo,
  DisplaySnapshot,
  ForegroundState,
  Keypress
} from "../src/types.js";

class FakeChild extends EventEmitter {
  killed = false;
  killCalls = 0;
  readonly stderr = new EventEmitter();

  kill(): boolean {
    this.killed = true;
    this.killCalls += 1;
    return true;
  }
}

class FakeAdb implements FixedAdbAdapter {
  displays = [{ displayId: 0, width: 1080, height: 2400, rotation: 0 }];
  failDisplayIds = new Set<number>();
  displayLaunches: Array<{
    packageName: string;
    displayId: number;
    multipleTask: boolean;
  }> = [];

  async listDevices(): Promise<readonly DeviceInfo[]> {
    return [{ serial: "phone-1", state: "device", authorized: true }];
  }

  async getApiLevel(): Promise<number> {
    return 33;
  }

  async listDisplays(): Promise<readonly typeof this.displays[number][]> {
    return this.displays.map((display) => ({ ...display }));
  }

  async getForeground(
    _serial: string,
    displayId = 0
  ): Promise<ForegroundState> {
    return { packageName: null, activity: null, displayId };
  }

  async getDisplay(_serial: string, displayId = 0): Promise<DisplaySnapshot> {
    if (this.failDisplayIds.has(displayId)) {
      throw new Error(`display ${displayId} unavailable`);
    }
    const display = this.displays.find((candidate) => candidate.displayId === displayId) ?? {
      displayId,
      width: 1080,
      height: 2400,
      rotation: 0
    };
    return {
      display: { width: display.width, height: display.height, displayId },
      rotation: display.rotation,
      displayId
    };
  }

  async dumpUiAutomatorXml(): Promise<string> {
    return "";
  }

  async captureScreenshot(): Promise<Uint8Array> {
    return Uint8Array.of();
  }

  async launchApp(): Promise<void> {}
  async launchAppOnDisplay(
    _serial: string,
    packageName: string,
    displayId: number,
    options: { multipleTask?: boolean } = {}
  ): Promise<void> {
    this.displayLaunches.push({
      packageName,
      displayId,
      multipleTask: options.multipleTask === true
    });
  }
  async tap(): Promise<void> {}
  async swipe(): Promise<void> {}
  async typeText(): Promise<void> {}
  async keypress(_serial: string, _key: Keypress): Promise<void> {}
}

function asChildProcess(child: FakeChild): ChildProcess {
  return child as unknown as ChildProcess;
}

describe("VirtualDisplayManager", () => {
  it("reuses a package session by default", async () => {
    const adb = new FakeAdb();
    const children: FakeChild[] = [];
    const manager = new VirtualDisplayManager(adb, {
      scrcpyPath: "scrcpy.exe",
      spawn: (_file, _args, _options) => {
        const child = new FakeChild();
        children.push(child);
        adb.displays = [
          ...adb.displays,
          { displayId: 2, width: 420, height: 936, rotation: 0 }
        ];
        return asChildProcess(child);
      },
      sleep: async () => {}
    });

    const first = await manager.launch("phone-1", "com.example.app");
    const second = await manager.launch("phone-1", "com.example.app");

    expect(second).toEqual(first);
    expect(children).toHaveLength(1);
    expect(manager.sessions.map((session) => session.displayId)).toEqual([2]);
  });

  it("creates a distinct display for newInstance", async () => {
    const adb = new FakeAdb();
    const children: FakeChild[] = [];
    let nextDisplayId = 2;
    const manager = new VirtualDisplayManager(adb, {
      scrcpyPath: "scrcpy.exe",
      spawn: (_file, _args, _options) => {
        const child = new FakeChild();
        children.push(child);
        const displayId = nextDisplayId++;
        adb.displays = [
          ...adb.displays,
          { displayId, width: 420, height: 936, rotation: 0 }
        ];
        return asChildProcess(child);
      },
      sleep: async () => {}
    });

    const first = await manager.launch("phone-1", "com.example.app");
    const second = await manager.launch("phone-1", "com.example.app", {
      newInstance: true
    });

    expect(first.displayId).toBe(2);
    expect(second.displayId).toBe(3);
    expect(manager.getSessionsByPackage("com.example.app").map((session) => session.displayId)).toEqual([
      2,
      3
    ]);
    expect(children).toHaveLength(2);
    expect(adb.displayLaunches).toEqual([
      {
        packageName: "com.example.app",
        displayId: 3,
        multipleTask: true
      }
    ]);
  });

  it("serializes concurrent launches so each detects its own display", async () => {
    const adb = new FakeAdb();
    const children: FakeChild[] = [];
    let nextDisplayId = 2;
    const manager = new VirtualDisplayManager(adb, {
      scrcpyPath: "scrcpy.exe",
      spawn: (_file, _args, _options) => {
        const child = new FakeChild();
        children.push(child);
        const displayId = nextDisplayId++;
        adb.displays = [
          ...adb.displays,
          { displayId, width: 420, height: 936, rotation: 0 }
        ];
        return asChildProcess(child);
      },
      sleep: async () => {}
    });

    const [first, second] = await Promise.all([
      manager.launch("phone-1", "com.example.app", { newInstance: true }),
      manager.launch("phone-1", "com.example.app", { newInstance: true })
    ]);

    expect([first.displayId, second.displayId]).toEqual([2, 3]);
    expect(children).toHaveLength(2);
    expect(manager.sessions.map((session) => session.displayId)).toEqual([2, 3]);
  });

  it("kills a failed second launch and preserves the existing session", async () => {
    const adb = new FakeAdb();
    const children: FakeChild[] = [];
    let nextDisplayId = 2;
    const manager = new VirtualDisplayManager(adb, {
      scrcpyPath: "scrcpy.exe",
      spawn: (_file, _args, _options) => {
        const child = new FakeChild();
        children.push(child);
        const displayId = nextDisplayId++;
        adb.displays = [
          ...adb.displays,
          { displayId, width: 420, height: 936, rotation: 0 }
        ];
        if (displayId === 3) adb.failDisplayIds.add(displayId);
        return asChildProcess(child);
      },
      sleep: async () => {}
    });

    await manager.launch("phone-1", "com.example.app");
    await expect(
      manager.launch("phone-1", "com.example.app", { newInstance: true })
    ).rejects.toThrow("display 3 unavailable");

    expect(children[1].killed).toBe(true);
    expect(manager.sessions.map((session) => session.displayId)).toEqual([2]);
  });
});
