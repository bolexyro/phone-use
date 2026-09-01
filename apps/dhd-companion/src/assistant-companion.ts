import { mkdirSync, readFileSync } from "node:fs";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID } from "node:crypto";
import { homedir } from "node:os";
import { join, resolve } from "node:path";
import { performance } from "node:perf_hooks";
import * as readline from "node:readline";

import {
  invokeDhdTool,
  type PhoneAssistantToolResult
} from "./dhd-tools.js";
import {
  bridgeHost,
  bridgePort,
  isLoopbackBridgeHost,
  requestBridge,
  type BridgeMessage
} from "./phone-assistant-bridge.js";

const DEFAULT_POLL_INTERVAL_MS = 1_000;
const BRIDGE_POLL_TIMEOUT_MS = 5_000;
// Phone turns can legitimately contain several observe/action cycles. Keep a
// bounded default that is long enough for those cycles without allowing a
// disconnected or wedged App Server process to run forever.
const DEFAULT_TURN_TIMEOUT_MS = 600_000;
const APP_SERVER_REQUEST_TIMEOUT_MS = 30_000;
const MAX_AGENT_FEEDBACK_CHARS = 4_000;
const MAX_STEER_CHARS = 4_000;
const DEFAULT_COMPLETION_MESSAGE = "Your DHD task is ready to review.";
const DEFAULT_CODEX_HOME = join(homedir(), ".dhd", "codex-home");
const DEFAULT_CODEX_RUNTIME_CWD = join(homedir(), ".dhd", "codex-runtime");
const MINIMAL_CODEX_CONFIG_OVERRIDES = [
  "mcp_servers={}",
  "features.apps=false",
  "features.browser_use=false",
  "features.computer_use=false",
  "features.goals=false",
  "features.hooks=false",
  "features.image_generation=false",
  "features.in_app_browser=false",
  "features.memories=false",
  "features.multi_agent=false",
  "features.plugins=false",
  "features.remote_plugin=false",
  "features.shell_snapshot=false",
  "features.shell_tool=false",
  "features.skill_mcp_dependency_install=false",
  "features.skill_search=false",
  "features.tool_suggest=false",
  "features.unified_exec=false",
  "features.view_image=false",
  "features.workspace_dependencies=false"
];
const PREWARM_ATTEMPTS = 2;
const PREWARM_RETRY_DELAY_MS = 500;
// DHD owns its App Server conversation settings. These defaults deliberately
// do not depend on the user's interactive Codex chat or global config.
const DEFAULT_CODEX_MODEL = "gpt-5.6-luna";
const DEFAULT_CODEX_EFFORT = "max";
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

/**
 * Human-readable, millisecond-resolution lifecycle timing written to stderr.
 * The companion never includes request text, tool arguments, screenshots, or
 * model output in these diagnostics.
 */
class PhaseTimer {
  private readonly startedAt = performance.now();

  constructor(private readonly scope: string) {}

  log(phase: string, details?: string): void {
    const elapsedMs = Math.round(performance.now() - this.startedAt);
    const suffix = details ? ` ${details}` : "";
    console.error(
      `[dhd-timing] scope=${this.scope} phase=${phase} tsMs=${Date.now()} elapsedMs=${elapsedMs}${suffix}`
    );
  }
}

function logCompanionPhase(phase: string, details?: string): void {
  const suffix = details ? ` ${details}` : "";
  console.error(`[dhd-timing] scope=companion phase=${phase} tsMs=${Date.now()}${suffix}`);
}

function debugTimingEnabled(): boolean {
  return ["1", "true", "yes", "on"].includes(
    (process.env.PHONE_ASSISTANT_DEBUG_TIMING ?? "").trim().toLowerCase()
  );
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
  threadId: string;
}

interface AgentMessageState {
  id: string;
  text: string;
  phase?: string;
  completed: boolean;
  lastEventOrder: number;
}

/**
 * Persistent Codex App Server client. Authentication stays in the Codex
 * CLI/App Server; this process never handles ChatGPT cookies or API keys.
 * DHD gives the child App Server its own Codex home so global coding-agent
 * configuration and credentials do not leak into phone turns.
 */
export class CodexAppServerClient {
  private child: ChildProcessWithoutNullStreams | null = null;
  private reader: readline.Interface | null = null;
  private nextId = 1;
  private pending = new Map<JsonRpcId, PendingRpcRequest>();
  private startPromise: Promise<void> | null = null;
  private initialized = false;
  private loadedThreadIds = new Set<string>();
  private readonly codexHome = resolveCodexHome();
  private readonly runtimeCwd = resolveCodexRuntimeCwd();
  private turnCompletion: {
    resolve: (result: TurnResult) => void;
    reject: (error: Error) => void;
    agentMessages: Map<string, AgentMessageState>;
    nextAgentMessageOrder: number;
  } | null = null;
  private activeThreadId: string | null = null;
  private activeDhdThreadId: string | null = null;
  // A thread loaded from an older companion process may carry an obsolete
  // dynamic-tool contract. Establish one current DHD thread before allowing
  // thread reuse or resume.
  private hasCurrentDhdThread = false;
  private activeTurnId: string | null = null;
  private turnStartedAt: number | null = null;
  private lastServerEvent: string | null = null;
  private activeTiming: PhaseTimer | null = null;
  private userMessageLogged = false;

  /** True while this client still owns an in-flight App Server turn. */
  get isTurnInFlight(): boolean {
    return this.turnCompletion !== null;
  }

  /** True when the active thread and turn ids are available for steering. */
  get canSteer(): boolean {
    return this.isTurnInFlight && Boolean(this.activeThreadId && this.activeTurnId);
  }

  /**
   * Start and initialize one App Server connection. The connection remains
   * alive after a turn so later requests can reuse its loaded thread state.
   */
  async start(timing?: PhaseTimer): Promise<void> {
    if (this.initialized && this.child) {
      timing?.log("connection:reuse", `pid=${this.child.pid ?? "?"}`);
      return;
    }
    if (this.startPromise) return this.startPromise;

    const logger = timing ?? new PhaseTimer("codex-connection");
    this.startPromise = (async () => {
      if (this.child) await this.stopProcess();
      logger.log("spawn:start", `cwd=${this.runtimeCwd} codexHome=${this.codexHome}`);
      this.startProcess();
      logger.log("spawn:complete", `pid=${this.child?.pid ?? "?"}`);
      logger.log("initialize:start");
      try {
        await this.request("initialize", {
          clientInfo: {
            name: "dhd-phone-assistant",
            title: "DHD phone assistant",
            version: "0.1.0"
          },
          capabilities: { experimentalApi: true }
        });
        this.notify("initialized", {});
        this.initialized = true;
        logger.log("initialize:complete");
      } catch (error) {
        await this.stopProcess();
        throw error;
      }
    })();

    try {
      await this.startPromise;
    } finally {
      this.startPromise = null;
    }
  }

  async runTurn(
    phoneRequest: string,
    existingThreadId?: string,
    threadTitle?: string,
    timing?: PhaseTimer
  ): Promise<TurnResult> {
    const logger = timing ?? new PhaseTimer("codex-turn");
    this.activeTiming = logger;
    this.userMessageLogged = false;
    try {
      await this.start(logger);

      const threadParams: Record<string, unknown> = {
        dynamicTools: buildDhdDynamicTools(),
        model: resolveCodexModel(),
        cwd: this.runtimeCwd
      };

      let threadId: string | null = null;
      if (this.activeDhdThreadId && this.activeDhdThreadId !== existingThreadId) {
        await this.unsubscribeThread(this.activeDhdThreadId, logger);
      }
      const mayReuseExistingThread = Boolean(existingThreadId && this.hasCurrentDhdThread);
      if (mayReuseExistingThread && existingThreadId && this.loadedThreadIds.has(existingThreadId)) {
        threadId = existingThreadId;
        logger.log("thread:reuse_loaded", `threadId=${threadId}`);
      } else if (mayReuseExistingThread && existingThreadId) {
        logger.log("resume:start", `threadId=${existingThreadId}`);
        const threadResponse = await this.request("thread/resume", {
          ...threadParams,
          threadId: existingThreadId
        });
        threadId = extractThreadId(threadResponse.result) || existingThreadId;
        logger.log("resume:complete", `threadId=${threadId}`);
      } else {
        if (existingThreadId) {
          logger.log("thread:fresh_contract", `replacingStoredThread=${existingThreadId}`);
        }
        logger.log("thread/start:start");
        const threadResponse = await this.request("thread/start", threadParams);
        threadId = extractThreadId(threadResponse.result);
        logger.log("thread/start:complete", `threadId=${threadId ?? "?"}`);
      }
      if (!threadId) throw new Error("Codex App Server did not return a thread id.");
      this.activeThreadId = threadId;
      this.activeDhdThreadId = threadId;
      this.loadedThreadIds.add(threadId);
      if (!existingThreadId && threadTitle?.trim()) {
        // Naming is best-effort: older App Server builds may not expose this
        // convenience method, but a failed name update must not lose a turn.
        try {
          await this.request("thread/name/set", {
            threadId,
            name: threadTitle.trim().slice(0, 80)
          });
        } catch (error) {
          console.error(`[codex-app-server] could not name thread: ${error instanceof Error ? error.message : String(error)}`);
        }
      }

      const completion = new Promise<TurnResult>((resolve, reject) => {
        this.turnCompletion = {
          resolve,
          reject,
          agentMessages: new Map(),
          nextAgentMessageOrder: 0
        };
      });
      try {
        this.turnStartedAt = Date.now();
        logger.log("turn/start:start", `threadId=${threadId}`);
        const turnStartResponse = await this.request("turn/start", {
          threadId,
          model: resolveCodexModel(),
          effort: resolveCodexEffort(),
          cwd: this.runtimeCwd,
          input: [{ type: "text", text: phoneRequest }]
        });
        // `turn/start` returns the initial turn object. The notification is
        // also tracked below, but capturing this response makes cancellation
        // reliable even if the notification arrives after a timeout callback.
        this.activeTurnId = extractTurnId(turnStartResponse.result) || this.activeTurnId;
        this.logUserMessagePhaseFromValue(turnStartResponse.result, "turn/start.response");
        logger.log("turn/start:complete", `turnId=${this.activeTurnId ?? "?"}`);
      } catch (error) {
        this.turnCompletion?.reject(error instanceof Error ? error : new Error(String(error)));
        this.turnCompletion = null;
        throw error;
      }
      const result = await withTimeout(
        completion,
        parseTurnTimeout(process.env.PHONE_ASSISTANT_TURN_TIMEOUT_MS),
        () => {
          const elapsedMs = this.turnStartedAt ? Date.now() - this.turnStartedAt : undefined;
          const lastEvent = this.lastServerEvent || "no App Server event received";
          logger.log("turn:timeout", `lastEvent=${lastEvent.replaceAll(" ", "_")}`);
          console.error(
            `[codex-app-server] turn watchdog expired after ${elapsedMs ?? "?"}ms; ` +
            `last event: ${lastEvent}`
          );
          this.interruptTurn(threadId);
          return new Error(
            `Codex App Server turn timed out after ${elapsedMs ?? "?"}ms while waiting for turn/completed ` +
            `(last event: ${lastEvent}).`
          );
        }
      );
      this.hasCurrentDhdThread = true;
      return result;
    } catch (error) {
      // A failed/timeout turn may still be active inside App Server. Restart
      // the connection on the next request so a bad turn cannot poison the
      // persistent connection or make later requests fail mysteriously.
      await this.stopProcess();
      throw error;
    } finally {
      this.activeThreadId = null;
      this.activeTurnId = null;
      this.turnStartedAt = null;
      this.lastServerEvent = null;
      this.activeTiming = null;
      this.userMessageLogged = false;
      if (this.turnCompletion) {
        this.turnCompletion.reject(new Error("Codex App Server turn ended before completion."));
        this.turnCompletion = null;
      }
    }
  }

  /** Stop the persistent App Server connection during companion shutdown. */
  async close(): Promise<void> {
    await this.stopProcess();
  }

  /** Append a user instruction to the currently running turn. */
  async steer(text: string): Promise<void> {
    const safeText = text.trim().slice(0, MAX_STEER_CHARS);
    if (!safeText) throw new Error("A steer instruction is required.");
    const threadId = this.activeThreadId;
    const turnId = this.activeTurnId;
    if (!this.isTurnInFlight || !threadId || !turnId) {
      throw new Error("Codex has no active turn to steer.");
    }

    const response = await this.request("turn/steer", {
      threadId,
      input: [{ type: "text", text: safeText }],
      expectedTurnId: turnId
    });
    const acceptedTurnId = extractRecord(response.result)?.turnId;
    if (typeof acceptedTurnId === "string" && acceptedTurnId !== turnId) {
      throw new Error(`Codex accepted the steer for unexpected turn ${acceptedTurnId}.`);
    }
  }

  /** Interrupt the active turn, for example after the phone-side Stop action. */
  async interrupt(): Promise<void> {
    const threadId = this.activeThreadId;
    if (!threadId || !this.isTurnInFlight) return;
    const turnId = this.activeTurnId;
    await this.request("turn/interrupt", {
      threadId,
      ...(turnId ? { turnId } : {})
    });
  }

  private startProcess(): void {
    if (this.child) throw new Error("Codex App Server client is already running.");
    mkdirSync(this.codexHome, { recursive: true });
    mkdirSync(this.runtimeCwd, { recursive: true });
    const command = process.env.PHONE_ASSISTANT_CODEX_BIN?.trim() || "codex";
    const args = ["app-server", "--listen", "stdio://"];
    for (const override of [
      ...MINIMAL_CODEX_CONFIG_OVERRIDES,
      ...disabledConfiguredMcpOverrides(this.codexHome)
    ]) {
      args.push("-c", override);
    }
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
      ? `${quoteWindowsCommand(command)} ${args.map(quoteWindowsCommand).join(" ")}`
      : command;
    const child = spawn(windowsCommand, process.platform === "win32" ? [] : args, {
      stdio: ["pipe", "pipe", "pipe"],
      cwd: this.runtimeCwd,
      // On Windows Codex may be exposed as a .ps1/.cmd shim rather than a
      // native executable. Let cmd.exe resolve that user-installed command.
      shell: process.platform === "win32",
      windowsHide: true,
      env: {
        ...process.env,
        CODEX_HOME: this.codexHome
      }
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
      if (this.child === child) {
        this.child = null;
        this.reader = null;
        this.initialized = false;
        this.loadedThreadIds.clear();
      }
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

    if (message.method === "thread/started") {
      const threadId = extractThreadId(message.params);
      if (threadId) this.loadedThreadIds.add(threadId);
    } else if (message.method === "thread/closed") {
      const threadId = extractThreadId(message.params);
      if (threadId) {
        this.loadedThreadIds.delete(threadId);
        if (this.activeDhdThreadId === threadId) this.activeDhdThreadId = null;
      }
    } else if (message.method === "thread/status/changed") {
      const params = extractRecord(message.params);
      const status = extractRecord(params?.status);
      if (status?.type === "notLoaded") {
        const threadId = extractThreadId(message.params);
        if (threadId) {
          this.loadedThreadIds.delete(threadId);
          if (this.activeDhdThreadId === threadId) this.activeDhdThreadId = null;
        }
      }
    }

    const completion = this.turnCompletion;
    if (!completion || !message.method) return;
    this.lastServerEvent = describeServerEvent(message);
    logServerNotification(message);
    if (message.method === "turn/started") {
      this.activeTurnId = extractTurnId(message.params) || this.activeTurnId;
      this.turnStartedAt = this.turnStartedAt || Date.now();
      this.activeTiming?.log("turn/started", `turnId=${this.activeTurnId ?? "?"}`);
      return;
    }
    if (message.method === "item/started") {
      this.logUserMessagePhase(message);
      recordAgentMessageStarted(completion, message.params);
      return;
    }
    if (message.method === "item/agentMessage/delta") {
      recordAgentMessageDelta(completion, message.params);
      return;
    }
    if (message.method === "item/completed") {
      this.logUserMessagePhase(message);
      recordAgentMessageCompleted(completion, message.params);
      return;
    }
    if (message.method === "turn/completed") {
      const turn = extractRecord(message.params)?.turn;
      const status = extractRecord(turn)?.status;
      this.activeTiming?.log("turn/completed", `status=${String(status ?? "unknown")}`);
      if (status === "failed") {
        completion.reject(new Error(extractTurnError(message.params) || "Codex App Server turn failed."));
      } else if (status === "interrupted") {
        completion.reject(new Error("Codex App Server turn was interrupted."));
      } else if (status === "completed") {
        completion.resolve({
          text: selectFinalAgentMessageText(completion.agentMessages) || extractText(message.params),
          threadId: this.activeThreadId || ""
        });
      } else {
        completion.reject(
          new Error(`Codex App Server turn ended with unexpected status: ${String(status ?? "unknown")}.`)
        );
      }
      this.turnCompletion = null;
      return;
    }
    if (message.method === "turn/failed" || message.method === "error") {
      completion.reject(new Error(extractTurnError(message.params) || "Codex App Server turn failed."));
      this.turnCompletion = null;
    }
  }

  private logUserMessagePhase(message: JsonRpcMessage): void {
    this.logUserMessagePhaseFromValue(message.params, message.method || "notification");
  }

  private logUserMessagePhaseFromValue(value: unknown, event: string): void {
    if (this.userMessageLogged) return;
    const record = extractRecord(value);
    const candidates: unknown[] = [record?.item];
    const turn = extractRecord(record?.turn);
    if (Array.isArray(turn?.items)) candidates.push(...turn.items);
    if (Array.isArray(record?.items)) candidates.push(...record.items);
    if (record?.type === "userMessage") candidates.push(record);
    const userMessage = candidates
      .map((candidate) => extractRecord(candidate))
      .find((item) => item?.type === "userMessage");
    if (!userMessage) return;
    this.userMessageLogged = true;
    this.activeTiming?.log("userMessage", `event=${event}`);
  }

  private async unsubscribeThread(threadId: string, timing: PhaseTimer): Promise<void> {
    if (!this.loadedThreadIds.has(threadId)) {
      if (this.activeDhdThreadId === threadId) this.activeDhdThreadId = null;
      return;
    }
    timing.log("thread/unsubscribe:start", `threadId=${threadId}`);
    try {
      await this.request("thread/unsubscribe", { threadId });
      timing.log("thread/unsubscribe:complete", `threadId=${threadId}`);
    } catch (error) {
      timing.log("thread/unsubscribe:error", `threadId=${threadId}`);
      console.error(
        `[codex-app-server] could not unsubscribe superseded thread ${threadId}: ` +
        `${error instanceof Error ? error.message : String(error)}`
      );
    } finally {
      this.loadedThreadIds.delete(threadId);
      if (this.activeDhdThreadId === threadId) this.activeDhdThreadId = null;
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
      const turnId = this.activeTurnId;
      this.send({
        method: "turn/interrupt",
        id: `interrupt-${this.nextId++}`,
        params: { threadId, ...(turnId ? { turnId } : {}) }
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
    this.initialized = false;
    this.loadedThreadIds.clear();
    this.activeDhdThreadId = null;
    this.hasCurrentDhdThread = false;
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

const DHD_DYNAMIC_TOOL_TO_DISPATCH = {
  dhd_list_allowed_apps: "dhd_list_allowed_apps",
  dhd_observe: "dhd_observe",
  dhd_open_app: "dhd_open_app",
  dhd_execute: "dhd_execute",
  dhd_request_attention: "dhd_request_attention"
} as const;

type DynamicToolSpec = Record<string, unknown>;

/**
 * Register a small direct tool surface on the App Server thread. These are
 * intentionally separate names from the configured MCP tools. The App Server
 * delivers their calls to this companion; current builds normally reach that
 * request path through the bundled Code Mode host.
 */
export function buildDhdDynamicTools(): DynamicToolSpec[] {
  const metadata = {
    type: "object",
    properties: {
      purpose: { type: "string", minLength: 1, maxLength: 240 },
      targetDescription: { type: "string", minLength: 1, maxLength: 240 }
    },
    required: ["purpose", "targetDescription"],
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
        { type: { const: "tap" }, x: { type: "integer", minimum: 0 }, y: { type: "integer", minimum: 0 } },
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
      "dhd_list_allowed_apps",
      "List Android packages enabled in the phone-side per-app allowlist.",
      emptySchema()
    ),
    dynamicTool(
      "dhd_observe",
      "Capture the current physical Android display and return a screenshot for visual context. Include a concise purpose when this observation should appear in the user's activity timeline. Screen changes do not block a subsequent typed action.",
      {
        type: "object",
        properties: {
          expectedPackageName: { type: "string", minLength: 1 },
          purpose: { type: "string", minLength: 1, maxLength: 240 },
          targetDescription: { type: "string", minLength: 1, maxLength: 240 }
        },
        additionalProperties: false
      }
    ),
    dynamicTool(
      "dhd_open_app",
      "Open one allowlisted Android app and return a post-action screenshot. Include a meaningful user-facing purpose and concrete target description.",
      {
        type: "object",
        properties: {
          packageName: { type: "string", minLength: 1 },
          metadata
        },
        required: ["packageName", "metadata"],
        additionalProperties: false
      }
    ),
    dynamicTool(
      "dhd_execute",
      "Execute one typed phone action with metadata.purpose, then return a post-action screenshot. Never use shell commands. Actions are not rejected because the screenshot changed.",
      {
        type: "object",
        properties: { action },
        required: ["action"],
        additionalProperties: false
      }
    ),
    dynamicTool(
      "dhd_request_attention",
      "Notify the user that their attention is needed without bringing the assistant app to the foreground. Use this when the user must review or take over; do not continue phone actions afterward.",
      {
        type: "object",
        properties: { reason: { type: "string", minLength: 1, maxLength: 240 } },
        required: ["reason"],
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
  const mappedName = DHD_DYNAMIC_TOOL_TO_DISPATCH[name as keyof typeof DHD_DYNAMIC_TOOL_TO_DISPATCH];
  if (!mappedName) {
    return dynamicToolFailure(`Unsupported dynamic phone tool: ${requestedName || "(missing tool name)"}`);
  }

  const input = normalizeDynamicArguments(params.arguments);
  console.error(`[codex-app-server] invoking ${name}`);
  const result = await invokeDhdTool(mappedName, input);
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
  const duration = typeof item.durationMs === "number" ? ` [${item.durationMs}ms]` : "";
  console.error(`[codex-app-server] ${message.method} ${type}${tool}${status}${duration}`);
}

/**
 * Keep timeout diagnostics useful without logging tool arguments, screenshots,
 * or model text. App Server notifications are intentionally summarized by
 * method plus the small lifecycle fields that identify what was last active.
 */
function describeServerEvent(message: JsonRpcMessage): string {
  const method = message.method || "unknown event";
  const params = extractRecord(message.params);
  const turn = extractRecord(params?.turn);
  if (method.startsWith("turn/") && turn) {
    const status = typeof turn.status === "string" ? ` (${turn.status})` : "";
    return `${method}${status}`;
  }
  const item = extractRecord(params?.item);
  if (item) {
    const type = typeof item.type === "string" ? item.type : "item";
    const tool = typeof item.tool === "string" ? ` ${item.tool}` : "";
    const status = typeof item.status === "string" ? ` (${item.status})` : "";
    const duration = typeof item.durationMs === "number" ? ` [${item.durationMs}ms]` : "";
    return `${method} ${type}${tool}${status}${duration}`;
  }
  return method;
}

interface ActiveCodexTurn {
  sessionId: string;
  client: CodexAppServerClient;
}

/**
 * The phone bridge is pull-based: the desktop companion polls the phone over
 * the adb-forwarded socket. Keep the active App Server client here so those
 * polls can deliver steering input to the same in-flight turn.
 */
let activeCodexTurn: ActiveCodexTurn | null = null;

export async function runAssistantCompanion(): Promise<void> {
  const pollIntervalMs = parsePollInterval(process.env.PHONE_ASSISTANT_POLL_MS);
  let stopping = false;
  const codexClient = new CodexAppServerClient();
  let pendingRun: Promise<void> | null = null;
  const stop = () => {
    stopping = true;
    const active = activeCodexTurn;
    if (active) {
      void active.client.interrupt().catch((error) => {
        console.error(`[phone-assistant-companion] could not interrupt on shutdown: ${error instanceof Error ? error.message : String(error)}`);
      });
    }
  };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);

  console.error("[phone-assistant-companion] waiting for a request typed in the Android app");
  console.error(`[phone-assistant-companion] phone bridge target ${bridgeHost}:${bridgePort}`);
  if (isLoopbackBridgeHost(bridgeHost)) {
    console.error("[phone-assistant-companion] loopback mode: adb forward tcp:8765 tcp:8765 is still supported");
  } else {
    console.error("[phone-assistant-companion] wireless mode: phone and laptop must share Wi-Fi and PHONE_ASSISTANT_BRIDGE_TOKEN must match DHD settings");
  }
  console.error("[phone-assistant-companion] a logged-in Codex CLI must be available on this companion host");

  try {
    await prewarmCodexClient(codexClient, "codex-prewarm");
    while (!stopping) {
      const pollStartedAt = performance.now();
      if (debugTimingEnabled()) logCompanionPhase("poll:start");
      try {
        if (!pendingRun && !activeCodexTurn) {
          const pending = await requestBridge(
            { type: "pending_request", requestId: randomUUID() },
            { timeoutMs: BRIDGE_POLL_TIMEOUT_MS }
          );
          if (debugTimingEnabled()) {
            logCompanionPhase(
              "poll:complete",
              `durationMs=${Math.round(performance.now() - pollStartedAt)} available=${pending.available === true}`
            );
          }
          if (pending.warmupRequested === true) {
            logCompanionPhase("codex:warmup_requested");
            await prewarmCodexClient(codexClient, "codex-app-open-warmup");
          }
          if (pending.ok === true && pending.available === true) {
            logCompanionPhase(
              "poll:request_detected",
              `durationMs=${Math.round(performance.now() - pollStartedAt)}`
            );
            pendingRun = processPendingRequest(pending, codexClient)
              .catch((error) => {
                console.error(`[phone-assistant-companion] phone request runner failed: ${error instanceof Error ? error.message : String(error)}`);
              })
              .finally(() => {
                pendingRun = null;
              });
          } else if (pending.ok === false) {
            console.error(`[phone-assistant-companion] phone bridge rejected poll: ${String(pending.message ?? "unknown error")}`);
          }
        } else if (activeCodexTurn) {
          await processPendingSteer(activeCodexTurn);
        }
      } catch (error) {
        logCompanionPhase(
          "poll:error",
          `durationMs=${Math.round(performance.now() - pollStartedAt)}`
        );
        // The phone may be disconnected or the bridge may not be running yet.
        // Keep polling so reconnecting the device does not require a restart.
        console.error(`[phone-assistant-companion] ${error instanceof Error ? error.message : String(error)}`);
      }
      if (!stopping) await delay(pollIntervalMs);
    }
  } finally {
    if (pendingRun) await pendingRun;
    await codexClient.close();
  }
}

async function prewarmCodexClient(
  codexClient: CodexAppServerClient,
  scope: string
): Promise<boolean> {
  for (let attempt = 1; attempt <= PREWARM_ATTEMPTS; attempt += 1) {
    const timing = new PhaseTimer(scope);
    try {
      timing.log("start", `attempt=${attempt}`);
      await codexClient.start(timing);
      timing.log("complete", `attempt=${attempt}`);
      return true;
    } catch (error) {
      timing.log("error", `attempt=${attempt}`);
      console.error(
        `[phone-assistant-companion] Codex prewarm attempt ${attempt}/${PREWARM_ATTEMPTS} failed: ` +
        `${error instanceof Error ? error.message : String(error)}`
      );
      if (attempt < PREWARM_ATTEMPTS) await delay(PREWARM_RETRY_DELAY_MS);
    }
  }
  console.error("[phone-assistant-companion] continuing without a warm Codex connection; the next request will retry startup");
  return false;
}

async function processPendingRequest(
  pending: BridgeMessage,
  codexClient: CodexAppServerClient
): Promise<void> {
  const sessionId = typeof pending.sessionId === "string" ? pending.sessionId : "";
  if (!sessionId) {
    console.error("[phone-assistant-companion] pending request did not include a session id");
    return;
  }
  const timing = new PhaseTimer(`phone-request:${sessionId}`);
  timing.log("claim:start");
  let claimed: BridgeMessage;
  try {
    claimed = await requestBridge({
      type: "claim_request",
      requestId: randomUUID(),
      sessionId
    });
    timing.log("claim:complete", `ok=${claimed.ok === true}`);
  } catch (error) {
    timing.log("claim:error");
    throw error;
  }
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
  activeCodexTurn = { sessionId, client: codexClient };
  try {
    const conversationId = typeof claimed.conversationId === "string" ? claimed.conversationId : undefined;
    const existingThreadId = typeof claimed.codexThreadId === "string" ? claimed.codexThreadId : undefined;
    const threadTitle = typeof claimed.title === "string" ? claimed.title : request;
    const result = await codexClient.runTurn(request, existingThreadId, threadTitle, timing);
    if (conversationId && result.threadId && result.threadId !== existingThreadId) {
      const bound = await requestBridge({
        type: "bind_codex_thread",
        requestId: randomUUID(),
        conversationId,
        codexThreadId: result.threadId
      });
      if (bound.ok !== true) {
        throw new Error(`The phone did not bind Codex thread ${result.threadId}: ${String(bound.message ?? "unknown error")}`);
      }
    }
    console.error(
      `[phone-assistant-companion] Codex turn reached terminal status; closing phone session` +
      `${result.text ? `; final assistant message: ${result.text.slice(0, 500)}` : ""}`
    );
    const feedback = normalizeAgentFeedback(result.text);
    const completed = await requestBridge({
      type: "complete_session",
      requestId: randomUUID(),
      sessionId,
      message: feedback || DEFAULT_COMPLETION_MESSAGE,
      ...(feedback ? { feedback } : {})
    });
    if (completed.ok !== true) {
      console.error(`[phone-assistant-companion] could not mark the phone session complete: ${String(completed.message ?? "unknown error")}`);
    }
  } catch (error) {
    console.error(`[phone-assistant-companion] Codex turn failed: ${error instanceof Error ? error.message : String(error)}`);
    try {
      await requestBridge({
        type: "fail_session",
        requestId: randomUUID(),
        sessionId,
        reason: error instanceof Error ? error.message : String(error)
      });
    } catch (failureError) {
      console.error(`[phone-assistant-companion] could not mark the phone session failed: ${failureError instanceof Error ? failureError.message : String(failureError)}`);
    }
  } finally {
    if (activeCodexTurn?.sessionId === sessionId) activeCodexTurn = null;
  }
}

async function processPendingSteer(active: ActiveCodexTurn): Promise<void> {
  if (!active.client.isTurnInFlight) return;

  const pending = await requestBridge(
    {
      type: "pending_steer",
      requestId: randomUUID(),
      sessionId: active.sessionId
    },
    { timeoutMs: BRIDGE_POLL_TIMEOUT_MS }
  );
  if (pending.ok !== true) {
    if (pending.message) {
      console.error(`[phone-assistant-companion] phone bridge rejected steer poll: ${String(pending.message)}`);
    }
    return;
  }

  // A phone-side Stop changes the coordinator state before the next poll. In
  // that case interrupt Codex as well so the desktop turn cannot continue
  // operating the phone after the user has stopped it.
  if (pending.active === false && active.client.isTurnInFlight) {
    await active.client.interrupt().catch((error) => {
      console.error(`[phone-assistant-companion] could not interrupt stopped phone session: ${error instanceof Error ? error.message : String(error)}`);
    });
    return;
  }
  // The App Server may still be completing turn/start. Leave a queued steer
  // untouched until its thread and turn ids are available for turn/steer.
  if (!active.client.canSteer) return;
  if (pending.available !== true) return;

  const steerId = typeof pending.steerId === "string" ? pending.steerId : "";
  if (!steerId) {
    console.error("[phone-assistant-companion] pending steer did not include a steer id");
    return;
  }
  const claimed = await requestBridge({
    type: "claim_steer",
    requestId: randomUUID(),
    sessionId: active.sessionId,
    steerId
  });
  if (claimed.ok !== true) {
    if (claimed.code !== "STEER_NOT_AVAILABLE") {
      console.error(`[phone-assistant-companion] could not claim steer ${steerId}: ${String(claimed.message ?? "unknown error")}`);
    }
    return;
  }

  const text = typeof claimed.text === "string" ? claimed.text.trim() : "";
  if (!text) {
    await releaseSteer(steerId, active.sessionId);
    return;
  }

  try {
    await active.client.steer(text);
    const completed = await requestBridge({
      type: "complete_steer",
      requestId: randomUUID(),
      sessionId: active.sessionId,
      steerId
    });
    if (completed.ok !== true) {
      console.error(`[phone-assistant-companion] could not mark steer ${steerId} delivered: ${String(completed.message ?? "unknown error")}`);
    }
    console.error(`[phone-assistant-companion] delivered steer ${steerId} to the active Codex turn`);
  } catch (error) {
    console.error(`[phone-assistant-companion] Codex steer failed: ${error instanceof Error ? error.message : String(error)}`);
    await releaseSteer(steerId, active.sessionId);
  }
}

async function releaseSteer(steerId: string, sessionId: string): Promise<void> {
  try {
    await requestBridge({
      type: "release_steer",
      requestId: randomUUID(),
      sessionId,
      steerId
    });
  } catch (error) {
    console.error(`[phone-assistant-companion] could not release steer ${steerId}: ${error instanceof Error ? error.message : String(error)}`);
  }
}

async function releaseRequest(sessionId: string): Promise<void> {
  try {
    await requestBridge({ type: "release_request", requestId: randomUUID(), sessionId });
  } catch (error) {
    console.error(`[phone-assistant-companion] could not release request: ${error instanceof Error ? error.message : String(error)}`);
  }
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
  if (typeof record.threadId === "string" && record.threadId) return record.threadId;
  return typeof record.id === "string" && record.id ? record.id : null;
}

function extractTurnId(value: unknown): string | null {
  const record = extractRecord(value);
  if (!record) return null;
  const turn = extractRecord(record.turn);
  if (turn && typeof turn.id === "string" && turn.id) return turn.id;
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

function recordAgentMessageStarted(
  completion: {
    agentMessages: Map<string, AgentMessageState>;
    nextAgentMessageOrder: number;
  },
  value: unknown
): void {
  const item = extractAgentMessageItem(value);
  if (!item) return;
  const state = getAgentMessageState(completion, extractAgentMessageId(value) || UNSCOPED_AGENT_MESSAGE_ID);
  if (typeof item.text === "string") state.text = item.text;
  state.phase = extractAgentMessagePhase(value) || state.phase;
  state.completed = false;
  touchAgentMessage(completion, state);
}

function recordAgentMessageDelta(
  completion: {
    agentMessages: Map<string, AgentMessageState>;
    nextAgentMessageOrder: number;
  },
  value: unknown
): void {
  const delta = extractText(value);
  if (!delta) return;
  const state = getAgentMessageState(completion, extractAgentMessageId(value) || UNSCOPED_AGENT_MESSAGE_ID);
  state.text += delta;
  state.phase = extractAgentMessagePhase(value) || state.phase;
  touchAgentMessage(completion, state);
}

function recordAgentMessageCompleted(
  completion: {
    agentMessages: Map<string, AgentMessageState>;
    nextAgentMessageOrder: number;
  },
  value: unknown
): void {
  const item = extractAgentMessageItem(value);
  if (!item) return;
  const state = getAgentMessageState(completion, extractAgentMessageId(value) || UNSCOPED_AGENT_MESSAGE_ID);
  // item/completed is authoritative for the full agentMessage text. This
  // replaces any streamed deltas for this item without touching other phases.
  if (typeof item.text === "string") state.text = item.text;
  state.phase = extractAgentMessagePhase(value) || state.phase;
  state.completed = true;
  touchAgentMessage(completion, state);
}

function selectFinalAgentMessageText(agentMessages: Map<string, AgentMessageState>): string {
  const messages = [...agentMessages.values()]
    .filter((message) => message.text.trim())
    .sort((left, right) => left.lastEventOrder - right.lastEventOrder);
  const finalAnswer = [...messages]
    .reverse()
    .find((message) => message.completed && message.phase === "final_answer");
  if (finalAnswer) return finalAnswer.text;
  const lastCompleted = [...messages].reverse().find((message) => message.completed);
  if (lastCompleted) return lastCompleted.text;
  const lastFinalPhase = [...messages].reverse().find((message) => message.phase === "final_answer");
  return lastFinalPhase?.text || messages.at(-1)?.text || "";
}

const UNSCOPED_AGENT_MESSAGE_ID = "__agent_message_without_item_id__";

function getAgentMessageState(
  completion: {
    agentMessages: Map<string, AgentMessageState>;
    nextAgentMessageOrder: number;
  },
  id: string
): AgentMessageState {
  const existing = completion.agentMessages.get(id);
  if (existing) return existing;
  const state: AgentMessageState = {
    id,
    text: "",
    completed: false,
    lastEventOrder: 0
  };
  completion.agentMessages.set(id, state);
  return state;
}

function touchAgentMessage(
  completion: {
    nextAgentMessageOrder: number;
  },
  state: AgentMessageState
): void {
  state.lastEventOrder = ++completion.nextAgentMessageOrder;
}

function extractAgentMessageItem(value: unknown): Record<string, unknown> | null {
  const record = extractRecord(value);
  const item = extractRecord(record?.item) || record;
  return item?.type === "agentMessage" ? item : null;
}

function extractAgentMessageId(value: unknown): string | null {
  const record = extractRecord(value);
  if (!record) return null;
  if (typeof record.itemId === "string" && record.itemId) return record.itemId;
  const item = extractRecord(record.item);
  if (typeof item?.id === "string" && item.id) return item.id;
  if (record.type === "agentMessage" && typeof record.id === "string" && record.id) return record.id;
  return null;
}

function extractAgentMessagePhase(value: unknown): string | null {
  const record = extractRecord(value);
  if (!record) return null;
  if (typeof record.phase === "string" && record.phase) return record.phase;
  const item = extractRecord(record.item);
  return typeof item?.phase === "string" && item.phase ? item.phase : null;
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

function resolveCodexHome(): string {
  const configured = process.env.PHONE_ASSISTANT_CODEX_HOME?.trim();
  return configured ? resolve(configured) : DEFAULT_CODEX_HOME;
}

function resolveCodexRuntimeCwd(): string {
  const configured = process.env.PHONE_ASSISTANT_CODEX_CWD?.trim();
  return configured ? resolve(configured) : DEFAULT_CODEX_RUNTIME_CWD;
}

/**
 * Codex merges table-valued `-c` overrides with the selected home config.
 * Explicitly disable each MCP server configured in that same home as well as
 * passing `mcp_servers={}` so a DHD child cannot start unrelated integrations
 * during a phone turn. Only section names are read; credentials and command
 * values never enter logs.
 */
function disabledConfiguredMcpOverrides(codexHome: string): string[] {
  const configPath = join(codexHome, "config.toml");
  let config: string;
  try {
    config = readFileSync(configPath, "utf8");
  } catch {
    return [];
  }
  const names = new Set<string>();
  for (const line of config.split(/\r?\n/)) {
    const match = line.match(/^\s*\[mcp_servers\.([A-Za-z0-9_-]+)(?:[.\]])/);
    if (match?.[1]) names.add(match[1]);
  }
  return [...names].sort().map((name) => `mcp_servers.${name}.enabled=false`);
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

function resolveCodexModel(): string {
  return process.env.PHONE_ASSISTANT_CODEX_MODEL?.trim() || DEFAULT_CODEX_MODEL;
}

function resolveCodexEffort(): string {
  return process.env.PHONE_ASSISTANT_CODEX_REASONING_EFFORT?.trim() || DEFAULT_CODEX_EFFORT;
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
