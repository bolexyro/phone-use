import { existsSync, readFileSync } from "node:fs";
import { isAbsolute, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { PhoneControlError } from "./errors.js";
import type { PolicyProfile } from "./types.js";

export const DEFAULT_PROFILE = "local";
export const DEFAULT_CONFIG_PATH = ["config", "phone-control.json"];

const PACKAGE_ROOT = resolve(fileURLToPath(import.meta.url), "../..");

export interface LoadedPolicy extends PolicyProfile {
  configPath: string;
}

export interface LoadPolicyOptions {
  configPath?: string;
  cwd?: string;
  env?: NodeJS.ProcessEnv;
  profile?: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function resolveProfile(env: NodeJS.ProcessEnv = process.env): string {
  const configured = env.PHONE_CONTROL_PROFILE?.trim();
  return configured || DEFAULT_PROFILE;
}

export function resolveConfigPath(
  options: Pick<LoadPolicyOptions, "configPath" | "cwd" | "env"> = {}
): string {
  const cwd = options.cwd ?? process.cwd();
  const configured = options.configPath ?? options.env?.PHONE_CONTROL_CONFIG_PATH;
  const candidate = configured || DEFAULT_CONFIG_PATH.join("/");
  if (isAbsolute(candidate)) {
    return candidate;
  }
  const inCwd = resolve(cwd, candidate);
  if (existsSync(inCwd)) {
    return inCwd;
  }
  return resolve(PACKAGE_ROOT, candidate);
}

function invalidPolicy(message: string, details: Record<string, unknown> = {}): never {
  throw new PhoneControlError("POLICY_INVALID", message, details);
}

function parsePolicyDocument(raw: unknown, configPath: string, profile: string): LoadedPolicy {
  if (!isRecord(raw) || !isRecord(raw.profiles)) {
    return invalidPolicy("Policy config must contain a profiles object.", {
      configPath
    });
  }

  const profileValue = raw.profiles[profile];
  if (!isRecord(profileValue)) {
    return invalidPolicy(`Policy profile '${profile}' was not found.`, {
      configPath,
      profile
    });
  }

  const allowedApps = profileValue.allowedApps;
  if (
    !Array.isArray(allowedApps) ||
    allowedApps.some((packageName) => typeof packageName !== "string")
  ) {
    return invalidPolicy("Policy profile allowedApps must be an array of strings.", {
      configPath,
      profile
    });
  }

  const normalizedApps = allowedApps.map((packageName) => packageName.trim());
  if (normalizedApps.some((packageName) => packageName.length === 0)) {
    return invalidPolicy("Policy profile allowedApps cannot contain empty values.", {
      configPath,
      profile
    });
  }

  return Object.freeze({
    profile,
    configPath,
    allowedApps: Object.freeze([...new Set(normalizedApps)])
  });
}

export function loadPolicy(options: LoadPolicyOptions = {}): LoadedPolicy {
  const env = options.env ?? process.env;
  const profile = options.profile?.trim() || resolveProfile(env);
  const configPath = resolveConfigPath({
    configPath: options.configPath,
    cwd: options.cwd,
    env
  });

  let text: string;
  try {
    text = readFileSync(configPath, "utf8");
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new PhoneControlError(
      "POLICY_NOT_FOUND",
      `Policy config could not be read at '${configPath}'.`,
      { configPath, cause: message }
    );
  }

  let raw: unknown;
  try {
    raw = JSON.parse(text) as unknown;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new PhoneControlError(
      "POLICY_INVALID",
      `Policy config is not valid JSON at '${configPath}'.`,
      { configPath, cause: message }
    );
  }

  return parsePolicyDocument(raw, configPath, profile);
}
