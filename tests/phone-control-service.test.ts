import { Buffer } from "node:buffer";

import { describe, expect, it } from "vitest";

import type { FixedAdbAdapter, SwipeGesture } from "../src/adb/adapter.js";
import type { AuditLogEntry } from "../src/audit-log.js";
import { PhoneControlError } from "../src/errors.js";
import { hashUiTree, parseUiAutomatorXml, parseBounds, boundsCenter } from "../src/ui-automator.js";
import { ObservationStore } from "../src/observation-store.js";
import { PhoneControlService } from "../src/service.js";
import { assertAllowedTarget } from "../src/policy-guard.js";
import { VirtualDisplayManager } from "../src/virtual-display.js";
import type {
  DeviceInfo,
  DisplaySnapshot,
  ForegroundState,
  Keypress,
  ObservationCapture,
  PolicyProfile,
  VirtualDisplaySession
} from "../src/types.js";

const PNG_2X3 = Buffer.from(
  "89504e470d0a1a0a0000000d4948445200000002000000030806000000",
  "hex"
);

const XML = `<hierarchy rotation="0"><node text="Calculate" content-desc="calc" resource-id="com.sec.android.app.popupcalculator:id/calculate" class="android.widget.Button" clickable="true" enabled="true" bounds="[10,20][110,80]"/><node text="" class="android.widget.TextView" checked="false" bounds="[0,0][2,2]"/></hierarchy>`;
const TARGET_XML = `<hierarchy rotation="0"><node text="Pay" content-desc="pay" resource-id="com.paystack:id/pay" class="android.widget.Button" clickable="true" enabled="true" bounds="[10,20][110,80]"/><node text="Live ticker 29:33" content-desc="live" resource-id="com.paystack:id/timer" class="android.widget.TextView" bounds="[0,0][200,30]"/><node text="$10.00" resource-id="com.paystack:id/amount" class="android.widget.TextView" bounds="[0,40][200,70]"/></hierarchy>`;
const CHOWDECK_XML = `<hierarchy rotation="0"><node resource-id="com.chowdeck:id/root" class="android.widget.FrameLayout" bounds="[0,0][300,900]"><node resource-id="com.chowdeck:id/feed" class="androidx.recyclerview.widget.RecyclerView" scrollable="true" bounds="[0,0][300,900]"><node text="Restaurant A" class="android.view.View" bounds="[0,650][300,800]"/></node><node resource-id="com.chowdeck:id/offline_sheet" class="android.widget.FrameLayout" bounds="[0,600][300,900]"><node text="Try again" content-desc="Try again" class="android.widget.Button" clickable="true" enabled="true" bounds="[40,700][260,780]"/></node></node></hierarchy>`;

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
  apiLevel = 33;
  displays = [{ displayId: 0, width: 1080, height: 2400, rotation: 0 }];
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
  taps: Array<{ x: number; y: number; displayId: number }> = [];
  swipes: Array<SwipeGesture & { displayId: number }> = [];
  typed: Array<{ text: string; displayId: number }> = [];
  keys: Array<{ key: Keypress; displayId: number }> = [];
  launches: string[] = [];
  failNextAction: PhoneControlError | undefined;
  failLaunch: PhoneControlError | undefined;

  async listDevices(): Promise<readonly DeviceInfo[]> {
    return this.devices;
  }

  async getApiLevel(): Promise<number> {
    return this.apiLevel;
  }

  async listDisplays(): Promise<
    readonly {
      displayId: number;
      width: number;
      height: number;
      rotation: number;
    }[]
  > {
    return this.displays;
  }

  async getForeground(_serial: string, displayId = 0): Promise<ForegroundState> {
    return { ...this.foreground, displayId };
  }

  async getDisplay(_serial: string, displayId = 0): Promise<DisplaySnapshot> {
    return {
      display: { ...this.display.display, displayId },
      rotation: this.display.rotation,
      displayId
    };
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

  async tap(_serial: string, x: number, y: number, displayId = 0): Promise<void> {
    if (this.failNextAction) throw this.failNextAction;
    this.taps.push({ x, y, displayId });
  }

  async swipe(_serial: string, gesture: SwipeGesture, displayId = 0): Promise<void> {
    if (this.failNextAction) throw this.failNextAction;
    this.swipes.push({ ...gesture, displayId });
  }

  async typeText(_serial: string, text: string, displayId = 0): Promise<void> {
    if (this.failNextAction) throw this.failNextAction;
    this.typed.push({ text, displayId });
  }

  async keypress(_serial: string, key: Keypress, displayId = 0): Promise<void> {
    if (this.failNextAction) throw this.failNextAction;
    this.keys.push({ key, displayId });
  }
}

class FakeVirtualDisplayManager extends VirtualDisplayManager {
  readonly launchedPackages: string[] = [];
  failLaunch = false;
  nextDisplayId = 2;
  private readonly fakeAdb?: FakeAdb;
  private fakeSessions = new Map<number, VirtualDisplaySession>();

  constructor(adb: FakeAdb) {
    super(adb);
    this.fakeAdb = adb;
  }

  override get sessions(): readonly VirtualDisplaySession[] {
    return Array.from(this.fakeSessions.values());
  }

  override getSessionByPackage(
    packageName: string
  ): VirtualDisplaySession | undefined {
    for (const s of this.fakeSessions.values()) {
      if (s.packageName === packageName) return s;
    }
    return undefined;
  }

  override async launch(
    _serial: string,
    packageName: string
  ): Promise<VirtualDisplaySession> {
    if (this.failLaunch) {
      throw new PhoneControlError(
        "VIRTUAL_DISPLAY_FAILED",
        "Failed to create virtual display"
      );
    }
    this.launchedPackages.push(packageName);
    if (this.fakeAdb) {
      this.fakeAdb.foreground = {
        packageName,
        activity: `${packageName}/.MainActivity`
      };
    }
    const session: VirtualDisplaySession = {
      displayId: this.nextDisplayId,
      packageName,
      activity: `${packageName}/.MainActivity`,
      width: 1080,
      startedAt: Date.now()
    };
    this.fakeSessions.set(this.nextDisplayId, session);
    return session;
  }

  override close(target: {
    displayId?: number;
    packageName?: string;
  }): boolean {
    if (target.displayId !== undefined) {
      return this.fakeSessions.delete(target.displayId);
    }
    if (target.packageName !== undefined) {
      for (const [id, s] of this.fakeSessions.entries()) {
        if (s.packageName === target.packageName) {
          this.fakeSessions.delete(id);
          return true;
        }
      }
    }
    return false;
  }
}

function createService(
  adb: FakeAdb,
  logger?: MemoryAuditLogger,
  options: {
    now?: () => number;
    sleep?: (ms: number) => Promise<void>;
    virtualDisplayManager?: VirtualDisplayManager;
  } = {}
): PhoneControlService {
  return new PhoneControlService({
    adb,
    policy: POLICY,
    environment: { NODE_ENV: "test" },
    virtualDisplayManager:
      options.virtualDisplayManager ?? new FakeVirtualDisplayManager(adb),
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

    const nested = parseUiAutomatorXml(CHOWDECK_XML);
    const retryIndex = nested.findIndex((element) => element.text === "Try again");
    const sheetIndex = nested.findIndex(
      (element) => element.resourceId === "com.chowdeck:id/offline_sheet"
    );
    expect(nested[retryIndex].parentIndex).toBe(sheetIndex);
    expect(nested[sheetIndex].parentIndex).toBe(0);
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
  it("opens an approved app in a virtual display by default on Android 10+", async () => {
    const adb = new FakeAdb();
    adb.foreground = {
      packageName: "com.sec.android.app.launcher",
      activity: "com.sec.android.app.launcher/.Launcher"
    };
    const vdManager = new FakeVirtualDisplayManager(adb);
    const service = createService(adb, undefined, {
      virtualDisplayManager: vdManager
    });

    const result = await service.openApp(POLICY.allowedApps[0]);

    expect(vdManager.launchedPackages).toEqual([POLICY.allowedApps[0]]);
    expect(result.data.observation.packageName).toBe(POLICY.allowedApps[0]);
    expect(result.data.observation.displayId).toBe(2);
  });

  it("fails with VIRTUAL_DISPLAY_UNSUPPORTED when useVirtualDisplay is true and Android API < 29", async () => {
    const adb = new FakeAdb();
    adb.apiLevel = 28; // Android 9 Pie
    const service = createService(adb);

    await expect(
      service.openApp(POLICY.allowedApps[0], { useVirtualDisplay: true })
    ).rejects.toMatchObject({
      code: "VIRTUAL_DISPLAY_UNSUPPORTED"
    });
  });

  it("launches on primary display 0 when useVirtualDisplay is explicitly false on Android < 29", async () => {
    const adb = new FakeAdb();
    adb.apiLevel = 28;
    const service = createService(adb);

    const result = await service.openApp(POLICY.allowedApps[0], {
      useVirtualDisplay: false
    });

    expect(adb.launches).toEqual([POLICY.allowedApps[0]]);
    expect(result.data.observation.packageName).toBe(POLICY.allowedApps[0]);
    expect(result.data.observation.displayId).toBe(0);
  });

  it("fails with VIRTUAL_DISPLAY_FAILED and gives confirmation advice when virtual display creation fails", async () => {
    const adb = new FakeAdb();
    const vdManager = new FakeVirtualDisplayManager(adb);
    vdManager.failLaunch = true;
    const service = createService(adb, undefined, {
      virtualDisplayManager: vdManager
    });

    await expect(service.openApp(POLICY.allowedApps[0])).rejects.toMatchObject({
      code: "VIRTUAL_DISPLAY_FAILED"
    });
  });

  it("closes an active virtual display session with closeApp", async () => {
    const adb = new FakeAdb();
    const vdManager = new FakeVirtualDisplayManager(adb);
    const service = createService(adb, undefined, {
      virtualDisplayManager: vdManager
    });

    await service.openApp(POLICY.allowedApps[0]);
    expect(vdManager.sessions).toHaveLength(1);

    const closeResult = await service.closeApp({
      packageName: POLICY.allowedApps[0]
    });
    expect(closeResult.data.closed).toBe(true);
    expect(vdManager.sessions).toHaveLength(0);
  });

  it("returns active virtual displays in status", async () => {
    const adb = new FakeAdb();
    const vdManager = new FakeVirtualDisplayManager(adb);
    const service = createService(adb, undefined, {
      virtualDisplayManager: vdManager
    });

    await service.openApp(POLICY.allowedApps[0]);
    const status = await service.status();

    expect(status.data.virtualDisplays).toHaveLength(1);
    expect(status.data.virtualDisplays?.[0].packageName).toBe(
      POLICY.allowedApps[0]
    );
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

    expect(adb.taps).toEqual([{ x: 60, y: 50, displayId: 0 }]);
    expect(result.data.pointerEvent).toMatchObject({
      x: 60,
      y: 50,
      observationId,
      serial: "phone-1",
      displayId: 0,
      packageName: POLICY.allowedApps[0],
      displayWidth: 1080,
      displayHeight: 2400,
      timestamp: expect.any(Number)
    });
    expect(logger.entries[0]).toMatchObject({
      outcome: "pending",
      phase: "start",
      pointerEvent: { x: 60, y: 50 }
    });
    expect(logger.entries[1]).toMatchObject({
      outcome: "success",
      phase: "result",
      pointerEvent: { x: 60, y: 50 }
    });
    expect(service.observationStore.get(observationId)).toBeUndefined();
  });

  it("logs the pointer start before sending the tap", async () => {
    const adb = new FakeAdb();
    const logger = new MemoryAuditLogger();
    const order: string[] = [];
    const append = logger.append.bind(logger);
    logger.append = async (entry) => {
      order.push(`audit:${entry.phase ?? "result"}`);
      await append(entry);
    };
    adb.tap = async () => {
      order.push("tap");
    };
    const service = createService(adb, logger);
    const observed = await service.observe();

    await service.execute({
      observationId: observed.data.observation.observationId,
      action: { type: "click", elementRef: observed.data.observation.elements[0].elementRef }
    });

    expect(order.slice(0, 2)).toEqual(["audit:start", "tap"]);
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

  it("ignores generic unrelated mutations and reanchors to fresh target bounds", async () => {
    const adb = new FakeAdb();
    adb.xml = TARGET_XML;
    const service = createService(adb);
    const observed = await service.observe();
    const observation = observed.data.observation;
    adb.xml = TARGET_XML
      .replace("Live ticker 29:33", "Clock-like text 09:16")
      .replace('content-desc="live"', 'content-desc="transient"')
      .replace('text="$10.00"', 'text="Progress 87%" selected="true"')
      .replace('bounds="[0,40][200,70]"', 'bounds="[0,50][240,90]"')
      .replace("[10,20][110,80]", "[20,30][140,100]");

    await service.execute({
      observationId: observation.observationId,
      action: { type: "click", elementRef: observation.elements[0].elementRef }
    });
    expect(adb.taps).toEqual([{ x: 80, y: 65, displayId: 0 }]);
  });

  it("allows arbitrary unrelated clock-like and amount mutations", async () => {
    const adb = new FakeAdb();
    adb.xml = TARGET_XML;
    const service = createService(adb);
    const observed = await service.observe();
    const observation = observed.data.observation;
    adb.xml = adb.xml.replace("Live ticker 29:33", "9:16").replace("$10.00", "$20.00");
    await service.execute({
      observationId: observation.observationId,
      action: { type: "click", elementRef: observation.elements[0].elementRef }
    });
  });

  it("allows background feed nodes to lazy-load behind a valid sheet target", async () => {
    const adb = new FakeAdb();
    adb.xml = CHOWDECK_XML;
    const service = createService(adb);
    const observed = await service.observe();
    const retry = observed.data.observation.elements.find(
      (element) => element.text === "Try again"
    );
    expect(retry).toBeDefined();
    adb.xml = CHOWDECK_XML.replace(
      '</node><node resource-id="com.chowdeck:id/offline_sheet"',
      '<node text="Restaurant B" class="android.view.View" bounds="[0,500][300,650]"/><node text="Restaurant C" class="android.view.View" bounds="[0,350][300,500]"/><node text="Restaurant D" class="android.view.View" bounds="[0,200][300,350]"/><node text="Restaurant E" class="android.view.View" bounds="[0,50][300,200]"/></node><node resource-id="com.chowdeck:id/offline_sheet"'
    );

    await service.execute({
      observationId: observed.data.observation.observationId,
      action: { type: "click", elementRef: retry!.elementRef }
    });
    expect(adb.taps).toEqual([{ x: 150, y: 740, displayId: 0 }]);
  });

  it("rejects a matching target that moved into a different ancestor context", async () => {
    const retryNode = '<node text="Try again" content-desc="Try again" class="android.widget.Button" clickable="true" enabled="true" bounds="[40,700][260,780]"/>';
    const adb = new FakeAdb();
    adb.xml = CHOWDECK_XML;
    const service = createService(adb);
    const observed = await service.observe();
    const retry = observed.data.observation.elements.find(
      (element) => element.text === "Try again"
    );
    expect(retry).toBeDefined();
    adb.xml = CHOWDECK_XML
      .replace(retryNode, "")
      .replace(
        '</node><node resource-id="com.chowdeck:id/offline_sheet"',
        `${retryNode}</node><node resource-id="com.chowdeck:id/offline_sheet"`
      );

    await expect(
      service.execute({
        observationId: observed.data.observation.observationId,
        action: { type: "click", elementRef: retry!.elementRef }
      })
    ).rejects.toMatchObject({ code: "STALE_OBSERVATION" });
    expect(adb.taps).toHaveLength(0);
  });

  it("reanchors element-scoped scrolls to fresh bounds", async () => {
    const adb = new FakeAdb();
    adb.xml = TARGET_XML;
    const service = createService(adb);
    const observed = await service.observe();
    const target = observed.data.observation.elements[0];
    adb.xml = TARGET_XML.replace('bounds="[10,20][110,80]"', 'bounds="[20,30][140,100]"')
      .replace("Live ticker 29:33", "Live ticker 29:21");
    await service.execute({
      observationId: observed.data.observation.observationId,
      action: { type: "scroll", direction: "right", amount: "small", elementRef: target.elementRef }
    });
    expect(adb.swipes[0]).toMatchObject({ x1: 95, x2: 65, y1: 65, y2: 65 });
  });

  it("rejects changed target labels, modal structure, ambiguity, and missing targets", async () => {
    const scenarios = [
      (xml: string) => xml.replace('text="Pay"', 'text="Cancel"'),
      (xml: string) => xml.replace("</hierarchy>", '<node text="Confirm" class="android.widget.Dialog" bounds="[0,0][300,300]"/></hierarchy>'),
      (xml: string) => xml.replace("</hierarchy>", '<node text="Pay" content-desc="pay" resource-id="com.paystack:id/pay" class="android.widget.Button" clickable="true" enabled="true" bounds="[10,20][110,80]"/></hierarchy>'),
      (xml: string) => xml.replace('resource-id="com.paystack:id/pay"', 'resource-id="com.paystack:id/missing"')
    ];
    for (const mutate of scenarios) {
      const adb = new FakeAdb();
      adb.xml = TARGET_XML;
      const service = createService(adb);
      const observed = await service.observe();
      const observation = observed.data.observation;
      adb.xml = mutate(TARGET_XML);
      await expect(
        service.execute({
          observationId: observation.observationId,
          action: { type: "click", elementRef: observation.elements[0].elementRef }
        })
      ).rejects.toMatchObject({ code: "STALE_OBSERVATION" });
      expect(adb.taps).toHaveLength(0);
    }
  });

  it("rejects target state changes while ignoring unrelated transient states", async () => {
    const targetStateXml = TARGET_XML.replace(
      'clickable="true" enabled="true"',
      'checkable="true" checked="false" clickable="true" enabled="true"'
    );
    const adb = new FakeAdb();
    adb.xml = targetStateXml;
    const service = createService(adb);
    const observed = await service.observe();
    adb.xml = targetStateXml
      .replace('checked="false"', 'checked="true"')
      .replace('text="$10.00"', 'text="$10.00" selected="true"');

    await expect(
      service.execute({
        observationId: observed.data.observation.observationId,
        action: {
          type: "click",
          elementRef: observed.data.observation.elements[0].elementRef
        }
      })
    ).rejects.toMatchObject({ code: "STALE_OBSERVATION" });
    expect(adb.taps).toHaveLength(0);
  });

  it("keeps coordinate actions strict when an unrelated live label changes", async () => {
    const adb = new FakeAdb();
    adb.xml = TARGET_XML;
    const service = createService(adb);
    const observed = await service.observe();
    adb.xml = TARGET_XML.replace("Live ticker 29:33", "Live ticker 29:21");
    await expect(
      service.execute({
        observationId: observed.data.observation.observationId,
        action: { type: "click_coordinate", x: 10, y: 20 }
      })
    ).rejects.toMatchObject({ code: "STALE_OBSERVATION" });
    expect(adb.taps).toHaveLength(0);
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
    await expect(
      service.openApp(POLICY.allowedApps[0], { useVirtualDisplay: false })
    ).rejects.toMatchObject({
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
    await expect(
      service.openApp(POLICY.allowedApps[0], { useVirtualDisplay: false })
    ).rejects.toMatchObject({
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
    expect(adb.swipes[0]).toMatchObject({ durationMs: 280, displayId: 0 });

    const elementScrollObservation = await service.observe();
    const targetElement = elementScrollObservation.data.observation.elements[0];
    await service.execute({
      observationId: elementScrollObservation.data.observation.observationId,
      action: {
        type: "scroll",
        direction: "right",
        amount: "small",
        elementRef: targetElement.elementRef
      }
    });
    expect(adb.swipes[1]).toMatchObject({ durationMs: 200, displayId: 0 });

    const typeObservation = await service.observe();
    const typedResult = await service.execute({
      observationId: typeObservation.data.observation.observationId,
      action: { type: "type", text: "secret value" }
    });
    expect(typedResult.data.textLength).toBe(12);
    expect(adb.typed).toEqual([{ text: "secret value", displayId: 0 }]);

    const keyObservation = await service.observe();
    await service.execute({
      observationId: keyObservation.data.observation.observationId,
      action: { type: "keypress", key: "ENTER" }
    });
    expect(adb.keys).toEqual([{ key: "ENTER", displayId: 0 }]);
    expect(JSON.stringify(logger.entries)).not.toContain("secret value");
  });
});
