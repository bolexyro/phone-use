import { spawn, type ChildProcess } from "node:child_process";
import { Buffer } from "node:buffer";

import type { FixedAdbAdapter } from "./adb/adapter.js";
import { PhoneControlError } from "./errors.js";
import type { VirtualDisplaySession } from "./types.js";
import { resolveScrcpyPath } from "./viewer/scrcpy-path.js";

export interface VirtualDisplayOptions {
  scrcpyPath?: string;
  width?: number;
  height?: number;
  dpi?: number;
  mouseMode?: "disabled" | "sdk" | "uhid" | "aoa";
  noDecorations?: boolean;
  environment?: NodeJS.ProcessEnv;
  pollIntervalMs?: number;
  launchTimeoutMs?: number;
  sleep?: (ms: number) => Promise<void>;
}

export interface SpawnedVirtualDisplay {
  session: VirtualDisplaySession;
  process: ChildProcess;
}

const DEFAULT_POLL_INTERVAL_MS = 250;
const DEFAULT_LAUNCH_TIMEOUT_MS = 10_000;

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export const DEFAULT_VIRTUAL_DISPLAY_WINDOW_WIDTH = 420;

export function buildVirtualDisplayScrcpyArgs(
  serial: string,
  packageName: string,
  options: {
    width?: number;
    height?: number;
    dpi?: number;
    windowWidth?: number;
    mouseMode?: "disabled" | "sdk" | "uhid" | "aoa";
    noDecorations?: boolean;
  } = {}
): readonly string[] {
  const args = ["-s", serial];

  if (options.width && options.height) {
    const res = options.dpi
      ? `${options.width}x${options.height}/${options.dpi}`
      : `${options.width}x${options.height}`;
    args.push(`--new-display=${res}`);
  } else {
    args.push("--new-display");
  }

  args.push(`--start-app=${packageName}`);

  if (options.noDecorations !== false) {
    args.push("--no-vd-system-decorations");
  }

  const mouseMode = options.mouseMode ?? "sdk";
  args.push(`--mouse=${mouseMode}`);

  args.push("--no-audio");
  args.push("--stay-awake");
  args.push(`--window-title=Phone Control: ${packageName}`);

  const windowWidth = options.windowWidth ?? DEFAULT_VIRTUAL_DISPLAY_WINDOW_WIDTH;
  args.push(`--window-width=${windowWidth}`);

  return args;
}

export class VirtualDisplayManager {
  readonly #adb: FixedAdbAdapter;
  readonly #sessions = new Map<number, SpawnedVirtualDisplay>();
  readonly #environment: NodeJS.ProcessEnv;
  readonly #sleep: (ms: number) => Promise<void>;
  readonly #scrcpyPath?: string;

  public constructor(
    adb: FixedAdbAdapter,
    options: {
      environment?: NodeJS.ProcessEnv;
      scrcpyPath?: string;
      sleep?: (ms: number) => Promise<void>;
    } = {}
  ) {
    this.#adb = adb;
    this.#environment = options.environment ?? process.env;
    this.#scrcpyPath = options.scrcpyPath;
    this.#sleep = options.sleep ?? defaultSleep;
  }

  public get sessions(): readonly VirtualDisplaySession[] {
    return Array.from(this.#sessions.values()).map((item) => ({
      ...item.session
    }));
  }

  public getSessionByDisplayId(
    displayId: number
  ): VirtualDisplaySession | undefined {
    const item = this.#sessions.get(displayId);
    return item ? { ...item.session } : undefined;
  }

  public getSessionByPackage(
    packageName: string
  ): VirtualDisplaySession | undefined {
    for (const item of this.#sessions.values()) {
      if (item.session.packageName === packageName) {
        return { ...item.session };
      }
    }
    return undefined;
  }

  public async launch(
    serial: string,
    packageName: string,
    options: VirtualDisplayOptions = {}
  ): Promise<VirtualDisplaySession> {
    // If already running for this package, return existing session
    const existing = this.getSessionByPackage(packageName);
    if (existing) {
      return existing;
    }

    const env = options.environment ?? this.#environment;
    const scrcpyPath =
      options.scrcpyPath ??
      this.#scrcpyPath ??
      resolveScrcpyPath({ env });

    const initialDisplays = await this.#adb.listDisplays(serial);
    const initialIds = new Set(initialDisplays.map((d) => d.displayId));

    const args = buildVirtualDisplayScrcpyArgs(serial, packageName, {
      width: options.width,
      height: options.height,
      dpi: options.dpi,
      mouseMode: options.mouseMode,
      noDecorations: options.noDecorations
    });

    let child: ChildProcess;
    const stderrChunks: Buffer[] = [];
    try {
      child = spawn(scrcpyPath, [...args], {
        shell: false,
        windowsHide: false,
        stdio: ["ignore", "ignore", "pipe"]
      });
    } catch (error) {
      throw new PhoneControlError(
        "VIRTUAL_DISPLAY_FAILED",
        `Could not spawn scrcpy for virtual display: ${
          error instanceof Error ? error.message : String(error)
        }`,
        { packageName, cause: String(error) }
      );
    }

    let exitedEarly = false;
    let exitCode: number | null = null;
    child.stderr?.on("data", (chunk: Buffer | string) => {
      stderrChunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
    });
    child.once("exit", (code) => {
      exitedEarly = true;
      exitCode = code;
    });

    const timeoutMs = options.launchTimeoutMs ?? DEFAULT_LAUNCH_TIMEOUT_MS;
    const pollIntervalMs = options.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS;
    const startTime = Date.now();

    let detectedDisplayId: number | undefined;

    while (Date.now() - startTime < timeoutMs) {
      if (exitedEarly) {
        const stderr = Buffer.concat(stderrChunks).toString("utf8");
        throw new PhoneControlError(
          "VIRTUAL_DISPLAY_FAILED",
          `scrcpy exited early with code ${exitCode} while creating virtual display for '${packageName}'.`,
          { packageName, exitCode, stderr: stderr.slice(0, 500) }
        );
      }

      const currentDisplays = await this.#adb.listDisplays(serial);
      const newDisplay = currentDisplays.find((d) => !initialIds.has(d.displayId));
      if (newDisplay) {
        detectedDisplayId = newDisplay.displayId;
        break;
      }

      await this.#sleep(pollIntervalMs);
    }

    if (detectedDisplayId === undefined) {
      if (!child.killed) child.kill();
      const stderr = Buffer.concat(stderrChunks).toString("utf8");
      throw new PhoneControlError(
        "VIRTUAL_DISPLAY_FAILED",
        `Timed out waiting for virtual display to be created for '${packageName}'.`,
        { packageName, timeoutMs, stderr: stderr.slice(0, 500) }
      );
    }

    const displayId = detectedDisplayId;
    const displayInfo = await this.#adb.getDisplay(serial, displayId);

    const session: VirtualDisplaySession = {
      displayId,
      packageName,
      activity: null,
      width: displayInfo.display.width,
      height: displayInfo.display.height,
      startedAt: Date.now()
    };

    const spawned: SpawnedVirtualDisplay = {
      session,
      process: child
    };

    this.#sessions.set(displayId, spawned);

    child.once("exit", () => {
      this.#sessions.delete(displayId);
    });

    return { ...session };
  }

  public close(target: {
    displayId?: number;
    packageName?: string;
  }): boolean {
    if (target.displayId !== undefined) {
      const item = this.#sessions.get(target.displayId);
      if (item) {
        if (!item.process.killed) item.process.kill();
        this.#sessions.delete(target.displayId);
        return true;
      }
      return false;
    }

    if (target.packageName !== undefined) {
      for (const [id, item] of this.#sessions.entries()) {
        if (item.session.packageName === target.packageName) {
          if (!item.process.killed) item.process.kill();
          this.#sessions.delete(id);
          return true;
        }
      }
      return false;
    }

    return false;
  }

  public closeAll(): void {
    for (const item of this.#sessions.values()) {
      if (!item.process.killed) {
        item.process.kill();
      }
    }
    this.#sessions.clear();
  }
}
