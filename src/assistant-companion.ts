import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID } from "node:crypto";
import * as readline from "node:readline";

import { requestBridge, type BridgeMessage } from "./phone-assistant-bridge.js";

const DEFAULT_POLL_INTERVAL_MS = 1_000;
const BRIDGE_POLL_TIMEOUT_MS = 5_000;
interface JsonRpcMessage {
  id?: number;
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: { code?: number; message?: string; data?: unknown };
}

interface TurnResult {
  text: string;
}

/**
 * Small, one-turn Codex App Server client. Authentication stays in the Codex
 * CLI/App Server; this process never handles ChatGPT cookies or API keys.
 */
export class CodexAppServerClient {
  private child: ChildProcessWithoutNullStreams | null = null;
  private reader: readline.Interface | null = null;
  private nextId = 1;
  private pending = new Map<number, { resolve: (message: JsonRpcMessage) => void; reject: (error: Error) => void }>();
  private turnCompletion: {
    resolve: (result: TurnResult) => void;
    reject: (error: Error) => void;
    text: string;
  } | null = null;

  async runTurn(phoneRequest: string): Promise<TurnResult> {
    this.startProcess();
    try {
      await this.request("initialize", {
        clientInfo: {
          name: "phone-control-assistant",
          title: "Phone Control Assistant",
          version: "0.1.0"
        }
      });
      this.notify("initialized", {});

      const threadParams: Record<string, unknown> = {};
      const model = process.env.PHONE_ASSISTANT_CODEX_MODEL?.trim();
      if (model) threadParams.model = model;
      const threadResponse = await this.request("thread/start", threadParams);
      const threadId = extractThreadId(threadResponse.result);
      if (!threadId) throw new Error("Codex App Server did not return a thread id.");

      const result = await new Promise<TurnResult>((resolve, reject) => {
        this.turnCompletion = { resolve, reject, text: "" };
        this.request("turn/start", {
          threadId,
          input: [{ type: "text", text: buildPhonePrompt(phoneRequest) }]
        }).catch(reject);
      });
      return result;
    } finally {
      await this.stopProcess();
    }
  }

  private startProcess(): void {
    if (this.child) throw new Error("Codex App Server client is already running.");
    const command = process.env.PHONE_ASSISTANT_CODEX_BIN?.trim() || "codex";
    const args = ["app-server", "--listen", "stdio://"];
    const windowsCommand = process.platform === "win32"
      ? `${quoteWindowsCommand(command)} ${args.join(" ")}`
      : command;
    const child = spawn(windowsCommand, process.platform === "win32" ? [] : args, {
      stdio: ["pipe", "pipe", "pipe"],
      // On Windows Codex may be exposed as a .ps1/.cmd shim rather than a
      // native executable. Let cmd.exe resolve that user-installed command.
      shell: process.platform === "win32",
      windowsHide: true,
      env: process.env
    });
    this.child = child;
    this.reader = readline.createInterface({ input: child.stdout });
    this.reader.on("line", (line) => this.handleLine(line));
    child.stderr.on("data", (chunk: Buffer) => {
      const text = chunk.toString("utf8").trim();
      if (text) console.error(`[codex-app-server] ${text}`);
    });
    child.once("error", (error) => this.failPending(new Error(`Could not start Codex App Server: ${error.message}`)));
    child.once("close", (code, signal) => {
      this.failPending(new Error(`Codex App Server exited before completing the turn (code=${code ?? "?"}, signal=${signal ?? "?"}).`));
    });
  }

  private handleLine(line: string): void {
    const trimmed = line.trim();
    if (!trimmed) return;
    let message: JsonRpcMessage;
    try {
      message = JSON.parse(trimmed) as JsonRpcMessage;
    } catch {
      console.error(`[codex-app-server] ignored non-JSON stdout: ${trimmed.slice(0, 240)}`);
      return;
    }

    if (typeof message.id === "number") {
      const waiter = this.pending.get(message.id);
      if (waiter) {
        this.pending.delete(message.id);
        if (message.error) {
          waiter.reject(new Error(message.error.message || `Codex App Server request ${message.id} failed.`));
        } else {
          waiter.resolve(message);
        }
      }
    }

    const completion = this.turnCompletion;
    if (!completion || !message.method) return;
    if (message.method === "item/agentMessage/delta") {
      completion.text += extractText(message.params);
      return;
    }
    if (message.method === "item/completed" && !completion.text) {
      completion.text = extractCompletedAgentText(message.params);
      return;
    }
    if (message.method === "turn/completed") {
      const turn = extractRecord(message.params)?.turn;
      const status = extractRecord(turn)?.status;
      if (status === "failed") {
        completion.reject(new Error(extractTurnError(message.params) || "Codex App Server turn failed."));
      } else {
        const fallback = extractText(message.params);
        completion.resolve({ text: completion.text || fallback });
      }
      this.turnCompletion = null;
      return;
    }
    if (message.method === "turn/failed" || message.method === "error") {
      completion.reject(new Error(extractTurnError(message.params) || "Codex App Server turn failed."));
      this.turnCompletion = null;
    }
  }

  private request(method: string, params: Record<string, unknown>): Promise<JsonRpcMessage> {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      try {
        this.send({ method, id, params });
      } catch (error) {
        this.pending.delete(id);
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
  }

  private notify(method: string, params: Record<string, unknown>): void {
    this.send({ method, params });
  }

  private send(message: JsonRpcMessage): void {
    if (!this.child || this.child.stdin.destroyed) {
      throw new Error("Codex App Server is not running.");
    }
    this.child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  private failPending(error: Error): void {
    for (const waiter of this.pending.values()) waiter.reject(error);
    this.pending.clear();
    this.turnCompletion?.reject(error);
    this.turnCompletion = null;
  }

  private async stopProcess(): Promise<void> {
    const child = this.child;
    const reader = this.reader;
    this.child = null;
    this.reader = null;
    this.turnCompletion = null;
    for (const waiter of this.pending.values()) waiter.reject(new Error("Codex App Server stopped."));
    this.pending.clear();
    reader?.close();
    if (!child || child.killed) return;
    child.stdin.end();
    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => {
        child.kill();
        resolve();
      }, 1_500);
      child.once("close", () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }
}

export async function runAssistantCompanion(): Promise<void> {
  const pollIntervalMs = parsePollInterval(process.env.PHONE_ASSISTANT_POLL_MS);
  let stopping = false;
  const stop = () => {
    stopping = true;
  };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);

  console.error("[phone-assistant-companion] waiting for a request typed in the Android app");
  console.error("[phone-assistant-companion] ensure adb forward tcp:8765 tcp:8765 and the phone_assistant MCP config are active");

  while (!stopping) {
    try {
      const pending = await requestBridge(
        { type: "pending_request", requestId: randomUUID() },
        { timeoutMs: BRIDGE_POLL_TIMEOUT_MS }
      );
      if (pending.ok === true && pending.available === true) {
        await processPendingRequest(pending);
      } else if (pending.ok === false) {
        console.error(`[phone-assistant-companion] phone bridge rejected poll: ${String(pending.message ?? "unknown error")}`);
      }
    } catch (error) {
      // The phone may be disconnected or the bridge may not be running yet.
      // Keep polling so reconnecting the device does not require a restart.
      console.error(`[phone-assistant-companion] ${error instanceof Error ? error.message : String(error)}`);
    }
    if (!stopping) await delay(pollIntervalMs);
  }
}

async function processPendingRequest(pending: BridgeMessage): Promise<void> {
  const sessionId = typeof pending.sessionId === "string" ? pending.sessionId : "";
  if (!sessionId) {
    console.error("[phone-assistant-companion] pending request did not include a session id");
    return;
  }
  const claimed = await requestBridge({
    type: "claim_request",
    requestId: randomUUID(),
    sessionId
  });
  if (claimed.ok !== true) {
    // Another companion instance may have claimed it between polling and the
    // claim call. This is expected and is safe to ignore.
    if (claimed.code !== "REQUEST_NOT_AVAILABLE") {
      console.error(`[phone-assistant-companion] could not claim request: ${String(claimed.message ?? "unknown error")}`);
    }
    return;
  }

  const request = typeof claimed.request === "string" ? claimed.request : "";
  if (!request) {
    console.error("[phone-assistant-companion] claimed request was empty; releasing it");
    await releaseRequest(sessionId);
    return;
  }

  console.error(`[phone-assistant-companion] claimed ${sessionId}: ${request}`);
  try {
    const result = await new CodexAppServerClient().runTurn(request);
    console.error(`[phone-assistant-companion] Codex turn completed${result.text ? `: ${result.text.slice(0, 500)}` : ""}`);
    const completed = await requestBridge({
      type: "complete_session",
      requestId: randomUUID(),
      sessionId,
      message: "Codex completed the request. See the activity timeline for the actions it performed."
    });
    if (completed.ok !== true) {
      console.error(`[phone-assistant-companion] could not mark the phone session complete: ${String(completed.message ?? "unknown error")}`);
    }
  } catch (error) {
    console.error(`[phone-assistant-companion] Codex turn failed: ${error instanceof Error ? error.message : String(error)}`);
    await releaseRequest(sessionId);
  }
}

async function releaseRequest(sessionId: string): Promise<void> {
  try {
    await requestBridge({ type: "release_request", requestId: randomUUID(), sessionId });
  } catch (error) {
    console.error(`[phone-assistant-companion] could not release request: ${error instanceof Error ? error.message : String(error)}`);
  }
}

function buildPhonePrompt(phoneRequest: string): string {
  return [
    "Operate the user's physical Android phone for the request below.",
    "A phone-side session is already running; do not call phone_assistant_start.",
    "Use only the phone_assistant_* MCP tools. Begin with phone_assistant_list_allowed_apps if you need the allowlist, then phone_assistant_observe.",
    "Before every phone_assistant_execute call, use the freshest observationId. After every action, inspect the returned fresh screenshot before proposing the next action.",
    "Every action must include a concise, user-facing metadata.purpose and metadata.targetDescription. Never send shell commands or bypass a phone policy decision.",
    "If the phone requires confirmation or reports a stale observation, stop and explain what the user must do.",
    "When the request is complete, give a short result summary; the desktop companion will close the phone session.",
    "",
    `User request: ${phoneRequest}`
  ].join("\n");
}

function extractThreadId(value: unknown): string | null {
  if (!value || typeof value !== "object") return null;
  const record = value as Record<string, unknown>;
  const thread = record.thread;
  if (thread && typeof thread === "object") {
    const id = (thread as Record<string, unknown>).id;
    if (typeof id === "string" && id) return id;
  }
  return typeof record.id === "string" && record.id ? record.id : null;
}

function extractText(value: unknown): string {
  const record = extractRecord(value);
  if (!record) return "";
  for (const key of ["delta", "text", "message"]) {
    if (typeof record[key] === "string") return record[key] as string;
  }
  const item = record.item;
  if (item && typeof item === "object") {
    const itemRecord = extractRecord(item);
    if (!itemRecord) return "";
    for (const key of ["text", "message"]) {
      if (typeof itemRecord[key] === "string") return itemRecord[key] as string;
    }
  }
  return "";
}

function extractCompletedAgentText(value: unknown): string {
  const item = extractRecord(extractRecord(value)?.item);
  if (!item || item.type !== "agentMessage") return "";
  return typeof item.text === "string" ? item.text : "";
}

function extractTurnError(value: unknown): string {
  const record = extractRecord(value);
  const nestedError = extractRecord(record?.error);
  if (typeof nestedError?.message === "string") return nestedError.message;
  const turn = extractRecord(record?.turn);
  const turnError = extractRecord(turn?.error);
  if (typeof turnError?.message === "string") return turnError.message;
  return typeof record?.message === "string" ? record.message : "";
}

function extractRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" ? value as Record<string, unknown> : null;
}

function parsePollInterval(value: string | undefined): number {
  if (!value?.trim()) return DEFAULT_POLL_INTERVAL_MS;
  if (!/^\d+$/.test(value.trim())) throw new Error("PHONE_ASSISTANT_POLL_MS must be a positive integer.");
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 250 || parsed > 60_000) {
    throw new Error("PHONE_ASSISTANT_POLL_MS must be between 250 and 60000.");
  }
  return parsed;
}

function quoteWindowsCommand(command: string): string {
  if (/\s|[&|<>^]/.test(command) && !(command.startsWith('"') && command.endsWith('"'))) {
    return `"${command.replaceAll('"', '\\"')}"`;
  }
  return command;
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

const isMainModule = process.argv[1]?.endsWith("assistant-companion.ts") ||
  process.argv[1]?.endsWith("assistant-companion.js");

if (isMainModule) {
  runAssistantCompanion().catch((error: unknown) => {
    console.error(`[phone-assistant-companion] ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  });
}
