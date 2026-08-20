import { isAbsolute, resolve } from "node:path";

export interface ViewerConfig {
  x: number;
  y: number;
  width: number;
  height: number;
  cursorDurationMs: number;
  auditLogPath: string;
  auditPollIntervalMs: number;
}

export const DEFAULT_VIEWER_GEOMETRY = {
  x: 60,
  y: 60,
  width: 432,
  height: 936
} as const;

function integerEnv(
  env: NodeJS.ProcessEnv,
  name: string,
  fallback: number,
  minimum?: number
): number {
  const parsed = Number.parseInt(env[name] ?? "", 10);
  if (!Number.isFinite(parsed) || (minimum !== undefined && parsed < minimum)) {
    return fallback;
  }
  return parsed;
}

export function resolveViewerAuditLogPath(
  env: NodeJS.ProcessEnv = process.env,
  cwd = process.cwd()
): string {
  const configured = env.PHONE_CONTROL_AUDIT_LOG_PATH?.trim();
  const candidate = configured || "logs/phone-control.actions.ndjson";
  return isAbsolute(candidate) ? candidate : resolve(cwd, candidate);
}

export function loadViewerConfig(
  env: NodeJS.ProcessEnv = process.env,
  cwd = process.cwd()
): ViewerConfig {
  return {
    x: integerEnv(env, "PHONE_CONTROL_VIEWER_X", DEFAULT_VIEWER_GEOMETRY.x),
    y: integerEnv(env, "PHONE_CONTROL_VIEWER_Y", DEFAULT_VIEWER_GEOMETRY.y),
    width: integerEnv(
      env,
      "PHONE_CONTROL_VIEWER_WIDTH",
      DEFAULT_VIEWER_GEOMETRY.width,
      1
    ),
    height: integerEnv(
      env,
      "PHONE_CONTROL_VIEWER_HEIGHT",
      DEFAULT_VIEWER_GEOMETRY.height,
      1
    ),
    cursorDurationMs: integerEnv(
      env,
      "PHONE_CONTROL_VIEWER_CURSOR_DURATION_MS",
      700,
      1
    ),
    auditLogPath: resolveViewerAuditLogPath(env, cwd),
    auditPollIntervalMs: integerEnv(
      env,
      "PHONE_CONTROL_VIEWER_AUDIT_POLL_INTERVAL_MS",
      100,
      20
    )
  };
}
