import { isAbsolute, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

import { resolveAdbPath } from "./adb/path.js";
import { AdbProcessAdapter } from "./adb/process-adapter.js";
import { NdjsonActionLogger } from "./audit-log.js";
import { loadPolicy } from "./config.js";
import { asPhoneControlError } from "./errors.js";
import { createMcpServer } from "./server.js";
import { PhoneControlService } from "./service.js";

export function resolveAuditLogPath(
  env: NodeJS.ProcessEnv = process.env,
  cwd = process.cwd()
): string {
  const configured = env.PHONE_CONTROL_AUDIT_LOG_PATH?.trim();
  const candidate = configured || "logs/phone-control.actions.ndjson";
  return isAbsolute(candidate) ? candidate : resolve(cwd, candidate);
}

export async function startPhoneControlServer(
  env: NodeJS.ProcessEnv = process.env,
  cwd = process.cwd()
) {
  const policy = loadPolicy({ env, cwd });
  const auditLogPath = resolveAuditLogPath(env, cwd);
  const adbPath = resolveAdbPath({ env, cwd });
  const service = new PhoneControlService({
    adb: new AdbProcessAdapter({ adbPath }),
    policy,
    environment: env,
    auditLogger: new NdjsonActionLogger(auditLogPath)
  });
  const server = createMcpServer(service);
  await server.connect(new StdioServerTransport());
  return server;
}

function isMainModule(): boolean {
  return process.argv[1]
    ? resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))
    : false;
}

if (isMainModule()) {
  void startPhoneControlServer().catch((error: unknown) => {
    const normalized = asPhoneControlError(error);
    console.error(`[phone-control] startup failed: ${normalized.code}: ${normalized.message}`);
    process.exitCode = 1;
  });
}
