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
const UI_AUTOMATOR_DUMP_PATH_PREFIX = "/sdcard/phone_control_window_dump";
let uiAutomatorDumpSequence = 0;

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

export function buildUiDumpArgs(
  serial: string,
  dumpPath = UI_AUTOMATOR_DUMP_PATH
): readonly string[] {
  return [
    "-s",
    serial,
    "shell",
    "uiautomator",
    "dump",
    "--compressed",
    dumpPath
  ];
}

export function buildUiDumpReadArgs(
  serial: string,
  dumpPath = UI_AUTOMATOR_DUMP_PATH
): readonly string[] {
  return ["-s", serial, "exec-out", "cat", dumpPath];
}

/**
 * Resolve the physical/SurfaceFlinger id for one requested logical display.
 *
 * `screencap -d` consumes an id emitted by SurfaceFlinger, not an arbitrary
 * logical display id. Never use the first virtual-display-looking line: with
 * multiple sessions that can silently capture another display. The matching
 * line must identify the requested display explicitly.
 */
export function parseSurfaceFlingerDisplayId(
  output: string,
  logicalDisplayId: number,
  logicalUniqueId?: string
): string | undefined {
  const requested = String(logicalDisplayId);

  // Android has separate logical and SurfaceFlinger display identifiers. On
  // current releases the only reliable bridge is DisplayInfo.uniqueId: the
  // same uniqueId is printed beside the SurfaceFlinger id. Prefer that bridge
  // whenever it is available instead of comparing unrelated integer spaces.
  if (logicalUniqueId) {
    const matchingIds = new Set<string>();
    for (const line of output.split(/\r?\n/)) {
      if (!line.includes(logicalUniqueId)) {
        continue;
      }
      const displayMatch = line.match(
        /^\s*(?:Virtual\s+)?Display\s+(\d+)\b/i
      );
      if (displayMatch) {
        matchingIds.add(displayMatch[1]);
      }
    }
    if (matchingIds.size > 0) {
      return matchingIds.size === 1 ? [...matchingIds][0] : undefined;
    }

    // Physical DisplayInfo ids are normally local:<physical-id>. Validate
    // that token against the actual SurfaceFlinger display list before using
    // it; never pass an unverified unique-id suffix to screencap.
    const localId = logicalUniqueId.match(/^local:(\d+)$/i)?.[1];
    if (localId) {
      const physicalLine = output.split(/\r?\n/).find((line) => {
        const match = line.match(/^\s*Display\s+(\d+)\b/i);
        return match?.[1] === localId;
      });
      if (physicalLine) {
        return localId;
      }
    }

    // We found the logical identity but SurfaceFlinger did not expose the
    // same identity. Do not fall back to comparing unrelated numeric ids.
    return undefined;
  }

  for (const line of output.split(/\r?\n/)) {
    const virtualMatch = line.match(
      /^\s*Virtual\s+Display\s+(\d+)\b.*$/i
    );
    if (virtualMatch?.[1] === requested) {
      return virtualMatch[1];
    }

    // Some vendor builds label virtual displays as `Display <id>` and put
    // the virtual/scrcpy marker later on the same line. Require both the
    // exact id and the marker so a neighbouring display cannot be selected.
    const displayMatch = line.match(/^\s*Display\s+(\d+)\b(.*)$/i);
    if (
      displayMatch?.[1] === requested &&
      /virtual\s+display|scrcpy/i.test(displayMatch[2] ?? "")
    ) {
      return displayMatch[1];
    }
  }
  return undefined;
}

/**
 * Resolve a virtual SurfaceFlinger id from the compact `--displays` dump when
 * the Android build does not print virtual displays in `--display-id`.
 *
 * This is deliberately a uniqueness check, not a first-match heuristic. The
 * virtual display manager creates displays named `scrcpy`; if more than one
 * such candidate is present and the build exposes no unique-id bridge, there
 * is no safe host-only way to bind one to a logical display.
 */
export function parseUniqueSurfaceFlingerVirtualDisplayId(
  output: string,
  displayName = "scrcpy"
): string | undefined {
  const expectedName = displayName.toLowerCase();
  const candidates = new Set<string>();
  let currentVirtualId: string | null = null;

  for (const line of output.split(/\r?\n/)) {
    const singleLineMatch =
      line.match(
        /^\s*Display\s+(\d+)\b.*?Virtual\s+display.*?displayName="([^"]+)"/i
      ) ??
      line.match(/\bDisplayDevice\{\s*(\d+),\s*virtual,\s*"([^"]+)"/i) ??
      line.match(
        /^\s*Display\s+(\d+)\b.*?\(virtual,\s*"([^"]+)"\)/i
      ) ??
      line.match(
        /^\s*Virtual\s+Display\s+(\d+)\b.*?\(([^)]+)\)/i
      );

    if (
      singleLineMatch &&
      singleLineMatch[2].toLowerCase() === expectedName
    ) {
      candidates.add(singleLineMatch[1]);
      currentVirtualId = null;
      continue;
    }

    const blockHeaderMatch =
      line.match(/^\s*Virtual\s+Display\s+(\d+)\b/i) ??
      line.match(/^\s*Display\s+(\d+)\b.*?\bVirtual\s+display\b/i);
    if (blockHeaderMatch) {
      currentVirtualId = blockHeaderMatch[1];
      const inlineNameMatch = line.match(
        /\b(?:displayName|name)="([^"]+)"/i
      );
      if (inlineNameMatch) {
        if (inlineNameMatch[1].toLowerCase() === expectedName) {
          candidates.add(currentVirtualId);
        }
        currentVirtualId = null;
      }
      continue;
    }

    if (currentVirtualId) {
      const nameMatch = line.match(/\b(?:displayName|name)="([^"]+)"/i);
      if (nameMatch) {
        if (nameMatch[1].toLowerCase() === expectedName) {
          candidates.add(currentVirtualId);
        }
        currentVirtualId = null;
      } else if (/^\s*(?:Virtual\s+)?Display\s+\d+\b/i.test(line)) {
        currentVirtualId = null;
      }
    }
  }

  return candidates.size === 1 ? [...candidates][0] : undefined;
}

/**
 * Extract DisplayInfo.uniqueId for one Android logical display. `cmd display
 * get-displays` prints the logical id and unique id on the same line on
 * supported Android versions; the line-oriented parser also accepts the
 * equivalent DisplayInfo spelling used by dumpsys output.
 */
export function parseLogicalDisplayUniqueId(
  output: string,
  logicalDisplayId: number
): string | undefined {
  const requested = String(logicalDisplayId);
  const uniqueIdPattern = /\buniqueId\s*["'=:\s]+"([^"]+)"/i;
  for (const line of output.split(/\r?\n/)) {
    const displayId =
      line.match(/\bDisplay\s+id\s*[:=]\s*(\d+)\b/i)?.[1] ??
      line.match(/\bdisplayId\s*[:=]?\s*(\d+)\b/i)?.[1] ??
      line.match(/\bmDisplayId\s*[:=]\s*(\d+)\b/i)?.[1];
    if (displayId !== requested) {
      continue;
    }
    const uniqueId = line.match(uniqueIdPattern)?.[1];
    if (uniqueId) {
      return uniqueId;
    }
  }
  return undefined;
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

  public async dumpUiAutomatorXml(
    serial: string,
    displayId = 0
  ): Promise<string> {
    // `uiautomator dump` has no display-id selector on the supported Android
    // versions. A dump from a secondary display would therefore be the
    // focused/default (usually display 0) hierarchy, so never return it as
    // semantic metadata for another display. The service keeps secondary
    // captures visual-only until a genuinely display-scoped adapter exists.
    if (displayId !== 0) {
      throw new PhoneControlError(
        "OBSERVATION_FAILED",
        "Android UI Automator cannot provide display-scoped metadata for a secondary display.",
        { displayId, visualOnly: true }
      );
    }

    // Each dump gets an isolated device path. The old shared path allowed
    // concurrent observations to rm/write/read one another's XML.
    const dumpPath = `${UI_AUTOMATOR_DUMP_PATH_PREFIX}-${process.pid}-${Date.now()}-${uiAutomatorDumpSequence++}.xml`;
    try {
      try {
        await this.#runText(["-s", serial, "shell", "rm", "-f", dumpPath]);
      } catch {
        // Ignore failure to remove a path that should not exist yet.
      }

      const output = await this.#runText(buildUiDumpArgs(serial, dumpPath));
      if (/error|exception|failed/i.test(output)) {
        throw new PhoneControlError(
          "OBSERVATION_FAILED",
          "Android UI Automator could not write its server-owned dump file.",
          { outputPrefix: output.slice(0, 160) }
        );
      }

      const dump = await this.#run(buildUiDumpReadArgs(serial, dumpPath));
      const xml = dump.stdout.toString("utf8");
      const xmlStart = xml.search(/(?:<\?xml|<hierarchy\b)/i);
      if (xmlStart < 0) {
        throw new PhoneControlError(
          "OBSERVATION_FAILED",
          "The server-owned UI Automator dump file did not contain XML.",
          { outputPrefix: xml.slice(0, 160) }
        );
      }
      return xml.slice(xmlStart);
    } finally {
      try {
        await this.#runText(["-s", serial, "shell", "rm", "-f", dumpPath]);
      } catch {
        // Cleanup is best effort; the path is unique and contains no user data.
      }
    }
  }

  public async captureScreenshot(
    serial: string,
    displayId = 0
  ): Promise<Uint8Array> {
    let sfDisplayId: string | undefined;
    if (displayId > 0) {
      let displayInfoOutput = "";
      try {
        // `cmd display get-displays` exposes the logical display id and its
        // DisplayInfo.uniqueId together. That unique id is the only stable
        // bridge to SurfaceFlinger's physical/virtual capture id.
        displayInfoOutput = await this.#runText([
          "-s",
          serial,
          "shell",
          "cmd",
          "display",
          "get-displays"
        ]);
      } catch (error) {
        if (error instanceof PhoneControlError && error.code === "ADB_TIMEOUT") {
          throw error;
        }
        try {
          // Older Android builds do not expose `cmd display get-displays`.
          displayInfoOutput = await this.#runText([
            "-s",
            serial,
            "shell",
            "dumpsys",
            "display"
          ]);
        } catch (fallbackError) {
          if (
            fallbackError instanceof PhoneControlError &&
            fallbackError.code === "ADB_TIMEOUT"
          ) {
            throw fallbackError;
          }
        }
      }

      const logicalUniqueId = parseLogicalDisplayUniqueId(
        displayInfoOutput,
        displayId
      );
      let sfOutput = "";
      try {
        sfOutput = await this.#runText([
          "-s",
          serial,
          "shell",
          "dumpsys",
          "SurfaceFlinger",
          "--display-id"
        ]);
      } catch (error) {
        if (error instanceof PhoneControlError && error.code === "ADB_TIMEOUT") {
          throw error;
        }
      }

      sfDisplayId =
        parseSurfaceFlingerDisplayId(sfOutput, displayId, logicalUniqueId) ??
        parseUniqueSurfaceFlingerVirtualDisplayId(sfOutput);

      if (sfDisplayId === undefined) {
        try {
          // Android 14/15/16 commonly restrict `--display-id` to physical
          // displays or omit the uniqueId token. The compact `--displays`
          // dump still includes virtual DisplayDevice/Virtual Display ids, so
          // use it only when exactly one candidate has scrcpy's display name.
          const displaysOutput = await this.#runText([
            "-s",
            serial,
            "shell",
            "dumpsys",
            "SurfaceFlinger",
            "--displays"
          ]);
          sfDisplayId =
            parseSurfaceFlingerDisplayId(
              displaysOutput,
              displayId,
              logicalUniqueId
            ) ?? parseUniqueSurfaceFlingerVirtualDisplayId(displaysOutput);
        } catch (error) {
          if (error instanceof PhoneControlError && error.code === "ADB_TIMEOUT") {
            throw error;
          }
        }
      }

      if (sfDisplayId === undefined) {
        throw new PhoneControlError(
          "OBSERVATION_FAILED",
          `SurfaceFlinger did not expose a capture id provably bound to logical display ${displayId}.`,
          { displayId, visualOnly: true }
        );
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
