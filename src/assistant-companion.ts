import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID } from "node:crypto";
import * as readline from "node:readline";

import {
  invokePhoneAssistantTool,
  type PhoneAssistantToolResult
} from "./assistant-mcp-server.js";
import { requestBridge, type BridgeMessage } from "./phone-assistant-bridge.js";

const DEFAULT_POLL_INTERVAL_MS = 1_000;
const BRIDGE_POLL_TIMEOUT_MS = 5_000;
const DEFAULT_TURN_TIMEOUT_MS = 120_000;
const APP_SERVER_REQUEST_TIMEOUT_MS = 30_000;
const MAX_AGENT_FEEDBACK_CHARS = 4_000;
type JsonRpcId = number | string;

interface JsonRpcMessage {
  id?: JsonRpcId;
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: { code?: number; message?: string; data?: unknown };
}

interface PendingRpcRequest {
  resolve: (message: JsonRpcMessage) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

export interface DynamicToolCallResponse {
  contentItems: Array<
    | { type: "inputText"; text: string }
    | { type: "inputImage"; imageUrl: string }
  >;
  success: boolean;
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
  private pending = new Map<JsonRpcId, PendingRpcRequest>();
  private turnCompletion: {
    resolve: (result: TurnResult) => void;
    reject: (error: Error) => void;
    text: string;
  } | null = null;
  private activeThreadId: string | null = null;

  async runTurn(phoneRequest: string): Promise<TurnResult> {
    this.startProcess();
    try {
      await this.request("initialize", {
        clientInfo: {
          name: "phone-control-assistant",
          title: "Phone Control Assistant",
          version: "0.1.0"
        },
        capabilities: { experimentalApi: true }
      });
      this.notify("initialized", {});

      const threadParams: Record<string, unknown> = {
        dynamicTools: buildPhoneAssistantDynamicTools()
      };
      const model = process.env.PHONE_ASSISTANT_CODEX_MODEL?.trim();
      if (model) threadParams.model = model;
      const threadResponse = await this.request("thread/start", threadParams);
      const threadId = extractThreadId(threadResponse.result);
      if (!threadId) throw new Error("Codex App Server did not return a thread id.");
      this.activeThreadId = threadId;

      const completion = new Promise<TurnResult>((resolve, reject) => {
        this.turnCompletion = { resolve, reject, text: "" };
      });
      try {
        await this.request("turn/start", {
          threadId,
          input: [{ type: "text", text: buildPhonePrompt(phoneRequest) }]
        });
      } catch (error) {
        this.turnCompletion?.reject(error instanceof Error ? error : new Error(String(error)));
        this.turnCompletion = null;
        throw error;
      }
      return await withTimeout(
        completion,
        parseTurnTimeout(process.env.PHONE_ASSISTANT_TURN_TIMEOUT_MS),
        () => {
          this.interruptTurn(threadId);
          return new Error("Codex App Server turn timed out while waiting for a response.");
        }
      );
    } finally {
      this.activeThreadId = null;
      await this.stopProcess();
    }
  }

  private startProcess(): void {
    if (this.child) throw new Error("Codex App Server client is already running.");
    const command = process.env.PHONE_ASSISTANT_CODEX_BIN?.trim() || "codex";
    const args = ["app-server", "--listen", "stdio://"];
    // In the current Codex App Server builds, the model's dynamic-tool router
    // reaches these phone tools through the bundled Code Mode host. Keep that
    // host enabled by default; disabling it makes an otherwise healthy turn
    // fail closed with `code-mode host is disabled`. An explicit `false` is
    // still useful for diagnostics or environments that provide their own
    // tool-routing policy.
    if (process.env.PHONE_ASSISTANT_ENABLE_CODE_MODE_HOST?.trim().toLowerCase() === "false") {
      args.push("--disable", "code_mode_host");
    } else {
      args.push("--enable", "code_mode_host");
    }
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

    // App Server is bidirectional: a message with both `method` and `id` is a
    // server request that this client must answer, not a response to one of
    // our requests. Handling it before the pending map prevents server and
    // client request IDs from colliding.
    if (message.method && message.id !== undefined) {
      void this.handleServerRequest(message);
      return;
    }

    if (message.id !== undefined) {
      const waiter = this.pending.get(message.id);
      if (waiter) {
        this.pending.delete(message.id);
        clearTimeout(waiter.timer);
        if (message.error) {
          waiter.reject(new Error(message.error.message || `Codex App Server request ${message.id} failed.`));
        } else {
          waiter.resolve(message);
        }
      }
      return;
    }

    const completion = this.turnCompletion;
    if (!completion || !message.method) return;
    logServerNotification(message);
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

  private async handleServerRequest(message: JsonRpcMessage): Promise<void> {
    const id = message.id;
    const method = message.method;
    if (id === undefined || !method) return;

    try {
      switch (method) {
        case "item/tool/call": {
          const result = await handleDynamicToolCall(message.params);
          this.respond(id, result);
          return;
        }
        case "item/commandExecution/requestApproval":
          console.error("[codex-app-server] declined a command approval; phone turns may only use typed phone tools");
          this.respond(id, { decision: "decline" });
          return;
        case "item/fileChange/requestApproval":
          console.error("[codex-app-server] declined a file-change approval; the phone companion is not a coding host");
          this.respond(id, { decision: "decline" });
          return;
        case "item/tool/requestUserInput":
          console.error("[codex-app-server] answered tool user-input request with empty answers");
          this.respond(id, { answers: emptyToolAnswers(message.params) });
          return;
        case "item/permissions/requestApproval":
          console.error("[codex-app-server] declined an additional permission request");
          this.respond(id, { permissions: { network: null, fileSystem: null }, scope: "turn" });
          return;
        case "mcpServer/elicitation/request":
          console.error("[codex-app-server] declined an MCP elicitation request");
          this.respond(id, { action: "decline", content: null });
          return;
        case "account/chatgptAuthTokens/refresh":
          this.respondError(id, -32001, "The phone companion does not manage ChatGPT auth token refresh.");
          return;
        case "attestation/generate":
          this.respondError(id, -32001, "The phone companion does not provide upstream attestation.");
          return;
        default:
          console.error(`[codex-app-server] unsupported server request: ${method}`);
          this.respondError(id, -32601, `Unsupported App Server request: ${method}`);
      }
    } catch (error) {
      this.respondError(id, -32000, error instanceof Error ? error.message : String(error));
    }
  }

  private respond(id: JsonRpcId, result: unknown): void {
    this.send({ id, result });
  }

  private respondError(id: JsonRpcId, code: number, message: string): void {
    this.send({ id, error: { code, message } });
  }

  private request(method: string, params: Record<string, unknown>): Promise<JsonRpcMessage> {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (!this.pending.delete(id)) return;
        reject(new Error(`Timed out waiting for Codex App Server request ${method}.`));
      }, APP_SERVER_REQUEST_TIMEOUT_MS);
      this.pending.set(id, { resolve, reject, timer });
      try {
        this.send({ method, id, params });
      } catch (error) {
        this.pending.delete(id);
        clearTimeout(timer);
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
    for (const waiter of this.pending.values()) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
    this.pending.clear();
    this.turnCompletion?.reject(error);
    this.turnCompletion = null;
  }

  private interruptTurn(threadId: string): void {
    try {
      this.send({
        method: "turn/interrupt",
        id: `interrupt-${this.nextId++}`,
        params: { threadId }
      });
    } catch (error) {
      console.error(`[codex-app-server] could not interrupt timed-out turn: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  private async stopProcess(): Promise<void> {
    const child = this.child;
    const reader = this.reader;
    this.child = null;
    this.reader = null;
    this.turnCompletion = null;
    for (const waiter of this.pending.values()) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error("Codex App Server stopped."));
    }
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

const PHONE_DYNAMIC_TOOL_TO_MCP = {
  phone_control_status: "phone_assistant_status",
  phone_control_pending_request: "phone_assistant_pending_request",
  phone_control_list_allowed_apps: "phone_assistant_list_allowed_apps",
  phone_control_start: "phone_assistant_start",
  phone_control_observe: "phone_assistant_observe",
  phone_control_execute: "phone_assistant_execute",
  phone_control_request_attention: "phone_assistant_request_attention",
  phone_control_stop: "phone_assistant_stop"
} as const;

type DynamicToolSpec = Record<string, unknown>;

/**
 * Register a small direct tool surface on the App Server thread. These are
 * intentionally separate names from the configured MCP tools. The App Server
 * delivers their calls to this companion; current builds normally reach that
 * request path through the bundled Code Mode host.
 */
export function buildPhoneAssistantDynamicTools(): DynamicToolSpec[] {
  const guardRegion = {
    type: "object",
    properties: {
      left: { type: "integer", minimum: 0 },
      top: { type: "integer", minimum: 0 },
      right: { type: "integer", minimum: 1 },
      bottom: { type: "integer", minimum: 1 }
    },
    required: ["left", "top", "right", "bottom"],
    additionalProperties: false
  };
  const metadata = {
    type: "object",
    properties: {
      purpose: { type: "string", minLength: 1, maxLength: 240 },
      observationId: { type: "string", minLength: 1, maxLength: 240 },
      targetDescription: { type: "string", minLength: 1, maxLength: 240 },
      guardRegions: { type: "array", items: guardRegion, maxItems: 8 }
    },
    required: ["purpose", "observationId", "targetDescription"],
    additionalProperties: false
  };
  const actionObject = (
    properties: Record<string, unknown>,
    required: string[]
  ) => ({
    type: "object",
    properties: { ...properties, metadata },
    required: [...required, "metadata"],
    additionalProperties: false
  });
  const action = {
    oneOf: [
      actionObject(
        { type: { const: "open_app" }, packageName: { type: "string", minLength: 1 } },
        ["type", "packageName"]
      ),
      actionObject(
        { type: { const: "tap" }, x: { type: "integer", minimum: 0 }, y: { type: "integer", minimum: 0 } },
        ["type", "x", "y"]
      ),
      actionObject(
        { type: { const: "click_coordinate" }, x: { type: "integer", minimum: 0 }, y: { type: "integer", minimum: 0 } },
        ["type", "x", "y"]
      ),
      actionObject(
        { type: { const: "type" }, text: { type: "string", minLength: 1, maxLength: 4096 } },
        ["type", "text"]
      ),
      actionObject(
        {
          type: { const: "swipe" },
          startX: { type: "integer", minimum: 0 },
          startY: { type: "integer", minimum: 0 },
          endX: { type: "integer", minimum: 0 },
          endY: { type: "integer", minimum: 0 },
          durationMs: { type: "integer", minimum: 1, maximum: 10_000 }
        },
        ["type", "startX", "startY", "endX", "endY"]
      ),
      actionObject(
        {
          type: { const: "scroll" },
          direction: { type: "string", enum: ["up", "down", "left", "right"] },
          amount: { type: "string", enum: ["small", "medium", "large"] }
        },
        ["type", "direction", "amount"]
      ),
      actionObject({ type: { const: "back" } }, ["type"]),
      actionObject(
        { type: { const: "keypress" }, key: { type: "string", enum: ["BACK", "HOME", "ENTER", "DELETE"] } },
        ["type", "key"]
      ),
      actionObject(
        { type: { const: "wait" }, durationMs: { type: "integer", minimum: 1, maximum: 30_000 } },
        ["type", "durationMs"]
      )
    ]
  };

  return [
    dynamicTool(
      "phone_control_status",
      "Read the phone assistant session state and current user-visible action.",
      emptySchema()
    ),
    dynamicTool(
      "phone_control_pending_request",
      "Read whether the Android app has a typed request waiting for this companion.",
      emptySchema()
    ),
    dynamicTool(
      "phone_control_list_allowed_apps",
      "List Android packages enabled in the phone-side per-app allowlist.",
      emptySchema()
    ),
    dynamicTool(
      "phone_control_start",
      "Start a phone-side assistant session for a typed natural-language request.",
      {
        type: "object",
        properties: { request: { type: "string", minLength: 1, maxLength: 16_384 } },
        required: ["request"],
        additionalProperties: false
      }
    ),
    dynamicTool(
      "phone_control_observe",
      "Capture the physical Android display and return a fresh observationId plus screenshot.",
      {
        type: "object",
        properties: {
          expectedPackageName: { type: "string", minLength: 1 },
          guardRegions: { type: "array", items: guardRegion, maxItems: 8 }
        },
        additionalProperties: false
      }
    ),
    dynamicTool(
      "phone_control_execute",
      "Execute one typed phone action with metadata.purpose, then return a fresh post-action screenshot. Never use shell commands.",
      {
        type: "object",
        properties: { action },
        required: ["action"],
        additionalProperties: false
      }
    ),
    dynamicTool(
      "phone_control_request_attention",
      "Notify the user that their attention is needed without bringing the assistant app to the foreground. Use this when the user must review or take over; do not continue phone actions afterward.",
      {
        type: "object",
        properties: { reason: { type: "string", minLength: 1, maxLength: 240 } },
        required: ["reason"],
        additionalProperties: false
      }
    ),
    dynamicTool(
      "phone_control_stop",
      "Stop the active phone-side assistant session.",
      {
        type: "object",
        properties: { reason: { type: "string", minLength: 1, maxLength: 240 } },
        additionalProperties: false
      }
    )
  ];
}

function dynamicTool(name: string, description: string, inputSchema: Record<string, unknown>): DynamicToolSpec {
  return { type: "function", name, description, inputSchema };
}

function emptySchema(): Record<string, unknown> {
  return { type: "object", properties: {}, additionalProperties: false };
}

async function handleDynamicToolCall(value: unknown): Promise<DynamicToolCallResponse> {
  const params = extractRecord(value) ?? {};
  const requestedName = typeof params.tool === "string" ? params.tool : "";
  const name = requestedName.includes(".")
    ? requestedName.slice(requestedName.lastIndexOf(".") + 1)
    : requestedName;
  const mappedName = PHONE_DYNAMIC_TOOL_TO_MCP[name as keyof typeof PHONE_DYNAMIC_TOOL_TO_MCP];
  if (!mappedName) {
    return dynamicToolFailure(`Unsupported dynamic phone tool: ${requestedName || "(missing tool name)"}`);
  }

  const input = normalizeDynamicArguments(params.arguments);
  console.error(`[codex-app-server] invoking ${name}`);
  const result = await invokePhoneAssistantTool(mappedName, input);
  return toDynamicToolResponse(result);
}

function normalizeDynamicArguments(value: unknown): unknown {
  if (value === undefined || value === null) return {};
  if (typeof value !== "string") return value;
  try {
    return JSON.parse(value) as unknown;
  } catch {
    return { __invalidArguments: value.slice(0, 240) };
  }
}

export function toDynamicToolResponse(result: PhoneAssistantToolResult): DynamicToolCallResponse {
  const contentItems: DynamicToolCallResponse["contentItems"] = [];
  for (const item of result.content) {
    if (item.type === "text") {
      contentItems.push({ type: "inputText", text: item.text });
    } else if (item.type === "image") {
      const imageUrl = item.data.startsWith("data:")
        ? item.data
        : `data:${item.mimeType};base64,${item.data}`;
      contentItems.push({ type: "inputImage", imageUrl });
    }
  }
  if (contentItems.length === 0) {
    contentItems.push({
      type: "inputText",
      text: JSON.stringify(result.structuredContent ?? { ok: !result.isError })
    });
  }
  return { contentItems, success: !result.isError };
}

function dynamicToolFailure(message: string): DynamicToolCallResponse {
  return { contentItems: [{ type: "inputText", text: JSON.stringify({ ok: false, message }) }], success: false };
}

function emptyToolAnswers(value: unknown): Record<string, { answers: string[] }> {
  const questions = extractRecord(value ?? {})?.questions;
  if (!Array.isArray(questions)) return {};
  const answers: Record<string, { answers: string[] }> = {};
  for (const question of questions) {
    const id = extractRecord(question)?.id;
    if (typeof id === "string" && id) answers[id] = { answers: [] };
  }
  return answers;
}

function logServerNotification(message: JsonRpcMessage): void {
  if (message.method === "turn/started") {
    console.error("[codex-app-server] turn started");
    return;
  }
  if (message.method === "turn/completed") {
    const turn = extractRecord(extractRecord(message.params)?.turn);
    console.error(`[codex-app-server] turn completed (${String(turn?.status ?? "unknown")})`);
    return;
  }
  if (message.method !== "item/started" && message.method !== "item/completed") return;
  const item = extractRecord(extractRecord(message.params)?.item);
  if (!item) return;
  const type = typeof item.type === "string" ? item.type : "item";
  const tool = typeof item.tool === "string" ? ` ${item.tool}` : "";
  const status = typeof item.status === "string" ? ` (${item.status})` : "";
  console.error(`[codex-app-server] ${message.method} ${type}${tool}${status}`);
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
  console.error("[phone-assistant-companion] ensure adb forward tcp:8765 tcp:8765 and a logged-in Codex CLI are available");

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
    const feedback = normalizeAgentFeedback(result.text);
    const completed = await requestBridge({
      type: "complete_session",
      requestId: randomUUID(),
      sessionId,
      message: feedback || "Codex completed the request. See the activity timeline for the actions it performed.",
      ...(feedback ? { feedback } : {})
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
    "Use only the direct phone_control_* tools exposed by this companion. Do not use arbitrary exec code, shell commands, or the configured phone_assistant_* MCP wrappers. Code Mode may be the App Server transport for these direct tools; never use it to run unrelated code.",
    "Begin with phone_control_list_allowed_apps if you need the allowlist, then phone_control_observe.",
    "Before every phone_control_execute call, use the freshest observationId. After every action, inspect the returned fresh screenshot before proposing the next action.",
    "Every action must include a concise, user-facing metadata.purpose and metadata.targetDescription. Never send shell commands or bypass a phone policy decision.",
    "If the phone requires confirmation or reports a stale observation, use phone_control_request_attention with a concise explanation, stop taking phone actions, and explain what the user must do.",
    "When the request is complete, give a short result summary; the desktop companion will close the phone session.",
    "",
    `User request: ${phoneRequest}`
  ].join("\n");
}

function normalizeAgentFeedback(text: string): string {
  return text
    .replace(/\r\n?/g, "\n")
    .trim()
    .slice(0, MAX_AGENT_FEEDBACK_CHARS);
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

function parseTurnTimeout(value: string | undefined): number {
  if (!value?.trim()) return DEFAULT_TURN_TIMEOUT_MS;
  if (!/^\d+$/.test(value.trim())) {
    throw new Error("PHONE_ASSISTANT_TURN_TIMEOUT_MS must be a positive integer.");
  }
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 5_000 || parsed > 3_600_000) {
    throw new Error("PHONE_ASSISTANT_TURN_TIMEOUT_MS must be between 5000 and 3600000.");
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

function withTimeout<T>(
  promise: Promise<T>,
  milliseconds: number,
  onTimeout: () => Error
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(onTimeout()), milliseconds);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error);
      }
    );
  });
}

const isMainModule = process.argv[1]?.endsWith("assistant-companion.ts") ||
  process.argv[1]?.endsWith("assistant-companion.js");

if (isMainModule) {
  runAssistantCompanion().catch((error: unknown) => {
    console.error(`[phone-assistant-companion] ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  });
}
