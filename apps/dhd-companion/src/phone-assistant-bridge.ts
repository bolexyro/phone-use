import net from "node:net";

export const DEFAULT_BRIDGE_HOST = "127.0.0.1";
export const DEFAULT_BRIDGE_PORT = 8765;
export const DEFAULT_BRIDGE_TIMEOUT_MS = 45_000;
export const MAX_BRIDGE_RESPONSE_BYTES = 16 * 1024 * 1024;

export interface BridgeMessage {
  type?: string;
  ok?: boolean;
  [key: string]: unknown;
}

export interface BridgeRequest {
  type: string;
  requestId: string;
  [key: string]: unknown;
}

export interface BridgeRequestOptions {
  timeoutMs?: number;
  host?: string;
  port?: number;
  token?: string;
}

export const bridgeHost = process.env.PHONE_ASSISTANT_BRIDGE_HOST?.trim() || DEFAULT_BRIDGE_HOST;
export const bridgePort = parsePort(process.env.PHONE_ASSISTANT_BRIDGE_PORT ?? `${DEFAULT_BRIDGE_PORT}`);
export const bridgeToken = process.env.PHONE_ASSISTANT_BRIDGE_TOKEN?.trim() || undefined;

const TERMINAL_MESSAGE_TYPES = new Set([
  "error",
  "started",
  "status",
  "pending_request",
  "pending_steer",
  "request_claimed",
  "request_released",
  "steer_claimed",
  "steer_released",
  "steer_completed",
  "codex_thread_bound",
  "attention_requested",
  "session_completed",
  "session_failed",
  "allowed_apps",
  "browse_apps",
  "foreground_app",
  "observation",
  "completed",
  "stopped"
]);

export function parsePort(value: string): number {
  if (!/^\d+$/.test(value)) throw new Error("PHONE_ASSISTANT_BRIDGE_PORT must be an integer.");
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new Error("PHONE_ASSISTANT_BRIDGE_PORT must be between 1 and 65535.");
  }
  return port;
}

export function isLoopbackBridgeHost(host: string): boolean {
  const normalized = host.trim().toLowerCase();
  return normalized === "localhost" || normalized === "127.0.0.1" || normalized === "::1" || normalized === "[::1]";
}

export function buildBridgePayload(
  request: BridgeRequest,
  token?: string,
): BridgeRequest {
  const effectiveToken = arguments.length > 1 ? token : bridgeToken;
  const safeToken = effectiveToken?.trim();
  return safeToken ? { ...request, authToken: safeToken } : { ...request };
}

export function bridgeConfigurationError(
  host: string = bridgeHost,
  token: string | undefined = bridgeToken,
): string | null {
  if (!isLoopbackBridgeHost(host) && !token?.trim()) {
    return "PHONE_ASSISTANT_BRIDGE_TOKEN is required when PHONE_ASSISTANT_BRIDGE_HOST is not loopback.";
  }
  return null;
}

/** Send one request to the phone-local NDJSON bridge and await its terminal line. */
export function requestBridge(
  request: BridgeRequest,
  options: BridgeRequestOptions = {}
): Promise<BridgeMessage> {
  const host = options.host ?? bridgeHost;
  const port = options.port ?? bridgePort;
  const token = options.token ?? bridgeToken;
  const configurationError = bridgeConfigurationError(host, token);
  if (configurationError) return Promise.reject(new Error(configurationError));
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port });
    let buffer = "";
    let responseBytes = 0;
    let settled = false;

    const finish = (error?: Error, message?: BridgeMessage) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      if (error) reject(error);
      else resolve(message!);
    };

    socket.setTimeout(options.timeoutMs ?? DEFAULT_BRIDGE_TIMEOUT_MS, () => {
      finish(new Error("Timed out waiting for the phone assistant bridge."));
    });
    socket.once("error", (error) => {
      finish(new Error(`Could not connect to the phone assistant bridge at ${host}:${port}: ${error.message}`));
    });
    socket.once("close", () => {
      if (!settled) finish(new Error("The phone assistant bridge closed before completing the request."));
    });
    socket.once("connect", () => {
      socket.write(`${JSON.stringify(buildBridgePayload(request, token))}\n`);
    });
    socket.on("data", (chunk: Buffer) => {
      responseBytes += chunk.byteLength;
      if (responseBytes > MAX_BRIDGE_RESPONSE_BYTES) {
        finish(new Error("The phone assistant bridge response is too large."));
        return;
      }
      buffer += chunk.toString("utf8");
      let newline = buffer.indexOf("\n");
      while (newline >= 0) {
        const line = buffer.slice(0, newline).trim();
        buffer = buffer.slice(newline + 1);
        newline = buffer.indexOf("\n");
        if (!line) continue;
        let message: BridgeMessage;
        try {
          message = JSON.parse(line) as BridgeMessage;
        } catch {
          finish(new Error("The phone assistant bridge returned invalid JSON."));
          return;
        }
        // The phone sends an accepted progress line first. Resolve only on a
        // terminal response so callers can safely read the complete result.
        if (typeof message.type === "string" && TERMINAL_MESSAGE_TYPES.has(message.type)) {
          finish(undefined, message);
          return;
        }
      }
    });
  });
}
