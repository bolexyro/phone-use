import { Buffer } from "node:buffer";

import { describe, expect, it } from "vitest";

import type { FixedAdbAdapter, SwipeGesture } from "../src/adb/adapter.js";
import type { AuditLogEntry } from "../src/audit-log.js";
import { PhoneControlError } from "../src/errors.js";
import { hashUiTree, parseUiAutomatorXml, parseBounds, boundsCenter } from "../src/ui-automator.js";
import { ObservationStore } from "../src/observation-store.js";
import { PhoneControlService } from "../src/service.js";
import { assertAllowedTarget } from "../src/policy-guard.js";
import type {
  DeviceInfo,
  DisplaySnapshot,
  ForegroundState,
  Keypress,
  ObservationCapture,
  PolicyProfile
} from "../src/types.js";

const PNG_2X3 = Buffer.from(
  "89504e470d0a1a0a0000000d4948445200000002000000030806000000",
  "hex"
);

const XML = `<hierarchy rotation="0"><node text="Calculate" content-desc="calc" resource-id="com.sec.android.app.popupcalculator:id/calculate" class="android.widget.Button" clickable="true" enabled="true" bounds="[10,20][110,80]"/><node text="" class="android.widget.TextView" checked="false" bounds="[0,0][2,2]"/></hierarchy>`;

const POLICY: PolicyProfile = {
  profile: "local",
  allowedApps: ["com.sec.android.app.popupcalculator"]
};

class MemoryAuditLogger {
  readonly entries: AuditLogEntry[] = [];

  async append(entry: AuditLogEntry): Promise<void> {
    this.entries.push(entry);
  }
}

class FakeAdb implements FixedAdbAdapter {
  readonly devices: DeviceInfo[] = [
    { serial: "phone-1", state: "device", authorized: true }
  ];
  foreground: ForegroundState = {
    packageName: "com.sec.android.app.popupcalculator",
    activity: "com.sec.android.app.popupcalculator/.MainActivity"
  };
  display: DisplaySnapshot = {
    display: { width: 1080, height: 2400 },
    rotation: 0
  };
  xml = XML;
  screenshot = Uint8Array.from(PNG_2X3);
  taps: Array<{ x: number; y: number }> = [];
  swipes: SwipeGesture[] = [];
  typed: string[] = [];
  keys: Keypress[] = [];
  launches: string[] = [];
  failNextAction: PhoneControlError | undefined;
  failLaunch: PhoneControlError | undefined;

  async listDevices(): Promise<readonly DeviceInfo[]> {
    return this.devices;
  }

  async getForeground(): Promise<ForegroundState> {
    return this.foreground;
  }

  async getDisplay(): Promise<DisplaySnapshot> {
    return this.display;
  }

  async dumpUiAutomatorXml(): Promise<string> {
    return this.xml;
  }

  async captureScreenshot(): Promise<Uint8Array> {
    return this.screenshot;
  }

  async launchApp(_serial: string, packageName: string): Promise<void> {
    if (this.failLaunch) throw this.failLaunch;
    this.launches.push(packageName);
    this.foreground = {
      packageName,
      activity: `${packageName}/.MainActivity`
    };
  }

  async tap(_serial: string, x: number, y: number): Promise<void> {
    if (this.failNextAction) throw this.failNextAction;
    this.taps.push({ x, y });
  }

  async swipe(_serial: string, gesture: SwipeGesture): Promise<void> {
    if (this.failNextAction) throw this.failNextAction;
    this.swipes.push(gesture);
  }

  async typeText(_serial: string, text: string): Promise<void> {
    if (this.failNextAction) throw this.failNextAction;
    this.typed.push(text);
  }

  async keypress(_serial: string, key: Keypress): Promise<void> {
    if (this.failNextAction) throw this.failNextAction;
    this.keys.push(key);
  }
}

function createService(
  adb: FakeAdb,
  logger?: MemoryAuditLogger,
  options: { now?: () => number; sleep?: (ms: number) => Promise<void> } = {}
): PhoneControlService {
  return new PhoneControlService({
    adb,
    policy: POLICY,
    environment: {},
    auditLogger: logger,
    ...options
  });
}

describe("UI Automator and observation core", () => {
  it("parses bounds/states and hashes UI trees independently of temporary refs", () => {
    expect(parseBounds("[10,20][110,80]")).toEqual({
      left: 10,
      top: 20,
      right: 110,
      bottom: 80
    });
    expect(boundsCenter({ left: 10, top: 20, right: 110, bottom: 80 })).toEqual({
      x: 60,
      y: 50
    });
    const first = parseUiAutomatorXml(XML, () => "first");
    const second = parseUiAutomatorXml(XML, () => "second");
    expect(first[0].states).toMatchObject({ clickable: true, enabled: true });
    expect(first[0].elementRef).not.toBe(second[0].elementRef);
    expect(hashUiTree(first)).toBe(hashUiTree(second));
  });

  it("keeps screenshot bytes unchanged in an observation", () => {
    const screenshot = Uint8Array.from(PNG_2X3);
    const capture: ObservationCapture = {
      serial: "phone-1",
      packageName: POLICY.allowedApps[0],
      activity: "pkg/.Main",
      display: { width: 1080, height: 2400 },
      rotation: 0,
      uiHash: "hash",
      screenshotDimensions: { width: 2, height: 3 },
      observedAt: 1,
      elements: [],
      screenshot
    };
    const observation = new ObservationStore(() => "opaque").create(capture);
    expect([...observation.screenshot]).toEqual([...screenshot]);
    expect(observation.screenshot).not.toBe(screenshot);
  });
});

describe("phone-control service safety", () => {
  it("opens an approved app from an unapproved foreground package", async () => {
    const adb = new FakeAdb();
    adb.foreground = {
      packageName: "com.sec.android.app.launcher",
      activity: "com.sec.android.app.launcher/.Launcher"
    };
    const service = createService(adb);

    const result = await service.openApp(POLICY.allowedApps[0]);

    expect(adb.launches).toEqual([POLICY.allowedApps[0]]);
    expect(result.data.observation.packageName).toBe(POLICY.allowedApps[0]);
  });

  it("denies a package outside the server-side policy", () => {
    expect(() => assertAllowedTarget(POLICY, "com.android.settings")).toThrowError(
      PhoneControlError
    );
    try {
      assertAllowedTarget(POLICY, "com.android.settings");
    } catch (error) {
      expect((error as PhoneControlError).code).toBe("FORBIDDEN_APP");
    }
  });

  it("returns pointer coordinates, logs the pointer event, and invalidates the old observation", async () => {
    const adb = new FakeAdb();
    const logger = new MemoryAuditLogger();
    const service = createService(adb, logger);
    const observed = await service.observe();
    const observationId = observed.data.observation.observationId;
    const elementRef = observed.data.observation.elements[0].elementRef;

    const result = await service.execute({
      observationId,
      action: { type: "click", elementRef }
    });

    expect(adb.taps).toEqual([{ x: 60, y: 50 }]);
    expect(result.data.pointerEvent).toMatchObject({
      x: 60,
      y: 50,
      observationId,
      serial: "phone-1",
      packageName: POLICY.allowedApps[0],
      displayWidth: 1080,
      displayHeight: 2400,
      timestamp: expect.any(Number)
    });
    expect(logger.entries[0]).toMatchObject({
      outcome: "success",
      pointerEvent: { x: 60, y: 50 }
    });
    expect(service.observationStore.get(observationId)).toBeUndefined();
  });

  it("rejects coordinates outside the current display", async () => {
    const adb = new FakeAdb();
    const service = createService(adb);
    const observed = await service.observe();
    await expect(
      service.execute({
        observationId: observed.data.observation.observationId,
        action: { type: "click_coordinate", x: 1080, y: 0 }
      })
    ).rejects.toMatchObject({ code: "INVALID_COORDINATE" });
    expect(adb.taps).toHaveLength(0);
  });

  it("returns STALE_OBSERVATION for a changed UI tree or rotation", async () => {
    const adb = new FakeAdb();
    const service = createService(adb);
    const first = await service.observe();
    adb.xml = XML.replace("Calculate", "Changed");
    await expect(
      service.execute({
        observationId: first.data.observation.observationId,
        action: { type: "click_coordinate", x: 10, y: 10 }
      })
    ).rejects.toMatchObject({ code: "STALE_OBSERVATION" });

    const second = await service.observe();
    adb.display.rotation = 1;
    await expect(
      service.execute({
        observationId: second.data.observation.observationId,
        action: { type: "click_coordinate", x: 10, y: 10 }
      })
    ).rejects.toMatchObject({ code: "STALE_OBSERVATION" });
  });

  it("checks the post-launch foreground and rejects a failed transition", async () => {
    const adb = new FakeAdb();
    adb.launchApp = async () => {
      adb.launches.push("wrong");
      adb.foreground = {
        packageName: "com.android.settings",
        activity: "com.android.settings/.Settings"
      };
    };
    const service = createService(adb);
    await expect(service.openApp(POLICY.allowedApps[0])).rejects.toMatchObject({
      code: "FORBIDDEN_APP"
    });
  });

  it("returns APP_LAUNCH_FAILED when the typed launch operation fails", async () => {
    const adb = new FakeAdb();
    adb.failLaunch = new PhoneControlError(
      "APP_LAUNCH_FAILED",
      "launcher rejected the request"
    );
    const service = createService(adb);
    await expect(service.openApp(POLICY.allowedApps[0])).rejects.toMatchObject({
      code: "APP_LAUNCH_FAILED"
    });
  });

  it("preserves unknown outcomes on action timeouts and does not retry", async () => {
    const adb = new FakeAdb();
    const logger = new MemoryAuditLogger();
    const service = createService(adb, logger);
    const observed = await service.observe();
    adb.failNextAction = new PhoneControlError(
      "ADB_TIMEOUT",
      "timed out",
      { outcome: "unknown" }
    );

    await expect(
      service.execute({
        observationId: observed.data.observation.observationId,
        action: { type: "keypress", key: "BACK" }
      })
    ).rejects.toMatchObject({ code: "ADB_TIMEOUT" });
    expect(logger.entries[0]).toMatchObject({ outcome: "unknown", errorCode: "ADB_TIMEOUT" });
    expect(adb.keys).toHaveLength(0);
    expect(service.observationStore.get(observed.data.observation.observationId)).toBeUndefined();
  });

  it("returns a bounded wait timeout", async () => {
    const adb = new FakeAdb();
    const service = createService(adb, undefined, { now: () => 100 });
    const observed = await service.observe();
    await expect(
      service.waitFor(
        observed.data.observation.observationId,
        { type: "visible_text", text: "never-visible" },
        { timeoutMs: 0, pollIntervalMs: 0 }
      )
    ).rejects.toMatchObject({ code: "WAIT_TIMEOUT" });
  });

  it("matches successful foreground, text, resource, and UI-tree wait conditions", async () => {
    const adb = new FakeAdb();
    const service = createService(adb);
    const baseline = await service.observe();
    const observationId = baseline.data.observation.observationId;

    const foreground = await service.waitFor(
      observationId,
      {
        type: "foreground_package",
        packageName: POLICY.allowedApps[0]
      },
      { timeoutMs: 0 }
    );
    expect(foreground.data.observation.observationId).not.toBe(observationId);

    const visibleText = await service.waitFor(
      observationId,
      { type: "visible_text", text: "Calculate" },
      { timeoutMs: 0 }
    );
    expect(visibleText.data.observation.elements[0].text).toBe("Calculate");

    const visibleResource = await service.waitFor(
      observationId,
      {
        type: "visible_resource_id",
        resourceId: "com.sec.android.app.popupcalculator:id/calculate"
      },
      { timeoutMs: 0 }
    );
    expect(visibleResource.data.observation.observationId).not.toBe(observationId);

    adb.xml = XML.replace("Calculate", "Changed");
    const changed = await service.waitFor(
      observationId,
      { type: "ui_tree_changed" },
      { timeoutMs: 0 }
    );
    expect(changed.data.observation.observationId).not.toBe(observationId);
  });

  it("executes the approved scroll, type, and keypress actions", async () => {
    const adb = new FakeAdb();
    const logger = new MemoryAuditLogger();
    const service = createService(adb, logger);

    const scrollObservation = await service.observe();
    await service.execute({
      observationId: scrollObservation.data.observation.observationId,
      action: { type: "scroll", direction: "up", amount: "medium" }
    });
    expect(adb.swipes[0]).toMatchObject({ durationMs: 350 });

    const typeObservation = await service.observe();
    const typedResult = await service.execute({
      observationId: typeObservation.data.observation.observationId,
      action: { type: "type", text: "secret value" }
    });
    expect(typedResult.data.textLength).toBe(12);
    expect(adb.typed).toEqual(["secret value"]);

    const keyObservation = await service.observe();
    await service.execute({
      observationId: keyObservation.data.observation.observationId,
      action: { type: "keypress", key: "ENTER" }
    });
    expect(adb.keys).toEqual(["ENTER"]);
    expect(JSON.stringify(logger.entries)).not.toContain("secret value");
  });
});
