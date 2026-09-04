import { spawn, type ChildProcess } from "node:child_process";
import { existsSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

import { PhoneControlError } from "./errors.js";

export interface CursorOverlayController {
  start(): Promise<void>;
  stop(): void;
}

export interface ElectronCursorOverlayControllerOptions {
  environment?: NodeJS.ProcessEnv;
  platform?: NodeJS.Platform;
  spawnProcess?: typeof spawn;
  resolveElectronPath?: () => string;
  resolveViewerMainPath?: () => string;
}

function isDisabled(value: string | undefined): boolean {
  return value !== undefined && ["0", "false", "off", "disabled"].includes(value.trim().toLowerCase());
}

function waitForSpawn(child: ChildProcess): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const onSpawn = (): void => {
      cleanup();
      resolve();
    };
    const onError = (error: Error): void => {
      cleanup();
      reject(error);
    };
    const cleanup = (): void => {
      child.off("spawn", onSpawn);
      child.off("error", onError);
    };

    child.once("spawn", onSpawn);
    child.once("error", onError);
  });
}

export function resolveElectronExecutable(
  environment: NodeJS.ProcessEnv = process.env
): string {
  const configured = environment.PHONE_CONTROL_ELECTRON_PATH?.trim();
  if (configured) return configured;

  const viewerPackageJson = new URL(
    "../../../apps/phone-viewer/package.json",
    import.meta.url
  );
  const requireAnchors: Array<string | URL> = [import.meta.url, viewerPackageJson];
  for (const anchor of requireAnchors) {
    try {
      const resolved = createRequire(anchor)("electron");
      if (typeof resolved === "string" && resolved.trim()) {
        return resolved;
      }
    } catch {
      // Electron is a dependency of the viewer package, not necessarily of
      // the phone-control package. Try resolving it from the viewer next.
    }
  }

  throw new PhoneControlError(
    "VIEWER_START_FAILED",
    "Could not resolve Electron for the phone-control cursor viewer. Set PHONE_CONTROL_ELECTRON_PATH or install @dhd/phone-viewer dependencies."
  );
}

function resolveViewerMainPath(): string {
  const viewerMainPath = fileURLToPath(
    new URL("../../../apps/phone-viewer/dist/viewer/main.js", import.meta.url)
  );
  if (!existsSync(viewerMainPath)) {
    throw new PhoneControlError(
      "VIEWER_START_FAILED",
      `The phone-control cursor viewer is not built: ${viewerMainPath}. Run the phone-viewer build first.`,
      { viewerMainPath }
    );
  }
  return viewerMainPath;
}

export class ElectronCursorOverlayController implements CursorOverlayController {
  readonly #environment: NodeJS.ProcessEnv;
  readonly #platform: NodeJS.Platform;
  readonly #spawnProcess: typeof spawn;
  readonly #resolveElectronPath: () => string;
  readonly #resolveViewerMainPath: () => string;
  #process: ChildProcess | undefined;
  #starting: Promise<void> | undefined;

  public constructor(
    options: ElectronCursorOverlayControllerOptions = {}
  ) {
    this.#environment = options.environment ?? process.env;
    this.#platform = options.platform ?? process.platform;
    this.#spawnProcess = options.spawnProcess ?? spawn;
    this.#resolveElectronPath =
      options.resolveElectronPath ?? (() => resolveElectronExecutable(this.#environment));
    this.#resolveViewerMainPath =
      options.resolveViewerMainPath ?? resolveViewerMainPath;
  }

  public async start(): Promise<void> {
    if (
      this.#platform !== "win32" ||
      isDisabled(this.#environment.PHONE_CONTROL_CURSOR_OVERLAY)
    ) {
      return;
    }
    if (this.#process && !this.#process.killed && this.#process.exitCode === null) {
      return;
    }
    if (this.#starting) {
      return this.#starting;
    }

    this.#starting = this.#startProcess().finally(() => {
      this.#starting = undefined;
    });
    return this.#starting;
  }

  public stop(): void {
    const child = this.#process;
    this.#process = undefined;
    if (child && !child.killed && child.exitCode === null) {
      try {
        child.kill();
      } catch {
        // The process may have exited between the check and kill().
      }
    }
  }

  async #startProcess(): Promise<void> {
    const electronPath = this.#resolveElectronPath();
    const viewerMainPath = this.#resolveViewerMainPath();
    const workspaceRoot = fileURLToPath(new URL("../../../", import.meta.url));
    let child: ChildProcess;
    try {
      child = this.#spawnProcess(
        electronPath,
        [viewerMainPath, "--overlay-only"],
        {
          cwd: workspaceRoot,
          shell: false,
          windowsHide: true,
          stdio: ["ignore", "ignore", "pipe"],
          env: this.#environment
        }
      );
    } catch (error) {
      throw this.#startError(error);
    }

    this.#process = child;
    child.once("exit", () => {
      if (this.#process === child) {
        this.#process = undefined;
      }
    });

    try {
      await waitForSpawn(child);
    } catch (error) {
      if (this.#process === child) {
        this.#process = undefined;
      }
      throw this.#startError(error);
    }
  }

  #startError(error: unknown): PhoneControlError {
    return new PhoneControlError(
      "VIEWER_START_FAILED",
      `Could not start the automatic cursor overlay: ${
        error instanceof Error ? error.message : String(error)
      }`,
      { cause: String(error) }
    );
  }
}
