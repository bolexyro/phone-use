import { spawn, type ChildProcess } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { isAbsolute, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { app, BrowserWindow, screen } from "electron";

import {
  AdbProcessAdapter,
  PhoneControlError,
  asPhoneControlError,
  resolveAdbPath,
  resolveScrcpyPath,
  selectDeviceFromEnvironment
} from "@dhd/phone-control";
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
import { fitDipRectToWorkArea } from "./window-geometry.js";
import { parsePackageFromWindowTitle } from "./window-title.js";
import { Win32ClientWindowRectProvider } from "./win32-window.js";

if (process.platform === "win32" && typeof app !== "undefined") {
  try {
    app.disableHardwareAcceleration?.();
  } catch {
    app.commandLine?.appendSwitch?.("disable-gpu");
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
  if (overlay.isDestroyed() || !overlay.isVisible()) return;

  if (event.pointerEvent.action === "scroll") {
    const startMapped = mapDevicePointToOverlay(
      {
        x: event.pointerEvent.startX,
        y: event.pointerEvent.startY,
        displayWidth: event.pointerEvent.displayWidth,
        displayHeight: event.pointerEvent.displayHeight
      },
      geometry
    );
    const endMapped = mapDevicePointToOverlay(
      {
        x: event.pointerEvent.endX,
        y: event.pointerEvent.endY,
        displayWidth: event.pointerEvent.displayWidth,
        displayHeight: event.pointerEvent.displayHeight
      },
      geometry
    );
    overlay.moveTop();
    const payload = JSON.stringify({
      startX: startMapped.localX,
      startY: startMapped.localY,
      endX: endMapped.localX,
      endY: endMapped.localY,
      direction: event.pointerEvent.direction,
      amount: event.pointerEvent.amount,
      durationMs: event.pointerEvent.durationMs
    });
    void overlay.webContents
      .executeJavaScript(`window.phoneControlShowScroll && window.phoneControlShowScroll(${payload})`, true)
      .catch((error: unknown) => {
        console.error(
          `[phone-control-viewer] scroll render failed: ${
            error instanceof Error ? error.message : String(error)
          }`
        );
      });
    return;
  }

  const mapped = mapDevicePointToOverlay(event.pointerEvent, geometry);
  overlay.moveTop();
  const payload = JSON.stringify({
    localX: mapped.localX,
    localY: mapped.localY
  });
  void overlay.webContents
    .executeJavaScript(`window.phoneControlShowCursor && window.phoneControlShowCursor(${payload})`, true)
    .catch((error: unknown) => {
      console.error(
        `[phone-control-viewer] cursor render failed: ${
          error instanceof Error ? error.message : String(error)
        }`
      );
    });
}

export interface OverlaySession {
  processId: number;
  title: string;
  packageName?: string;
  overlay: BrowserWindow;
  recoveryState: ReturnType<typeof createGeometryRecoveryState>;
  geometry: ViewerGeometry;
  pendingCursorEvent?: SuccessfulClickAuditEvent;
}

export async function startViewer(
  env: NodeJS.ProcessEnv = process.env,
  cwd = process.cwd(),
  options: { overlayOnly?: boolean } = {}
): Promise<void> {
  if (process.platform !== "win32") {
    throw new PhoneControlError(
      "VIEWER_UNSUPPORTED_PLATFORM",
      "The phone-control cursor viewer is Windows-only."
    );
  }

  const config = loadViewerConfig(env, cwd);
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

  function createOverlay(initialBounds: ViewerGeometry): BrowserWindow {
    const overlay = new BrowserWindow({
      x: initialBounds.x,
      y: initialBounds.y,
      width: initialBounds.width,
      height: initialBounds.height,
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
    void overlay.loadFile(overlayPagePath);
    return overlay;
  }

  const scrcpy = options.overlayOnly
    ? undefined
    : spawnScrcpy(
        resolveScrcpyPath({ env, cwd, platform: "win32" }),
        await resolveViewerSerial(env, cwd),
        runtimeConfig
      );

  let stopping = false;
  const windowRectProvider = new Win32ClientWindowRectProvider();
  const sessions = new Map<number, OverlaySession>();
  const watchedProcesses = new Set<number>();
  let auditInterval: NodeJS.Timeout | undefined;
  let geometryInterval: NodeJS.Timeout | undefined;
  let geometrySyncPromise: Promise<boolean> | undefined;
  let removeWindowChangedListener: (() => void) | undefined;

  const stop = (): void => {
    if (stopping) return;
    stopping = true;
    if (auditInterval) clearInterval(auditInterval);
    if (geometryInterval) clearInterval(geometryInterval);
    removeWindowChangedListener?.();
    windowRectProvider.close();
    for (const session of sessions.values()) {
      if (!session.overlay.isDestroyed()) {
        session.overlay.close();
      }
    }
    sessions.clear();
    if (scrcpy && !scrcpy.killed) scrcpy.kill();
  };
  app.on("before-quit", stop);

  const tailer = new NdjsonTailer(config.auditLogPath, { startAtEnd: true });
  try {
    await tailer.poll();
  } catch (error) {
    stop();
    throw error;
  }

  const runGeometrySync = async (): Promise<boolean> => {
    if (stopping) return false;
    try {
      const windows = await windowRectProvider.listWindows();
      const discoveredPids = new Set<number>();

      for (const win of windows) {
        discoveredPids.add(win.processId);
        const nextBounds = screen.screenToDipRect(null, {
          x: win.x,
          y: win.y,
          width: win.width,
          height: win.height
        });

        let session = sessions.get(win.processId);
        if (!session) {
          const overlay = createOverlay(nextBounds);
          const pkg = parsePackageFromWindowTitle(win.title);
          session = {
            processId: win.processId,
            title: win.title,
            packageName: pkg,
            overlay,
            recoveryState: createGeometryRecoveryState(),
            geometry: {
              x: nextBounds.x,
              y: nextBounds.y,
              width: nextBounds.width,
              height: nextBounds.height
            }
          };
          sessions.set(win.processId, session);
        }

        if (!watchedProcesses.has(win.processId)) {
          watchedProcesses.add(win.processId);
          void windowRectProvider.watchProcess(win.processId);
        }

        const recovery = updateGeometryRecovery(session.recoveryState, nextBounds, 2);
        session.recoveryState = recovery.state;

        const changed =
          session.geometry.x !== nextBounds.x ||
          session.geometry.y !== nextBounds.y ||
          session.geometry.width !== nextBounds.width ||
          session.geometry.height !== nextBounds.height;

        if (changed) {
          session.geometry = {
            x: nextBounds.x,
            y: nextBounds.y,
            width: nextBounds.width,
            height: nextBounds.height
          };
          if (!session.overlay.isDestroyed()) {
            session.overlay.setBounds(nextBounds);
          }
        }

        if (!recovery.attached) {
          if (!session.overlay.isDestroyed() && session.overlay.isVisible()) {
            session.overlay.hide();
          }
          if (session.recoveryState.stableSamples === 1 && !stopping) {
            setTimeout(() => void reconcile(), 40);
          }
        } else {
          if (!session.overlay.isDestroyed() && !session.overlay.isVisible()) {
            session.overlay.showInactive();
          }
          if (session.pendingCursorEvent) {
            const pending = session.pendingCursorEvent;
            session.pendingCursorEvent = undefined;
            if (Date.now() - pending.at <= 3000) {
              showCursor(session.overlay, pending, session.geometry);
            }
          }
        }
      }

      for (const [pid, session] of sessions.entries()) {
        if (!discoveredPids.has(pid)) {
          let isAlive = false;
          try {
            process.kill(pid, 0);
            isAlive = true;
          } catch {
            isAlive = false;
          }

          if (isAlive) {
            session.recoveryState = updateGeometryRecovery(session.recoveryState, undefined).state;
            if (!session.overlay.isDestroyed() && session.overlay.isVisible()) {
              session.overlay.hide();
            }
          } else {
            if (!session.overlay.isDestroyed()) {
              session.overlay.close();
            }
            sessions.delete(pid);
          }
        }
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

  const syncOverlays = (): Promise<boolean> => {
    if (!geometrySyncPromise) {
      geometrySyncPromise = runGeometrySync().finally(() => {
        geometrySyncPromise = undefined;
      });
    }
    return geometrySyncPromise;
  };

  const dispatchCursorEvent = (event: SuccessfulClickAuditEvent): void => {
    let targetSession: OverlaySession | undefined;

    if (event.packageName) {
      for (const session of sessions.values()) {
        if (session.packageName === event.packageName) {
          targetSession = session;
          break;
        }
      }
    }

    if (!targetSession) {
      if (scrcpy?.pid !== undefined && sessions.has(scrcpy.pid)) {
        targetSession = sessions.get(scrcpy.pid);
      } else {
        targetSession = sessions.values().next().value;
      }
    }

    if (!targetSession || targetSession.overlay.isDestroyed()) {
      return;
    }

    if (targetSession.recoveryState.attached && targetSession.overlay.isVisible()) {
      showCursor(targetSession.overlay, event, targetSession.geometry);
    } else {
      targetSession.pendingCursorEvent = event;
    }
  };

  const reconcile = async (): Promise<void> => {
    await syncOverlays();
  };

  removeWindowChangedListener = windowRectProvider.onWindowChanged(() => {
    void reconcile();
  });

  await reconcile();
  geometryInterval = setInterval(() => void reconcile(), 250);

  let polling = false;
  const poll = async (): Promise<void> => {
    if (polling || stopping) return;
    polling = true;
    try {
      for (const event of await tailer.poll()) {
        await syncOverlays();
        dispatchCursorEvent(event);
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

  scrcpy?.once("error", (error) => {
    console.error(`[phone-control-viewer] scrcpy failed: ${error.message}`);
  });
  scrcpy?.once("exit", (code, signal) => {
    console.error(`[phone-control-viewer] primary scrcpy exited (${code ?? signal ?? "unknown"}).`);
  });
  app.on("window-all-closed", () => {
    // Keep viewer daemon running even if all current overlay windows close
  });
}

function isMainModule(): boolean {
  return process.argv[1]
    ? resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))
    : false;
}

if (isMainModule()) {
  void startViewer(process.env, process.cwd(), {
    overlayOnly: process.argv.includes("--overlay-only")
  }).catch((error: unknown) => {
    const normalized = asPhoneControlError(error, "VIEWER_START_FAILED");
    console.error(`[phone-control-viewer] startup failed: ${normalized.code}: ${normalized.message}`);
    process.exitCode = 1;
    if (app.isReady()) app.quit();
  });
}
