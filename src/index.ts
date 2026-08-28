import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { isAbsolute, resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";

import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";

import { resolveAdbPath } from "./adb/path.js";
import { AdbProcessAdapter } from "./adb/process-adapter.js";
import { NdjsonActionLogger } from "./audit-log.js";
import { loadPolicy } from "./config.js";
import { asPhoneControlError } from "./errors.js";
import { createMcpServer } from "./server.js";
import { PhoneControlService } from "./service.js";

const PACKAGE_ROOT = resolve(fileURLToPath(import.meta.url), "../..");
const DEFAULT_HTTP_HOST = "127.0.0.1";
const DEFAULT_HTTP_PORT = 3000;
const MAX_HTTP_BODY_BYTES = 1_048_576;

function createPhoneControlService(
  env: NodeJS.ProcessEnv,
  cwd: string
): PhoneControlService {
  const policy = loadPolicy({ env, cwd });
  const auditLogPath = resolveAuditLogPath(env, cwd);
  const adbPath = resolveAdbPath({ env, cwd });
  return new PhoneControlService({
    adb: new AdbProcessAdapter({ adbPath }),
    policy,
    environment: env,
    auditLogger: new NdjsonActionLogger(auditLogPath)
  });
}

export function resolveAuditLogPath(
  env: NodeJS.ProcessEnv = process.env,
  cwd = process.cwd()
): string {
  const configured = env.PHONE_CONTROL_AUDIT_LOG_PATH?.trim();
  const candidate = configured || "logs/phone-control.actions.ndjson";
  if (isAbsolute(candidate)) {
    return candidate;
  }
  return resolve(PACKAGE_ROOT, candidate);
}

export async function startPhoneControlServer(
  env: NodeJS.ProcessEnv = process.env,
  cwd = process.cwd()
) {
  console.error(
    `[phone-control] stdio server started pid=${process.pid} at=${new Date().toISOString()}`
  );
  const service = createPhoneControlService(env, cwd);
  const server = createMcpServer(service);
  await server.connect(new StdioServerTransport());
  return server;
}

function headerValue(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

function writeJson(res: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(payload)
  });
  res.end(payload);
}

async function readJsonBody(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    size += buffer.length;
    if (size > MAX_HTTP_BODY_BYTES) {
      throw new Error("Request body too large.");
    }
    chunks.push(buffer);
  }
  if (size === 0) return undefined;
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function isAuthorized(req: IncomingMessage, token: string | undefined): boolean {
  if (!token) return true;
  const authorization = headerValue(req.headers.authorization);
  return authorization === `Bearer ${token}`;
}

export async function startHttpPhoneControlServer(
  env: NodeJS.ProcessEnv = process.env,
  cwd = process.cwd()
) {
  const token = env.PHONE_CONTROL_HTTP_AUTH_TOKEN?.trim();

  const service = createPhoneControlService(env, cwd);
  const transports = new Map<string, StreamableHTTPServerTransport>();
  const mcpServers = new Map<string, ReturnType<typeof createMcpServer>>();

  const httpServer = createServer(async (req, res) => {
    const requestUrl = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);
    if (requestUrl.pathname === "/health" && req.method === "GET") {
      writeJson(res, 200, { ok: true, service: "phone-control" });
      return;
    }
    if (requestUrl.pathname !== "/mcp") {
      writeJson(res, 404, { error: "Not found." });
      return;
    }
    if (!isAuthorized(req, token)) {
      writeJson(res, 401, { error: "Unauthorized." });
      return;
    }

    try {
      const sessionId = headerValue(req.headers["mcp-session-id"]);
      const body = req.method === "POST" ? await readJsonBody(req) : undefined;
      let transport = sessionId ? transports.get(sessionId) : undefined;

      if (!transport && req.method === "POST" && body && typeof body === "object" &&
          "method" in body && body.method === "initialize") {
        const mcpServer = createMcpServer(service);
        transport = new StreamableHTTPServerTransport({
          sessionIdGenerator: () => randomUUID(),
          onsessioninitialized: (newSessionId) => {
            transports.set(newSessionId, transport!);
            mcpServers.set(newSessionId, mcpServer);
          }
        });
        transport.onclose = () => {
          const id = transport?.sessionId;
          if (id) {
            transports.delete(id);
            const closedServer = mcpServers.get(id);
            mcpServers.delete(id);
            if (closedServer) {
              void closedServer.close();
            }
          }
        };
        await mcpServer.connect(transport);
      }

      if (!transport) {
        writeJson(res, 400, {
          jsonrpc: "2.0",
          error: { code: -32000, message: "A valid MCP session is required." },
          id: null
        });
        return;
      }
      await transport.handleRequest(req, res, body);
    } catch (error) {
      console.error("[phone-control] HTTP request failed", error);
      if (!res.headersSent) {
        writeJson(res, 400, { error: "Invalid MCP request." });
      } else if (!res.writableEnded) {
        res.end();
      }
    }
  });

  const host = env.PHONE_CONTROL_HTTP_HOST?.trim() || DEFAULT_HTTP_HOST;
  const port = Number.parseInt(env.PHONE_CONTROL_HTTP_PORT ?? `${DEFAULT_HTTP_PORT}`, 10);
  await new Promise<void>((resolvePromise, reject) => {
    httpServer.once("error", reject);
    httpServer.listen(port, host, () => {
      httpServer.off("error", reject);
      resolvePromise();
    });
  });
  console.error(`[phone-control] HTTP listening on http://${host}:${port}/mcp`);
  return httpServer;
}

function isMainModule(): boolean {
  return process.argv[1]
    ? resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))
    : false;
}

if (isMainModule()) {
  const start = process.env.PHONE_CONTROL_TRANSPORT === "http"
    ? startHttpPhoneControlServer
    : startPhoneControlServer;
  void start().catch((error: unknown) => {
    const normalized = asPhoneControlError(error);
    console.error(`[phone-control] startup failed: ${normalized.code}: ${normalized.message}`);
    process.exitCode = 1;
  });
}
