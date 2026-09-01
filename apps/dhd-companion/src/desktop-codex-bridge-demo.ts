import net from "node:net";
import { randomUUID } from "node:crypto";

interface DemoOptions {
  host: string;
  port: number;
  token?: string;
  packageName: string;
  x: number;
  y: number;
  purpose: string;
  targetDescription: string;
}

/**
 * Development-only desktop companion stub.
 *
 * The real companion will obtain this same typed plan from Codex App Server.
 * For now the plan is explicit and deterministic so we can validate the
 * desktop-to-phone boundary without pretending that Codex is connected yet.
 */
export async function runDesktopCodexBridgeDemo(
  argv: readonly string[] = process.argv.slice(2),
): Promise<void> {
  const options = parseOptions(argv);
  const requestId = randomUUID();
  const request = {
    type: "demo_run",
    requestId,
    ...(options.token ? { authToken: options.token } : {}),
    packageName: options.packageName,
    x: options.x,
    y: options.y,
    purpose: options.purpose,
    targetDescription: options.targetDescription,
  };

  await new Promise<void>((resolve, reject) => {
    const socket = net.createConnection({ host: options.host, port: options.port });
    let buffer = "";
    let finished = false;
    const finish = (error?: Error) => {
      if (finished) return;
      finished = true;
      socket.end();
      if (error) reject(error);
      else resolve();
    };

    socket.setTimeout(30_000, () => {
      finish(new Error("Timed out waiting for the phone bridge."));
    });
    socket.once("connect", () => {
      console.error(
        `[desktop-bridge] connected to ${options.host}:${options.port}; sending open_app -> tap`,
      );
      socket.write(`${JSON.stringify(request)}\n`);
    });
    socket.on("data", (chunk: Buffer) => {
      buffer += chunk.toString("utf8");
      let newline = buffer.indexOf("\n");
      while (newline >= 0) {
        const line = buffer.slice(0, newline).trim();
        buffer = buffer.slice(newline + 1);
        newline = buffer.indexOf("\n");
        if (!line) continue;
        let message: Record<string, unknown>;
        try {
          message = JSON.parse(line) as Record<string, unknown>;
        } catch {
          finish(new Error(`Phone bridge returned invalid JSON: ${line}`));
          return;
        }
        printBridgeMessage(message);
        if (message.type === "error") {
          finish(new Error(String(message.message ?? "The phone bridge rejected the demo.")));
          return;
        }
        if (message.type === "completed") {
          finish();
          return;
        }
      }
    });
    socket.once("error", (error) => {
      finish(new Error(`Could not connect to the phone bridge: ${error.message}`));
    });
    socket.once("close", () => {
      if (!finished) finish(new Error("The phone bridge closed before completing the demo."));
    });
  });
}

function printBridgeMessage(message: Record<string, unknown>): void {
  const type = String(message.type ?? "message");
  const action = message.action ? ` ${String(message.action)}` : "";
  const detail = message.message ? ` — ${String(message.message)}` : "";
  console.error(`[phone-bridge] ${type}${action}${detail}`);
}

function parseOptions(argv: readonly string[]): DemoOptions {
  const values = new Map<string, string>();
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (!value.startsWith("--")) {
      throw new Error(`Unexpected argument ${value}.`);
    }
    const key = value.slice(2);
    const next = argv[index + 1];
    if (!next || next.startsWith("--")) {
      throw new Error(`Missing value for --${key}.`);
    }
    values.set(key, next);
    index += 1;
  }

  const host = values.get("host") ?? "127.0.0.1";
  const port = parseInteger(values.get("port") ?? "8765", "port");
  const token = values.get("token")?.trim() || process.env.PHONE_ASSISTANT_BRIDGE_TOKEN?.trim() || undefined;
  const packageName = values.get("package") ?? "com.phonecontrol.coordinatebenchmark";
  if (!/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/.test(packageName)) {
    throw new Error(`Invalid Android package name: ${packageName}.`);
  }
  const x = parseInteger(values.get("x") ?? "500", "x");
  const y = parseInteger(values.get("y") ?? "900", "y");
  if (x < 0 || y < 0) throw new Error("x and y must be non-negative.");
  const purpose = values.get("purpose") ?? "Developer-selected demo coordinate";
  const targetDescription = values.get("target") ?? "developer-selected coordinate";
  if (!purpose.trim() || purpose.length > 240) throw new Error("--purpose must be 1-240 characters.");
  if (!targetDescription.trim() || targetDescription.length > 240) {
    throw new Error("--target must be 1-240 characters.");
  }
  return { host, port, token, packageName, x, y, purpose, targetDescription };
}

function parseInteger(value: string, name: string): number {
  if (!/^-?\d+$/.test(value)) throw new Error(`--${name} must be an integer.`);
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed)) throw new Error(`--${name} is outside the safe integer range.`);
  return parsed;
}

const isMainModule = process.argv[1]?.endsWith("desktop-codex-bridge-demo.ts") ||
  process.argv[1]?.endsWith("desktop-codex-bridge-demo.js");

if (isMainModule) {
  runDesktopCodexBridgeDemo().catch((error: unknown) => {
    console.error(`[desktop-bridge] ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  });
}
