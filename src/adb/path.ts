import { existsSync } from "node:fs";
import { delimiter, isAbsolute, join, resolve } from "node:path";

import { PhoneControlError } from "../errors.js";

export interface AdbPathResolutionOptions {
  env?: NodeJS.ProcessEnv;
  cwd?: string;
  platform?: NodeJS.Platform;
  fileExists?: (candidate: string) => boolean;
}

function absoluteCandidate(candidate: string, cwd: string): string {
  return isAbsolute(candidate) ? candidate : resolve(cwd, candidate);
}

function pathEntries(value: string, platform: NodeJS.Platform): string[] {
  const separator = platform === "win32" ? ";" : delimiter;
  return value
    .split(separator)
    .map((entry) => entry.trim().replace(/^"|"$/g, ""))
    .filter((entry) => entry.length > 0);
}

export function resolveAdbPath(
  options: AdbPathResolutionOptions = {}
): string {
  const env = options.env ?? process.env;
  const cwd = options.cwd ?? process.cwd();
  const platform = options.platform ?? process.platform;
  const fileExists = options.fileExists ?? existsSync;
  const attempted: string[] = [];

  const check = (candidate: string): string | undefined => {
    const absolute = absoluteCandidate(candidate, cwd);
    attempted.push(absolute);
    return fileExists(absolute) ? absolute : undefined;
  };

  const configuredPath = env.PHONE_CONTROL_ADB_PATH?.trim();
  if (configuredPath) {
    const selected = check(configuredPath);
    if (selected) {
      return selected;
    }
  }

  const bundledPath = join(cwd, "scrcpy-win64-v4.1", "adb.exe");
  const bundled = check(bundledPath);
  if (bundled) {
    return bundled;
  }

  const configuredPathValue = env.Path ?? env.PATH ?? "";
  const executableNames =
    platform === "win32" ? ["adb.exe", "adb"] : ["adb", "adb.exe"];
  for (const directory of pathEntries(configuredPathValue, platform)) {
    for (const executableName of executableNames) {
      const selected = check(join(directory, executableName));
      if (selected) {
        return selected;
      }
    }
  }

  throw new PhoneControlError(
    "ADB_NOT_FOUND",
    "ADB was not found in PHONE_CONTROL_ADB_PATH, the bundled scrcpy directory, or PATH.",
    { attempted }
  );
}
