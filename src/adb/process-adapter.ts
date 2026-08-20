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
  parseDisplaySnapshot,
  parseForegroundOutput,
  parsePngDimensions
} from "./process-parsers.js";
import type { FixedAdbAdapter, SwipeGesture } from "./adapter.js";
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

export function buildTypeTextArgs(
  serial: string,
  text: string
): readonly string[] {
  return [
    "-s",
    serial,
    "shell",
    "input",
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

  public async getForeground(serial: string): Promise<ForegroundState> {
    const activityOutput = await this.#runText([
      "-s",
      serial,
      "shell",
      "dumpsys",
      "activity",
      "activities"
    ]);
    const activityForeground = parseForegroundOutput(activityOutput);
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
    return parseForegroundOutput(windowOutput);
  }

  public async getDisplay(serial: string): Promise<DisplaySnapshot> {
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
    return parseDisplaySnapshot(sizeOutput, windowOutput);
  }

  public async dumpUiAutomatorXml(serial: string): Promise<string> {
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

  public async captureScreenshot(serial: string): Promise<Uint8Array> {
    const result = await this.#run([
      "-s",
      serial,
      "exec-out",
      "screencap",
      "-p"
    ]);
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

  public async tap(serial: string, x: number, y: number): Promise<void> {
    await this.#runText([
      "-s",
      serial,
      "shell",
      "input",
      "tap",
      String(x),
      String(y)
    ]);
  }

  public async swipe(serial: string, gesture: SwipeGesture): Promise<void> {
    await this.#runText([
      "-s",
      serial,
      "shell",
      "input",
      "swipe",
      String(gesture.x1),
      String(gesture.y1),
      String(gesture.x2),
      String(gesture.y2),
      String(gesture.durationMs)
    ]);
  }

  public async typeText(serial: string, text: string): Promise<void> {
    await this.#runText(buildTypeTextArgs(serial, text));
  }

  public async keypress(serial: string, key: Keypress): Promise<void> {
    await this.#runText([
      "-s",
      serial,
      "shell",
      "input",
      "keyevent",
      String(KEYCODES[key])
    ]);
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
