import { existsSync } from "node:fs";
import { isAbsolute, join, resolve } from "node:path";

import { PhoneControlError } from "../errors.js";

export interface ScrcpyPathResolutionOptions {
  env?: NodeJS.ProcessEnv;
  cwd?: string;
  platform?: NodeJS.Platform;
  fileExists?: (candidate: string) => boolean;
}

function absoluteCandidate(candidate: string, cwd: string): string {
  return isAbsolute(candidate) ? candidate : resolve(cwd, candidate);
}

export function resolveScrcpyPath(
  options: ScrcpyPathResolutionOptions = {}
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

  const configured = env.PHONE_CONTROL_SCRCPY_PATH?.trim();
  if (configured) {
    const selected = check(configured);
    if (selected) return selected;
  }

  const bundled = check(join(cwd, "scrcpy-win64-v4.1", "scrcpy.exe"));
  if (bundled) return bundled;

  const pathValue = env.Path || env.PATH || "";
  const separator = platform === "win32" ? ";" : ":";
  const names = platform === "win32" ? ["scrcpy.exe", "scrcpy"] : ["scrcpy", "scrcpy.exe"];
  for (const directory of pathValue.split(separator).map((value) => value.trim()).filter(Boolean)) {
    for (const name of names) {
      const selected = check(join(directory, name));
      if (selected) return selected;
    }
  }

  throw new PhoneControlError(
    "SCRCPY_NOT_FOUND",
    "scrcpy was not found in PHONE_CONTROL_SCRCPY_PATH, the bundled distribution, or PATH.",
    { attempted }
  );
}
