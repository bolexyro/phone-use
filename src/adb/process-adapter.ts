import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { Buffer } from "node:buffer";

import { PhoneControlError } from "../errors.js";
import type {
  DeviceInfo,
  DisplaySnapshot,
  ForegroundState,
  Keypress
} from "../types.js";
import {
  parseAdbDevicesOutput,
  parseDisplayMetrics,
  parseDisplaysList,
  parseDisplaySnapshotForId,
  parseForegroundForDisplay,
  parseForegroundOutput,
  parsePngDimensions
} from "./process-parsers.js";
import type {
  FixedAdbAdapter,
  SwipeGesture,
  TapBatchHooks,
  TapBatchResult,
  TapPoint
} from "./adapter.js";
import { resolveAdbPath } from "./path.js";

interface CompletedProcess {
  stdout: Buffer;
  stderr: Buffer;
  exitCode: number;
}

interface ProcessAdapterOptions {
  adbPath?: string;
  timeoutMs?: number;
}

const DEFAULT_TIMEOUT_MS = 15_000;
export const UI_AUTOMATOR_DUMP_PATH = "/sdcard/window_dump.xml";

const KEYCODES: Record<Keypress, number> = {
  BACK: 4,
  HOME: 3,
  ENTER: 66,
  DELETE: 67
};

export function encodeAndroidInputText(value: string): string {
  return value.replaceAll("%", "%25").replace(/\s/g, "%s");
}

export function buildLaunchArgs(
  serial: string,
  packageName: string
): readonly string[] {
  return [
    "-s",
    serial,
    "shell",
    "monkey",
    "-p",
    packageName,
    "-c",
    "android.intent.category.LAUNCHER",
    "1"
  ];
}

export function buildLaunchOnDisplayArgs(
  serial: string,
  componentName: string,
  displayId: number,
  options: { multipleTask?: boolean } = {}
): readonly string[] {
  return [
    "-s",
    serial,
    "shell",
    "am",
    "start",
    "-W",
    "--display",
    String(displayId),
    "-f",
    options.multipleTask === true ? "0x18080000" : "0x10000000",
    "-n",
    componentName
  ];
}

export function buildResolveLaunchActivityArgs(
  serial: string,
  packageName: string
): readonly string[] {
  return [
    "-s",
    serial,
    "shell",
    "cmd",
    "package",
    "resolve-activity",
    "--brief",
    "-a",
    "android.intent.action.MAIN",
    "-c",
    "android.intent.category.LAUNCHER",
    packageName
  ];
}

export function buildTypeTextArgs(
  serial: string,
  text: string,
  displayId = 0
): readonly string[] {
  return [
    "-s",
    serial,
    "shell",
    "input",
    ...(displayId > 0 ? ["-d", String(displayId)] : []),
    "text",
    encodeAndroidInputText(text)
  ];
}

export function buildUiDumpArgs(serial: string): readonly string[] {
  return [
    "-s",
    serial,
    "shell",
    "uiautomator",
    "dump",
    "--compressed",
    UI_AUTOMATOR_DUMP_PATH
  ];
}

export function buildUiDumpReadArgs(serial: string): readonly string[] {
  return ["-s", serial, "exec-out", "cat", UI_AUTOMATOR_DUMP_PATH];
}

function isLaunchFailure(output: string): boolean {
  return /(?:^|\n)\s*(?:Error|Exception):|START_ABORTED|does not exist/i.test(
    output
  );
}

export function parseResolvedLaunchActivity(
  output: string,
  packageName: string
): string | undefined {
  const lines = output.split(/\r?\n/).map((line) => line.trim());
  for (let index = lines.length - 1; index >= 0; index -= 1) {
    if (lines[index]?.startsWith(`${packageName}/`) === true) {
      return lines[index];
    }
  }
  return undefined;
}

export class AdbProcessAdapter implements FixedAdbAdapter {
  readonly adbPath: string;
  readonly timeoutMs: number;

  public constructor(options: ProcessAdapterOptions = {}) {
    this.adbPath = options.adbPath ?? resolveAdbPath();
    this.timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  }

  public async listDevices(): Promise<readonly DeviceInfo[]> {
    const result = await this.#run(["devices", "-l"]);
    return parseAdbDevicesOutput(result.stdout.toString("utf8"));
  }

  public async getApiLevel(serial: string): Promise<number> {
    const output = await this.#runText([
      "-s",
      serial,
      "shell",
      "getprop",
      "ro.build.version.sdk"
    ]);
    const level = parseInt(output.trim(), 10);
    return Number.isFinite(level) ? level : 0;
  }

  public async listDisplays(
    serial: string
  ): Promise<
    readonly {
      displayId: number;
      width: number;
      height: number;
      rotation: number;
    }[]
  > {
    const windowOutput = await this.#runText([
      "-s",
      serial,
      "shell",
      "dumpsys",
      "window",
      "displays"
    ]);
    const displays = parseDisplaysList(windowOutput);
    if (displays.length === 0) {
      const sizeOutput = await this.#runText([
        "-s",
        serial,
        "shell",
        "wm",
        "size"
      ]);
      const display = parseDisplayMetrics(sizeOutput);
      return [
        {
          displayId: 0,
          width: display.width,
          height: display.height,
          rotation: 0
        }
      ];
    }
    return displays;
  }

  public async getForeground(
    serial: string,
    displayId = 0
  ): Promise<ForegroundState> {
    const activityOutput = await this.#runText([
      "-s",
      serial,
      "shell",
      "dumpsys",
      "activity",
      "activities"
    ]);
    const activityForeground = parseForegroundForDisplay(
      activityOutput,
      displayId
    );
    if (activityForeground.packageName) {
      return activityForeground;
    }

    const windowOutput = await this.#runText([
      "-s",
      serial,
      "shell",
      "dumpsys",
      "window",
      "windows"
    ]);
    return parseForegroundForDisplay(windowOutput, displayId);
  }

  public async getDisplay(
    serial: string,
    displayId = 0
  ): Promise<DisplaySnapshot> {
    const sizeOutput = await this.#runText([
      "-s",
      serial,
      "shell",
      "wm",
      "size"
    ]);
    const windowOutput = await this.#runText([
      "-s",
      serial,
      "shell",
      "dumpsys",
      "window",
      "displays"
    ]);
    return parseDisplaySnapshotForId(sizeOutput, windowOutput, displayId);
  }

  public async dumpUiAutomatorXml(serial: string, _displayId = 0): Promise<string> {
    // `uiautomator dump` does not expose a display-id argument. Keep this
    // limitation explicit: screenshot/foreground are display-aware, while
    // shell UI metadata is the device's default hierarchy.
    try {
      await this.#runText(["-s", serial, "shell", "rm", "-f", UI_AUTOMATOR_DUMP_PATH]);
    } catch {
      // Ignore failure to remove prior file
    }
    const output = await this.#runText(buildUiDumpArgs(serial));
    if (/error|exception|failed/i.test(output)) {
      throw new PhoneControlError(
        "OBSERVATION_FAILED",
        "Android UI Automator could not write its fixed dump file.",
        { outputPrefix: output.slice(0, 160) }
      );
    }
    const dump = await this.#run(buildUiDumpReadArgs(serial));
    const xml = dump.stdout.toString("utf8");
    const xmlStart = xml.search(/(?:<\?xml|<hierarchy\b)/i);
    if (xmlStart < 0) {
      throw new PhoneControlError(
        "OBSERVATION_FAILED",
        "The fixed UI Automator dump file did not contain XML.",
        { outputPrefix: xml.slice(0, 160) }
      );
    }
    return xml.slice(xmlStart);
  }

  public async captureScreenshot(
    serial: string,
    displayId = 0
  ): Promise<Uint8Array> {
    let sfDisplayId: string | undefined;
    if (displayId > 0) {
      try {
        const sfOutput = await this.#runText([
          "-s",
          serial,
          "shell",
          "dumpsys",
          "SurfaceFlinger",
          "--display-id"
        ]);
        const lines = sfOutput.split("\n");
        const virtualLine = lines.find(
          (l) => l.includes("Virtual display") || l.includes("scrcpy")
        );
        if (virtualLine) {
          const match = virtualLine.match(/Display\s+(\d+)/i);
          if (match) {
            sfDisplayId = match[1];
          }
        }
      } catch {
        // Ignore failure and fallback to logical displayId
      }
    }

    const args = [
      "-s",
      serial,
      "exec-out",
      "screencap",
      ...(displayId > 0 ? ["-d", sfDisplayId ?? String(displayId)] : []),
      "-p"
    ];
    const result = await this.#run(args);
    parsePngDimensions(result.stdout);
    return Uint8Array.from(result.stdout);
  }

  public async launchApp(serial: string, packageName: string): Promise<void> {
    const output = await this.#runText(buildLaunchArgs(serial, packageName));
    if (isLaunchFailure(output)) {
      throw new PhoneControlError(
        "APP_LAUNCH_FAILED",
        `Android did not launch '${packageName}'.`,
        { packageName, output: output.slice(0, 500) }
      );
    }
  }

  public async launchAppOnDisplay(
    serial: string,
    packageName: string,
    displayId: number,
    options: { multipleTask?: boolean } = {}
  ): Promise<void> {
    const resolvedOutput = await this.#runText(
      buildResolveLaunchActivityArgs(serial, packageName)
    );
    const componentName = parseResolvedLaunchActivity(resolvedOutput, packageName);
    if (componentName === undefined) {
      throw new PhoneControlError(
        "APP_LAUNCH_FAILED",
        `Android could not resolve a launcher activity for '${packageName}'.`,
        { packageName, output: resolvedOutput.slice(0, 500) }
      );
    }
    const output = await this.#runText(
      buildLaunchOnDisplayArgs(serial, componentName, displayId, options)
    );
    if (isLaunchFailure(output)) {
      throw new PhoneControlError(
        "APP_LAUNCH_FAILED",
        `Android did not launch '${packageName}' on display ${displayId}.`,
        { packageName, displayId, output: output.slice(0, 500) }
      );
    }
  }

  public async tap(
    serial: string,
    x: number,
    y: number,
    displayId = 0
  ): Promise<void> {
    const args = [
      "-s",
      serial,
      "shell",
      "input",
      ...(displayId > 0 ? ["-d", String(displayId)] : []),
      "tap",
      String(x),
      String(y)
    ];
    await this.#runText(args);
  }

  public async tapBatch(
    serial: string,
    points: readonly TapPoint[],
    displayId = 0,
    hooks: TapBatchHooks = {}
  ): Promise<TapBatchResult> {
    let completed = 0;
    try {
      // Keep the operation behind the fixed adapter boundary. Android's
      // `input` command accepts one tap per invocation, so the adapter owns
      // the bounded transport loop while callers never provide shell args.
      for (const [index, point] of points.entries()) {
        await hooks.beforeTap?.(index, point);
        await this.tap(serial, point.x, point.y, displayId);
        completed += 1;
      }
      return { completed };
    } catch (error) {
      const normalized =
        error instanceof PhoneControlError
          ? error
          : new PhoneControlError(
              "ADB_COMMAND_FAILED",
              "ADB could not complete the tap batch."
            );
      throw new PhoneControlError(normalized.code, normalized.message, {
        ...normalized.details,
        completedSteps: completed,
        outcome: normalized.code === "ADB_TIMEOUT" ? "unknown" : "failed"
      });
    }
  }

  public async swipe(
    serial: string,
    gesture: SwipeGesture,
    displayId = 0
  ): Promise<void> {
    const args = [
      "-s",
      serial,
      "shell",
      "input",
      ...(displayId > 0 ? ["-d", String(displayId)] : []),
      "swipe",
      String(gesture.x1),
      String(gesture.y1),
      String(gesture.x2),
      String(gesture.y2),
      String(gesture.durationMs)
    ];
    await this.#runText(args);
  }

  public async typeText(
    serial: string,
    text: string,
    displayId = 0
  ): Promise<void> {
    await this.#runText(buildTypeTextArgs(serial, text, displayId));
  }

  public async keypress(
    serial: string,
    key: Keypress,
    displayId = 0
  ): Promise<void> {
    const args = [
      "-s",
      serial,
      "shell",
      "input",
      ...(displayId > 0 ? ["-d", String(displayId)] : []),
      "keyevent",
      String(KEYCODES[key])
    ];
    await this.#runText(args);
  }

  async #runText(args: readonly string[]): Promise<string> {
    const result = await this.#run(args);
    return result.stdout.toString("utf8");
  }

  async #run(args: readonly string[]): Promise<CompletedProcess> {
    return new Promise<CompletedProcess>((resolve, reject) => {
      let child: ChildProcessWithoutNullStreams;
      try {
        child = spawn(this.adbPath, [...args], {
          shell: false,
          windowsHide: true
        });
      } catch (error) {
        reject(
          new PhoneControlError("ADB_COMMAND_FAILED", "ADB could not be started.", {
            cause: error instanceof Error ? error.message : String(error)
          })
        );
        return;
      }

      const stdout: Buffer[] = [];
      const stderr: Buffer[] = [];
      let settled = false;
      const timer = setTimeout(() => {
        if (settled) {
          return;
        }
        settled = true;
        child.kill();
        reject(
          new PhoneControlError(
            "ADB_TIMEOUT",
            "ADB command timed out; its outcome is unknown and was not retried.",
            { outcome: "unknown", timeoutMs: this.timeoutMs }
          )
        );
      }, this.timeoutMs);

      child.stdout.on("data", (chunk: Buffer | string) => {
        stdout.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
      });
      child.stderr.on("data", (chunk: Buffer | string) => {
        stderr.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
      });
      child.once("error", (error) => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timer);
        reject(
          new PhoneControlError("ADB_COMMAND_FAILED", "ADB process failed.", {
            cause: error.message
          })
        );
      });
      child.once("close", (exitCode) => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timer);
        const result: CompletedProcess = {
          stdout: Buffer.concat(stdout),
          stderr: Buffer.concat(stderr),
          exitCode: exitCode ?? -1
        };
        if (result.exitCode !== 0) {
          reject(
            new PhoneControlError("ADB_COMMAND_FAILED", "ADB command failed.", {
              exitCode: result.exitCode,
              stdout: result.stdout.toString("utf8").slice(0, 500),
              stderr: result.stderr.toString("utf8").slice(0, 500)
            })
          );
          return;
        }
        resolve(result);
      });
    });
  }
}
