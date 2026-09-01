import { spawn, type ChildProcess } from "node:child_process";
import { randomUUID } from "node:crypto";
import { existsSync, watch } from "node:fs";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import http from "node:http";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";

import {
  DEFAULT_BRIDGE_HOST,
  DEFAULT_BRIDGE_PORT,
  parsePort,
  requestBridge
} from "../phone-assistant-bridge.js";
import { discoverPhone } from "../pairing.js";
import { normalizePairingCode } from "./pairing-code.js";
import type {
  BridgeCheckResult,
  BridgeStatus,
  CompanionLogEntry,
  CompanionSettingsInput,
  CompanionSettingsSnapshot,
  CompanionState,
  CompanionProcessStatus,
  PhoneSnapshot
} from "./api.js";

interface ConnectionConfig {
  host: string;
  port: number;
  token: string;
  pairingCode?: string;
  deviceId?: string;
}

interface StoredConnectionSettings {
  host?: string;
  port?: number;
  token?: string;
  pairingCode?: string;
  deviceId?: string;
}

const WEB_DIRECTORY = fileURLToPath(new URL(".", import.meta.url));
const PROJECT_ROOT = resolve(WEB_DIRECTORY, "../../");
const COMPANION_SCRIPT_JS = resolve(WEB_DIRECTORY, "../assistant-companion.js");
const COMPANION_SCRIPT_TS = resolve(PROJECT_ROOT, "src/assistant-companion.ts");
const MAX_LOG_ENTRIES = 250;
const DEFAULT_WEB_PORT = 8766;
const DEFAULT_WEB_HOST = "127.0.0.1";

let connection: ConnectionConfig;
let worker: ChildProcess | null = null;
let processStatus: CompanionProcessStatus = "stopped";
let bridgeStatus: BridgeStatus = "unknown";
let phone: PhoneSnapshot | undefined;
let lastError: string | undefined;
let logEntries: CompanionLogEntry[] = [];
const sseClients = new Set<http.ServerResponse>();

function readEnvPort(): number {
  try {
    return parsePort(process.env.PHONE_ASSISTANT_BRIDGE_PORT ?? `${DEFAULT_BRIDGE_PORT}`);
  } catch {
    return DEFAULT_BRIDGE_PORT;
  }
}

function initialConnection(): ConnectionConfig {
  return {
    host: process.env.PHONE_ASSISTANT_BRIDGE_HOST?.trim() || DEFAULT_BRIDGE_HOST,
    port: readEnvPort(),
    token: process.env.PHONE_ASSISTANT_BRIDGE_TOKEN?.trim() || ""
  };
}

function settingsPath(): string {
  return join(homedir(), ".dhd", "companion-connection.json");
}

async function loadConnection(): Promise<ConnectionConfig> {
  const defaults = initialConnection();
  let stored: StoredConnectionSettings = {};
  try {
    stored = JSON.parse(await readFile(settingsPath(), "utf8")) as StoredConnectionSettings;
  } catch {
    // Default configuration if settings file does not exist
  }

  const storedPort = typeof stored.port === "number" && Number.isInteger(stored.port)
    ? stored.port
    : defaults.port;
  const port = storedPort >= 1 && storedPort <= 65_535 ? storedPort : defaults.port;
  return {
    host: process.env.PHONE_ASSISTANT_BRIDGE_HOST?.trim() || stored.host?.trim() || defaults.host,
    port: process.env.PHONE_ASSISTANT_BRIDGE_PORT ? defaults.port : port,
    token: process.env.PHONE_ASSISTANT_BRIDGE_TOKEN?.trim() || stored.token?.trim() || "",
    ...(stored.pairingCode ? { pairingCode: stored.pairingCode.trim().toUpperCase() } : {}),
    ...(stored.deviceId ? { deviceId: stored.deviceId.trim() } : {})
  };
}

async function saveConnection(): Promise<void> {
  const stored: StoredConnectionSettings = {
    host: connection.host,
    port: connection.port,
    token: connection.token,
    ...(connection.pairingCode ? { pairingCode: connection.pairingCode } : {}),
    ...(connection.deviceId ? { deviceId: connection.deviceId } : {})
  };
  await mkdir(dirname(settingsPath()), { recursive: true });
  await writeFile(settingsPath(), `${JSON.stringify(stored, null, 2)}\n`, {
    encoding: "utf8",
    mode: 0o600
  });
}

function settingsSnapshot(): CompanionSettingsSnapshot {
  return {
    host: connection.host,
    port: connection.port,
    tokenConfigured: connection.token.length > 0,
    pairingConfigured: Boolean(connection.pairingCode)
  };
}

function snapshot(): CompanionState {
  return {
    processStatus,
    bridgeStatus,
    settings: settingsSnapshot(),
    ...(phone ? { phone } : {}),
    ...(lastError ? { lastError } : {}),
    logs: [...logEntries]
  };
}

function publishState(): void {
  const payload = `data: ${JSON.stringify(snapshot())}\n\n`;
  for (const client of sseClients) {
    try {
      client.write(payload);
    } catch {
      sseClients.delete(client);
    }
  }
}

function appendLog(
  message: string,
  options: Pick<CompanionLogEntry, "level" | "source"> = { level: "info", source: "companion" }
): void {
  const trimmed = message.trim();
  if (!trimmed) return;
  logEntries = [
    ...logEntries,
    { id: randomUUID(), timestamp: Date.now(), message: trimmed, ...options }
  ].slice(-MAX_LOG_ENTRIES);
  publishState();
}

function childOutput(child: ChildProcess, source: "companion" | "bridge"): void {
  for (const stream of [child.stdout, child.stderr]) {
    if (!stream) continue;
    let buffered = "";
    stream.setEncoding("utf8");
    stream.on("data", (chunk: string) => {
      buffered += chunk;
      let newline = buffered.indexOf("\n");
      while (newline >= 0) {
        const line = buffered.slice(0, newline).replace(/\r$/, "");
        buffered = buffered.slice(newline + 1);
        appendLog(line, {
          source,
          level: /error|failed|rejected|could not|timed out/i.test(line) ? "error" : "info"
        });
        newline = buffered.indexOf("\n");
      }
    });
    stream.on("end", () => {
      if (buffered.trim()) appendLog(buffered, { source, level: "info" });
    });
  }
}

function workerEnvironment(): NodeJS.ProcessEnv {
  return {
    ...process.env,
    PHONE_ASSISTANT_BRIDGE_HOST: connection.host,
    PHONE_ASSISTANT_BRIDGE_PORT: String(connection.port),
    PHONE_ASSISTANT_BRIDGE_TOKEN: connection.token,
    ...(connection.pairingCode ? { PHONE_ASSISTANT_PAIRING_CODE: connection.pairingCode } : {}),
    ...(process.versions.electron ? { ELECTRON_RUN_AS_NODE: "1" } : {})
  };
}

function getWorkerScript(): { command: string; args: string[] } | null {
  if (existsSync(COMPANION_SCRIPT_JS)) {
    return { command: process.execPath, args: [COMPANION_SCRIPT_JS] };
  }
  if (existsSync(COMPANION_SCRIPT_TS)) {
    return { command: process.execPath, args: ["--import", "tsx", COMPANION_SCRIPT_TS] };
  }
  const distScript = resolve(PROJECT_ROOT, "dist/assistant-companion.js");
  if (existsSync(distScript)) {
    return { command: process.execPath, args: [distScript] };
  }
  return null;
}

function startWorker(): CompanionState {
  if (worker && !worker.killed) return snapshot();

  const scriptConfig = getWorkerScript();
  if (!scriptConfig) {
    processStatus = "error";
    lastError = "Could not find assistant companion script. Build the project first.";
    appendLog(lastError, { level: "error", source: "system" });
    return snapshot();
  }

  processStatus = "starting";
  bridgeStatus = "unknown";
  lastError = undefined;
  appendLog(`Starting companion worker for ${connection.host}:${connection.port}.`, { level: "system", source: "system" });

  const child = spawn(scriptConfig.command, scriptConfig.args, {
    cwd: PROJECT_ROOT,
    env: workerEnvironment(),
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true
  });

  worker = child;
  childOutput(child, "companion");
  child.once("error", (error) => {
    if (worker !== child) return;
    worker = null;
    processStatus = "error";
    lastError = error.message;
    appendLog(`Companion worker failed: ${error.message}`, { level: "error", source: "system" });
  });
  child.once("exit", (code, signal) => {
    if (worker === child) worker = null;
    const expected = processStatus === "stopping";
    processStatus = expected || code === 0 ? "stopped" : "error";
    if (!expected && code !== 0) {
      lastError = `Companion worker exited with ${code === null ? signal ?? "unknown signal" : `code ${code}`}.`;
    }
    appendLog(
      `Companion worker ${expected ? "stopped" : "exited"}${code === null ? ` (${signal ?? "unknown"})` : ` (code ${code})`}.`,
      { level: expected || code === 0 ? "system" : "error", source: "system" }
    );
  });
  processStatus = "running";
  appendLog("Companion worker is running.", { level: "system", source: "system" });
  if (connection.token || connection.pairingCode) {
    void checkConnection({ silent: true }).catch(() => {});
  }
  return snapshot();
}

async function stopWorker(): Promise<CompanionState> {
  const child = worker;
  if (!child || child.killed) {
    worker = null;
    processStatus = "stopped";
    publishState();
    return snapshot();
  }
  processStatus = "stopping";
  appendLog("Stopping companion worker.", { level: "system", source: "system" });
  await new Promise<void>((resolveStop) => {
    let settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      resolveStop();
    };
    child.once("exit", finish);
    child.kill();
    setTimeout(() => {
      if (!settled) {
        child.kill("SIGKILL");
        finish();
      }
    }, 3_000);
  });
  return snapshot();
}

function clearLogs(): CompanionState {
  logEntries = [];
  publishState();
  return snapshot();
}

function bridgeOptions(timeoutMs: number, target: ConnectionConfig = connection) {
  return {
    host: target.host,
    port: target.port,
    token: target.token || undefined,
    timeoutMs
  };
}

function phoneSnapshot(value: Record<string, unknown>): PhoneSnapshot {
  return {
    state: typeof value.state === "string" ? value.state : "unknown",
    active: value.active === true,
    ...(typeof value.sessionId === "string" ? { sessionId: value.sessionId } : {}),
    ...(typeof value.request === "string" ? { request: value.request } : {}),
    ...(typeof value.currentPurpose === "string" ? { currentPurpose: value.currentPurpose } : {}),
    ...(typeof value.requestAvailable === "boolean" ? { requestAvailable: value.requestAvailable } : {})
  };
}

async function checkConnection(options: { silent?: boolean } = {}): Promise<BridgeCheckResult> {
  const previousBridgeStatus = bridgeStatus;
  const previousPhoneState = phone?.state;
  if (!options.silent) {
    bridgeStatus = "checking";
    lastError = undefined;
    publishState();
  }
  try {
    const result = await requestBridge(
      { type: "status", requestId: randomUUID() },
      bridgeOptions(4_000)
    );
    if (result.ok !== true) throw new Error(typeof result.message === "string" ? result.message : "The phone bridge rejected the status check.");
    const nextPhone = phoneSnapshot(result);
    const phoneChanged = JSON.stringify(nextPhone) !== JSON.stringify(phone);
    phone = nextPhone;
    bridgeStatus = "connected";
    lastError = undefined;
    if (!options.silent || previousBridgeStatus !== "connected") {
      appendLog(`Phone bridge connected; state: ${nextPhone.state}.`, { level: "system", source: "bridge" });
    } else if (phoneChanged) {
      publishState();
    }
    return { ok: true, message: "Phone bridge connected.", phone: nextPhone };
  } catch (error) {
    const directMessage = error instanceof Error ? error.message : String(error);
    if (connection.pairingCode && !options.silent) {
      try {
        const nextState = await pairWithCode(connection.pairingCode, "reconnected");
        return {
          ok: nextState.bridgeStatus === "connected",
          message: "Phone bridge rediscovered from the saved pairing code.",
          phone: nextState.phone
        };
      } catch (rediscoveryError) {
        const rediscoveryMessage = rediscoveryError instanceof Error ? rediscoveryError.message : String(rediscoveryError);
        lastError = `${directMessage} Pairing rediscovery failed: ${rediscoveryMessage}`;
      }
    } else {
      lastError = directMessage;
    }
    bridgeStatus = "offline";
    if (!options.silent || previousBridgeStatus !== "offline") {
      appendLog(lastError, { level: "error", source: "bridge" });
    } else {
      publishState();
    }
    return { ok: false, message: lastError };
  }
}

function parseSettingsInput(value: unknown): CompanionSettingsInput {
  if (!value || typeof value !== "object") throw new Error("Connection settings are required.");
  const input = value as Partial<CompanionSettingsInput>;
  const host = typeof input.host === "string" ? input.host.trim() : "";
  if (!host) throw new Error("Bridge host is required.");
  const port = parsePort(String(input.port ?? ""));
  const token = typeof input.token === "string" ? input.token.trim() : undefined;
  return { host, port, ...(token ? { token } : {}) };
}

async function saveSettings(value: unknown): Promise<CompanionState> {
  const input = parseSettingsInput(value);
  const wasRunning = Boolean(worker && !worker.killed);
  const changed = input.host !== connection.host || input.port !== connection.port || Boolean(input.token);
  if (wasRunning && changed) await stopWorker();
  connection = {
    host: input.host,
    port: input.port,
    token: input.token || connection.token
  };
  await saveConnection();
  bridgeStatus = "unknown";
  phone = undefined;
  lastError = undefined;
  appendLog(`Saved connection settings for ${connection.host}:${connection.port}.`, { level: "system", source: "system" });
  if (wasRunning && changed) startWorker();
  return snapshot();
}

async function pairWithCode(value: unknown, logVerb = "paired"): Promise<CompanionState> {
  const rawCode = typeof value === "string"
    ? value
    : value && typeof value === "object" && typeof (value as { code?: unknown }).code === "string"
      ? (value as { code: string }).code
      : null;
  if (!rawCode) throw new Error("A DHD pairing code is required.");
  const pairingCode = normalizePairingCode(rawCode);
  const offer = await discoverPhone(pairingCode);
  const candidate: ConnectionConfig = {
    host: offer.host,
    port: offer.port,
    token: offer.token,
    pairingCode,
    deviceId: offer.deviceId
  };

  const result = await requestBridge(
    { type: "status", requestId: randomUUID() },
    bridgeOptions(5_000, candidate)
  );
  if (result.ok !== true) {
    throw new Error(typeof result.message === "string" ? result.message : "The discovered phone rejected the connection check.");
  }

  const wasRunning = Boolean(worker && !worker.killed);
  if (wasRunning) await stopWorker();
  connection = candidate;
  await saveConnection();
  phone = phoneSnapshot(result);
  bridgeStatus = "connected";
  lastError = undefined;
  appendLog(`Phone pairing ${logVerb}; the companion discovered the phone automatically.`, { level: "system", source: "bridge" });
  if (wasRunning) startWorker();
  return snapshot();
}

async function readRequestBody(req: http.IncomingMessage): Promise<unknown> {
  return new Promise((resolveBody, rejectBody) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk;
      if (body.length > 1_000_000) {
        req.destroy();
        rejectBody(new Error("Request body too large."));
      }
    });
    req.on("end", () => {
      try {
        resolveBody(body ? JSON.parse(body) : {});
      } catch {
        rejectBody(new Error("Invalid JSON body."));
      }
    });
    req.on("error", rejectBody);
  });
}

function getContentType(path: string): string {
  if (path.endsWith(".html")) return "text/html; charset=utf-8";
  if (path.endsWith(".css")) return "text/css; charset=utf-8";
  if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
  if (path.endsWith(".json")) return "application/json; charset=utf-8";
  if (path.endsWith(".png")) return "image/png";
  if (path.endsWith(".svg")) return "image/svg+xml";
  return "text/plain; charset=utf-8";
}

function transpileTsFile(tsCode: string): string {
  return ts.transpileModule(tsCode, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      esModuleInterop: true,
      sourceMap: false
    }
  }).outputText;
}

async function serveStaticFile(res: http.ServerResponse, fileName: string): Promise<void> {
  // If a JS module is requested, check if a corresponding TS source exists and transpile on-the-fly
  if (fileName.endsWith(".js")) {
    const tsFileName = fileName.replace(/\.js$/, ".ts");
    const srcTsPath = resolve(PROJECT_ROOT, "src/companion-web", tsFileName);
    if (existsSync(srcTsPath)) {
      try {
        const tsCode = await readFile(srcTsPath, "utf8");
        const jsCode = transpileTsFile(tsCode);
        res.writeHead(200, {
          "Content-Type": "application/javascript; charset=utf-8",
          "Cache-Control": "no-cache, no-store, must-revalidate",
          "Pragma": "no-cache",
          "Expires": "0"
        });
        res.end(jsCode);
        return;
      } catch (err) {
        console.error(`Failed to transpile ${tsFileName}:`, err);
      }
    }
  }

  const candidatePaths = [
    resolve(PROJECT_ROOT, "src/companion-web", fileName),
    resolve(WEB_DIRECTORY, fileName),
    resolve(PROJECT_ROOT, "dist/companion-web", fileName)
  ];

  for (const filePath of candidatePaths) {
    if (existsSync(filePath)) {
      try {
        const content = await readFile(filePath);
        res.writeHead(200, {
          "Content-Type": getContentType(fileName),
          "Cache-Control": "no-cache, no-store, must-revalidate",
          "Pragma": "no-cache",
          "Expires": "0"
        });
        res.end(content);
        return;
      } catch {
        // try next
      }
    }
  }

  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("File not found");
}

export function createCompanionWebServer(): http.Server {
  return http.createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", `http://${req.headers.host || "localhost"}`);
    const pathname = url.pathname;

    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.setHeader("Access-Control-Allow-Headers", "Content-Type");

    if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }

    if (pathname === "/api/events" && req.method === "GET") {
      res.writeHead(200, {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache",
        "Connection": "keep-alive"
      });
      res.write(`data: ${JSON.stringify(snapshot())}\n\n`);
      sseClients.add(res);

      req.on("close", () => {
        sseClients.delete(res);
      });
      return;
    }

    if (pathname === "/api/state" && req.method === "GET") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(snapshot()));
      return;
    }

    if (pathname === "/api/settings" && req.method === "POST") {
      try {
        const body = await readRequestBody(req);
        const nextState = await saveSettings(body);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(nextState));
      } catch (err) {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ message: err instanceof Error ? err.message : String(err) }));
      }
      return;
    }

    if (pathname === "/api/pair" && req.method === "POST") {
      try {
        const body = await readRequestBody(req);
        const nextState = await pairWithCode(body);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(nextState));
      } catch (err) {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ message: err instanceof Error ? err.message : String(err) }));
      }
      return;
    }

    if (pathname === "/api/check" && req.method === "POST") {
      try {
        const result = await checkConnection();
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(result));
      } catch (err) {
        res.writeHead(500, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: false, message: err instanceof Error ? err.message : String(err) }));
      }
      return;
    }

    if (pathname === "/api/start" && req.method === "POST") {
      try {
        const nextState = startWorker();
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(nextState));
      } catch (err) {
        res.writeHead(500, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ message: err instanceof Error ? err.message : String(err) }));
      }
      return;
    }

    if (pathname === "/api/stop" && req.method === "POST") {
      try {
        const nextState = await stopWorker();
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(nextState));
      } catch (err) {
        res.writeHead(500, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ message: err instanceof Error ? err.message : String(err) }));
      }
      return;
    }

    if (pathname === "/api/clear-logs" && req.method === "POST") {
      try {
        const nextState = clearLogs();
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(nextState));
      } catch (err) {
        res.writeHead(500, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ message: err instanceof Error ? err.message : String(err) }));
      }
      return;
    }

    if (pathname === "/" || pathname === "/index.html") {
      return serveStaticFile(res, "index.html");
    }
    if (pathname === "/styles.css") {
      return serveStaticFile(res, "styles.css");
    }
    if (pathname === "/favicon.png") {
      return serveStaticFile(res, "favicon.png");
    }
    if (pathname === "/renderer.js" || pathname === "/renderer.ts") {
      return serveStaticFile(res, "renderer.js");
    }
    if (pathname === "/api.js" || pathname === "/api.ts") {
      return serveStaticFile(res, "api.js");
    }
    if (pathname === "/pairing-code.js" || pathname === "/pairing-code.ts") {
      return serveStaticFile(res, "pairing-code.js");
    }

    res.writeHead(404, { "Content-Type": "text/plain" });
    res.end("Not Found");
  });
}

let heartbeatTimer: NodeJS.Timeout | undefined;
let fileWatcher: ReturnType<typeof watch> | undefined;
let reloadDebounceTimer: NodeJS.Timeout | undefined;

function notifyClientsReload(type: "css" | "full", file?: string): void {
  const payload = `event: reload\ndata: ${JSON.stringify({ type, file, timestamp: Date.now() })}\n\n`;
  for (const client of sseClients) {
    try {
      client.write(payload);
    } catch {
      sseClients.delete(client);
    }
  }
}

function startFileWatcher(): void {
  if (fileWatcher) return;
  const srcWebDir = resolve(PROJECT_ROOT, "src/companion-web");
  if (!existsSync(srcWebDir)) return;

  try {
    fileWatcher = watch(srcWebDir, { recursive: true }, (_eventType, filename) => {
      if (!filename) return;
      if (filename.includes("tsconfig") || filename.endsWith(".tmp")) return;

      if (reloadDebounceTimer) clearTimeout(reloadDebounceTimer);
      reloadDebounceTimer = setTimeout(async () => {
        const isCss = filename.endsWith(".css");
        const isHtml = filename.endsWith(".html");

        // Sync static assets to dist if dist exists
        const distWebDir = resolve(PROJECT_ROOT, "dist/companion-web");
        if (existsSync(distWebDir)) {
          try {
            const srcFile = resolve(srcWebDir, filename);
            if (existsSync(srcFile) && (isCss || isHtml || filename.endsWith(".png"))) {
              await writeFile(resolve(distWebDir, filename), await readFile(srcFile));
            }
          } catch {
            // best effort copy
          }
        }

        notifyClientsReload(isCss ? "css" : "full", filename);
      }, 80);
    });
  } catch (err) {
    console.error("Failed to start file watcher:", err);
  }
}

function startHeartbeat(): void {
  if (heartbeatTimer) return;
  heartbeatTimer = setInterval(async () => {
    // Only poll when web UI clients are connected or worker is running
    if (sseClients.size === 0 && processStatus !== "running") return;
    if (bridgeStatus === "checking") return;
    if (!connection.token && !connection.pairingCode) return;
    try {
      await checkConnection({ silent: true });
    } catch {
      // Ignored in periodic heartbeat
    }
  }, 4_000);
}

export async function startCompanionWebServer(port = DEFAULT_WEB_PORT, host = DEFAULT_WEB_HOST): Promise<http.Server> {
  connection = await loadConnection();
  startHeartbeat();
  startFileWatcher();
  if (connection.token || connection.pairingCode) {
    void checkConnection({ silent: true }).catch(() => {});
  }
  const server = createCompanionWebServer();

  return new Promise((resolveReady, rejectReady) => {
    server.once("error", rejectReady);
    server.listen(port, host, () => {
      console.log(`\n  ======================================================`);
      console.log(`  DHD Companion Web App running at:`);
      console.log(`  http://${host}:${port}`);
      console.log(`  ======================================================\n`);
      resolveReady(server);
    });
  });
}

if (process.argv[1] && (process.argv[1].endsWith("server.ts") || process.argv[1].endsWith("server.js"))) {
  const port = Number(process.env.COMPANION_PORT || DEFAULT_WEB_PORT);
  const host = process.env.COMPANION_HOST || DEFAULT_WEB_HOST;
  startCompanionWebServer(port, host).catch((err) => {
    console.error("Failed to start companion web server:", err);
    process.exit(1);
  });
}
