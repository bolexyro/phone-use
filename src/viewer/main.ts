import { spawn, type ChildProcess } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { isAbsolute, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { app, BrowserWindow, screen } from "electron";

import { AdbProcessAdapter } from "../adb/process-adapter.js";
import { selectDeviceFromEnvironment } from "../adb/device-selection.js";
import { resolveAdbPath } from "../adb/path.js";
import { asPhoneControlError, PhoneControlError } from "../errors.js";
import { loadViewerConfig, type ViewerConfig } from "./config.js";
import {
  mapDevicePointToOverlay,
  type ViewerGeometry
} from "./coordinate-mapping.js";
import {
  createGeometryRecoveryState,
  updateGeometryRecovery
} from "./geometry-recovery.js";
import { NdjsonTailer, type SuccessfulClickAuditEvent } from "./ndjson.js";
import { buildOverlayHtml } from "./overlay-html.js";
import { buildScrcpyArgs } from "./scrcpy-args.js";
import { resolveScrcpyPath } from "./scrcpy-path.js";
import { fitDipRectToWorkArea } from "./window-geometry.js";
import { Win32ClientWindowRectProvider } from "./win32-window.js";

if (process.platform === "win32") {
  try {
    app.disableHardwareAcceleration();
  } catch {
    app.commandLine.appendSwitch("disable-gpu");
  }
}

export async function resolveViewerSerial(
  env: NodeJS.ProcessEnv = process.env,
  cwd = process.cwd()
): Promise<string> {
  const adbPath = resolveAdbPath({ env, cwd });
  const adb = new AdbProcessAdapter({ adbPath });
  const devices = await adb.listDevices();
  return selectDeviceFromEnvironment(devices, env).serial;
}

function spawnScrcpy(scrcpyPath: string, serial: string, config: ViewerConfig): ChildProcess {
  const child = spawn(scrcpyPath, buildScrcpyArgs(serial, config), {
    shell: false,
    windowsHide: false,
    stdio: ["ignore", "ignore", "pipe"]
  });
  child.stderr?.on("data", (chunk: Buffer | string) => {
    const text = Buffer.isBuffer(chunk) ? chunk.toString("utf8") : chunk;
    if (text.trim()) console.error(`[phone-control-viewer] ${text.trim()}`);
  });
  return child;
}

async function prepareOverlayPage(
  config: ViewerConfig,
  cwd: string
): Promise<string> {
  const runtimeDirectory = resolve(cwd, ".runtime", "viewer");
  await mkdir(runtimeDirectory, { recursive: true });
  const pagePath = resolve(runtimeDirectory, "overlay.html");
  await writeFile(pagePath, buildOverlayHtml(config.cursorDurationMs), "utf8");
  return pagePath;
}

function configureElectronRuntime(cwd: string): void {
  const userDataPath = resolve(cwd, ".runtime", "electron");
  app.setPath("userData", userDataPath);
  app.setPath("cache", resolve(userDataPath, "cache"));
}

function fitViewerToWorkArea(config: ViewerConfig): ViewerConfig {
  const requestedPhysical = {
    x: config.x,
    y: config.y,
    width: config.width,
    height: config.height
  };
  const requestedDip = screen.screenToDipRect(null, requestedPhysical);
  const display = screen.getDisplayNearestPoint({
    x: requestedDip.x,
    y: requestedDip.y
  });
  const fittedDip = fitDipRectToWorkArea(requestedDip, display.workArea);
  const topLeft = screen.dipToScreenPoint({ x: fittedDip.x, y: fittedDip.y });
  const bottomRight = screen.dipToScreenPoint({
    x: fittedDip.x + fittedDip.width,
    y: fittedDip.y + fittedDip.height
  });

  return {
    ...config,
    x: topLeft.x,
    y: topLeft.y,
    width: Math.max(1, bottomRight.x - topLeft.x),
    height: Math.max(1, bottomRight.y - topLeft.y)
  };
}

function showCursor(
  overlay: BrowserWindow,
  event: SuccessfulClickAuditEvent,
  geometry: ViewerGeometry
): void {
  const mapped = mapDevicePointToOverlay(event.pointerEvent, geometry);
  overlay.moveTop();
  const payload = JSON.stringify({
    localX: mapped.localX,
    localY: mapped.localY
  });
  void overlay.webContents
    .executeJavaScript(`window.phoneControlShowCursor(${payload})`, true)
    .catch((error: unknown) => {
      console.error(
        `[phone-control-viewer] cursor render failed: ${
          error instanceof Error ? error.message : String(error)
        }`
      );
    });
}

export async function startViewer(
  env: NodeJS.ProcessEnv = process.env,
  cwd = process.cwd()
): Promise<void> {
  if (process.platform !== "win32") {
    throw new PhoneControlError(
      "VIEWER_UNSUPPORTED_PLATFORM",
      "The phone-control cursor viewer is Windows-only."
    );
  }

  const config = loadViewerConfig(env, cwd);
  const scrcpyPath = resolveScrcpyPath({ env, cwd, platform: "win32" });
  const serial = await resolveViewerSerial(env, cwd);
  const overlayPagePath = await prepareOverlayPage(config, cwd);

  configureElectronRuntime(cwd);
  await app.whenReady();
  const runtimeConfig = fitViewerToWorkArea(config);
  if (
    runtimeConfig.x !== config.x ||
    runtimeConfig.y !== config.y ||
    runtimeConfig.width !== config.width ||
    runtimeConfig.height !== config.height
  ) {
    console.error(
      `[phone-control-viewer] fitted viewer geometry to visible work area: ${
        runtimeConfig.x
      },${runtimeConfig.y} ${runtimeConfig.width}x${runtimeConfig.height}`
    );
  }
  const physicalBounds = {
    x: runtimeConfig.x,
    y: runtimeConfig.y,
    width: runtimeConfig.width,
    height: runtimeConfig.height
  };
  const initialOverlayBounds = screen.screenToDipRect(null, physicalBounds);
  let overlayGeometry: ViewerGeometry = {
    x: initialOverlayBounds.x,
    y: initialOverlayBounds.y,
    width: initialOverlayBounds.width,
    height: initialOverlayBounds.height
  };
  const overlay = new BrowserWindow({
    x: initialOverlayBounds.x,
    y: initialOverlayBounds.y,
    width: initialOverlayBounds.width,
    height: initialOverlayBounds.height,
    show: false,
    transparent: true,
    backgroundColor: "#00000000",
    frame: false,
    alwaysOnTop: true,
    focusable: false,
    skipTaskbar: true,
    resizable: false,
    hasShadow: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  overlay.setAlwaysOnTop(true, "floating");
  overlay.setVisibleOnAllWorkspaces(false);
  overlay.setIgnoreMouseEvents(true, { forward: true });
  overlay.setMenuBarVisibility(false);
  // Start scrcpy before loading the transparent renderer. If Electron's
  // renderer is slow or unavailable, the user must still get a usable phone
  // window instead of a silent background process.
  const scrcpy = spawnScrcpy(scrcpyPath, serial, runtimeConfig);
  try {
    await overlay.loadFile(overlayPagePath);
  } catch (error) {
    if (!scrcpy.killed) scrcpy.kill();
    throw error;
  }

  let stopping = false;
  const windowRectProvider = new Win32ClientWindowRectProvider();
  let auditInterval: NodeJS.Timeout | undefined;
  let geometryInterval: NodeJS.Timeout | undefined;
  let geometrySyncPromise: Promise<boolean> | undefined;
  let removeWindowChangedListener: (() => void) | undefined;
  let recoveryState = createGeometryRecoveryState();
  let pendingCursorEvent: SuccessfulClickAuditEvent | undefined;
  const stop = (): void => {
    if (stopping) return;
    stopping = true;
    if (auditInterval) clearInterval(auditInterval);
    if (geometryInterval) clearInterval(geometryInterval);
    removeWindowChangedListener?.();
    windowRectProvider.close();
    if (!scrcpy.killed) scrcpy.kill();
  };
  app.on("before-quit", stop);

  const tailer = new NdjsonTailer(config.auditLogPath, { startAtEnd: true });
  try {
    await tailer.poll();
  } catch (error) {
    stop();
    if (!overlay.isDestroyed()) overlay.close();
    throw error;
  }

  const runGeometrySync = async (): Promise<boolean> => {
    if (stopping || scrcpy.pid === undefined) return false;
    try {
      const clientRect = await windowRectProvider.getClientRect(scrcpy.pid);
      if (!clientRect || overlay.isDestroyed()) {
        recoveryState = updateGeometryRecovery(recoveryState, undefined).state;
        if (!overlay.isDestroyed() && overlay.isVisible()) overlay.hide();
        return false;
      }
      const nextBounds = screen.screenToDipRect(null, clientRect);
      const wasAttached = recoveryState.attached;
      const recovery = updateGeometryRecovery(recoveryState, nextBounds, 2);
      recoveryState = recovery.state;
      const changed =
        overlayGeometry.x !== nextBounds.x ||
        overlayGeometry.y !== nextBounds.y ||
        overlayGeometry.width !== nextBounds.width ||
        overlayGeometry.height !== nextBounds.height;
      if (changed) {
        overlayGeometry = {
          x: nextBounds.x,
          y: nextBounds.y,
          width: nextBounds.width,
          height: nextBounds.height
        };
        overlay.setBounds(nextBounds);
        console.error(
          `[phone-control-viewer] overlay synced to scrcpy client: ${
            nextBounds.x
          },${nextBounds.y} ${nextBounds.width}x${nextBounds.height}`
        );
      }
      if (recovery.changed) {
        console.error(
          `[phone-control-viewer] scrcpy geometry changed; waiting for a stable window position.`
        );
      }
      if (!recovery.attached) {
        if (!overlay.isDestroyed() && overlay.isVisible()) overlay.hide();
        return false;
      }
      if (!overlay.isVisible()) overlay.showInactive();
      if (!wasAttached) {
        console.error(`[phone-control-viewer] cursor overlay recovered and is visible.`);
      }
      return true;
    } catch (error) {
      console.error(
        `[phone-control-viewer] window sync failed: ${
          error instanceof Error ? error.message : String(error)
        }`
      );
      return false;
    }
  };

  const syncOverlayToScrcpy = (): Promise<boolean> => {
    if (!geometrySyncPromise) {
      geometrySyncPromise = runGeometrySync().finally(() => {
        geometrySyncPromise = undefined;
      });
    }
    return geometrySyncPromise;
  };

  const renderPendingCursor = (): void => {
    if (
      !pendingCursorEvent ||
      overlay.isDestroyed() ||
      !recoveryState.attached
    ) {
      return;
    }
    const event = pendingCursorEvent;
    pendingCursorEvent = undefined;
    showCursor(overlay, event, overlayGeometry);
  };

  const reconcileAndRender = async (): Promise<void> => {
    if (await syncOverlayToScrcpy()) renderPendingCursor();
  };

  removeWindowChangedListener = windowRectProvider.onWindowChanged((event) => {
    if (event.processId === scrcpy.pid || scrcpy.pid === undefined || event.processId > 0) {
      void reconcileAndRender();
    }
  });
  if (scrcpy.pid !== undefined) {
    try {
      const watching = await windowRectProvider.watchProcess(scrcpy.pid);
      if (!watching) {
        console.error(
          `[phone-control-viewer] native window movement tracking did not start; watchdog polling remains active.`
        );
      }
    } catch (error) {
      console.error(
        `[phone-control-viewer] native window movement tracking failed: ${
          error instanceof Error ? error.message : String(error)
        }`
      );
    }
  }
  await reconcileAndRender();
  geometryInterval = setInterval(() => void reconcileAndRender(), 250);

  let polling = false;
  const poll = async (): Promise<void> => {
    if (polling || stopping) return;
    polling = true;
    try {
      for (const event of await tailer.poll()) {
        pendingCursorEvent = event;
        await reconcileAndRender();
      }
    } catch (error) {
      console.error(
        `[phone-control-viewer] audit poll failed: ${
          error instanceof Error ? error.message : String(error)
        }`
      );
    } finally {
      polling = false;
    }
  };
  auditInterval = setInterval(() => void poll(), config.auditPollIntervalMs);
  overlay.on("closed", () => {
    if (!stopping) app.quit();
  });
  scrcpy.once("error", (error) => {
    console.error(`[phone-control-viewer] scrcpy failed: ${error.message}`);
    if (!stopping) app.quit();
  });
  scrcpy.once("exit", (code, signal) => {
    if (!stopping) {
      console.error(`[phone-control-viewer] scrcpy exited (${code ?? signal ?? "unknown"}).`);
      app.quit();
    }
  });
  app.on("window-all-closed", () => {
    if (process.platform !== "darwin") app.quit();
  });
}

function isMainModule(): boolean {
  return process.argv[1]
    ? resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))
    : false;
}

if (isMainModule()) {
  void startViewer().catch((error: unknown) => {
    const normalized = asPhoneControlError(error, "VIEWER_START_FAILED");
    console.error(`[phone-control-viewer] startup failed: ${normalized.code}: ${normalized.message}`);
    process.exitCode = 1;
    if (app.isReady()) app.quit();
  });
}
