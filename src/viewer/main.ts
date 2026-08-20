import { spawn, type ChildProcess } from "node:child_process";
import { isAbsolute, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { app, BrowserWindow } from "electron";

import { AdbProcessAdapter } from "../adb/process-adapter.js";
import { selectDeviceFromEnvironment } from "../adb/device-selection.js";
import { resolveAdbPath } from "../adb/path.js";
import { asPhoneControlError, PhoneControlError } from "../errors.js";
import { loadViewerConfig, type ViewerConfig } from "./config.js";
import { mapDevicePointToOverlay } from "./coordinate-mapping.js";
import { NdjsonTailer, type SuccessfulClickAuditEvent } from "./ndjson.js";
import { buildOverlayHtml } from "./overlay-html.js";
import { buildScrcpyArgs } from "./scrcpy-args.js";
import { resolveScrcpyPath } from "./scrcpy-path.js";

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

function overlayUrl(config: ViewerConfig): string {
  return `data:text/html;charset=utf-8,${encodeURIComponent(
    buildOverlayHtml(config.cursorDurationMs)
  )}`;
}

function showCursor(
  overlay: BrowserWindow,
  event: SuccessfulClickAuditEvent,
  config: ViewerConfig
): void {
  const mapped = mapDevicePointToOverlay(event.pointerEvent, config);
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

  await app.whenReady();
  const overlay = new BrowserWindow({
    x: config.x,
    y: config.y,
    width: config.width,
    height: config.height,
    transparent: true,
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
  overlay.setIgnoreMouseEvents(true, { forward: true });
  overlay.setMenuBarVisibility(false);
  await overlay.loadURL(overlayUrl(config));
  overlay.showInactive();

  let stopping = false;
  const scrcpy = spawnScrcpy(scrcpyPath, serial, config);
  let interval: NodeJS.Timeout | undefined;
  const stop = (): void => {
    if (stopping) return;
    stopping = true;
    if (interval) clearInterval(interval);
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

  let polling = false;
  const poll = async (): Promise<void> => {
    if (polling || stopping) return;
    polling = true;
    try {
      for (const event of await tailer.poll()) {
        if (!overlay.isDestroyed()) showCursor(overlay, event, config);
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
  interval = setInterval(() => void poll(), config.auditPollIntervalMs);
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
