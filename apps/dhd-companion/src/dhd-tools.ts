import { randomUUID } from "node:crypto";

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

import { requestBridge, type BridgeMessage } from "./phone-assistant-bridge.js";


const packageNameSchema = z
  .string()
  .min(1)
  .max(255)
  .regex(/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/);

const actionMetadataSchema = z
  .object({
    purpose: z.string().min(1).max(240),
    targetDescription: z.string().min(1).max(240)
  })
  .strict();

/**
 * This is deliberately a phone-owned action contract. It does not include a
 * shell command, package-manager operation, or arbitrary code payload.
 * `metadata.purpose` is user-visible in the phone timeline/notification.
 */
export const dhdOpenAppInputSchema = z
  .object({
    packageName: packageNameSchema,
    metadata: actionMetadataSchema
  })
  .strict();

export const dhdExecuteActionSchema = z.discriminatedUnion("type", [
  z
    .object({
      type: z.literal("tap"),
      x: z.number().int().min(0),
      y: z.number().int().min(0),
      metadata: actionMetadataSchema
    })
    .strict(),
  z
    .object({
      type: z.literal("type"),
      text: z.string().min(1).max(4096),
      metadata: actionMetadataSchema
    })
    .strict(),
  z
    .object({
      type: z.literal("swipe"),
      startX: z.number().int().min(0),
      startY: z.number().int().min(0),
      endX: z.number().int().min(0),
      endY: z.number().int().min(0),
      durationMs: z.number().int().min(1).max(10_000).optional(),
      metadata: actionMetadataSchema
    })
    .strict(),
  z
    .object({
      type: z.literal("scroll"),
      direction: z.enum(["up", "down", "left", "right"]),
      amount: z.enum(["small", "medium", "large"]),
      metadata: actionMetadataSchema
    })
    .strict(),
  z
    .object({
      type: z.literal("back"),
      metadata: actionMetadataSchema
    })
    .strict(),
  z
    .object({
      type: z.literal("keypress"),
      key: z.enum(["BACK", "HOME", "ENTER", "DELETE"]),
      metadata: actionMetadataSchema
    })
    .strict(),
  z
    .object({
      type: z.literal("wait"),
      durationMs: z.number().int().min(1).max(30_000),
      metadata: actionMetadataSchema
    })
    .strict()
]);

export const dhdObserveInputSchema = z
  .object({
    expectedPackageName: packageNameSchema.optional(),
    purpose: z.string().min(1).max(240).optional(),
    targetDescription: z.string().min(1).max(240).optional()
  })
  .strict();

export const DHD_TOOL_NAMES = [
  "dhd_list_allowed_apps",
  "dhd_observe",
  "dhd_open_app",
  "dhd_execute",
  "dhd_request_attention"
] as const;

function parseInput<T>(schema: z.ZodType<T>, input: unknown): T {
  const parsed = schema.safeParse(input);
  if (!parsed.success) {
    throw new Error(`Invalid phone assistant input: ${parsed.error.message}`);
  }
  return parsed.data;
}

function withoutScreenshot(message: BridgeMessage): Record<string, unknown> {
  const copy = { ...message };
  delete copy.screenshotBase64;
  return copy;
}

type AssistantTextContent = { type: "text"; text: string };
type AssistantImageContent = { type: "image"; data: string; mimeType: "image/png" };

export interface PhoneAssistantToolResult {
  [key: string]: unknown;
  isError?: boolean;
  content: Array<AssistantTextContent | AssistantImageContent>;
  structuredContent?: Record<string, unknown>;
}

function toMcpResult(message: BridgeMessage, error?: unknown): PhoneAssistantToolResult {
  const isError = Boolean(error) || message.ok === false;
  const content: Array<AssistantTextContent | AssistantImageContent> = [
    {
      type: "text",
      text: JSON.stringify(error ? { ok: false, message: error instanceof Error ? error.message : String(error) } : withoutScreenshot(message))
    }
  ];
  const screenshot = typeof message.screenshotBase64 === "string" ? message.screenshotBase64 : undefined;
  if (screenshot) {
    content.push({ type: "image", data: screenshot, mimeType: "image/png" });
  }
  return {
    ...(isError ? { isError: true } : {}),
    content,
    structuredContent: error
      ? { ok: false, message: error instanceof Error ? error.message : String(error) }
      : withoutScreenshot(message)
  };
}

async function safely(work: () => Promise<BridgeMessage>) {
  try {
    return toMcpResult(await work());
  } catch (error) {
    console.error(`[phone-assistant-mcp] ${error instanceof Error ? error.message : String(error)}`);
    return toMcpResult({ ok: false }, error);
  }
}

/**
 * Invoke one of the phone tools without going through a second MCP transport.
 *
 * The companion registers these same operations as App Server dynamic tools so
 * a Codex turn can call the phone directly. Keeping this dispatcher beside the
 * MCP registrations prevents the two tool surfaces from drifting apart.
 */
export async function invokeDhdTool(
  name: string,
  input: unknown
): Promise<PhoneAssistantToolResult> {
  switch (name) {
    case "dhd_list_allowed_apps":
      return safely(() => requestBridge({ type: "allowed_apps", requestId: randomUUID() }));
    case "dhd_observe":
      return safely(() => {
        const parsed = parseInput(dhdObserveInputSchema, input);
        return requestBridge({
          type: "observe",
          requestId: randomUUID(),
          ...(parsed.expectedPackageName ? { expectedPackageName: parsed.expectedPackageName } : {}),
          ...(parsed.purpose ? { purpose: parsed.purpose } : {}),
          ...(parsed.targetDescription ? { targetDescription: parsed.targetDescription } : {})
        });
      });
    case "dhd_open_app":
      return safely(() => {
        const parsed = parseInput(dhdOpenAppInputSchema, input);
        return requestBridge({
          type: "execute_action",
          requestId: randomUUID(),
          action: {
            type: "open_app",
            packageName: parsed.packageName,
            metadata: parsed.metadata
          }
        });
      });
    case "dhd_execute":
      return safely(() => {
        const action = parseInput(dhdExecuteActionSchema, readRecord(input).action);
        return requestBridge({ type: "execute_action", requestId: randomUUID(), action });
      });
    case "dhd_request_attention":
      return safely(() => {
        const reason = parseInput(z.string().min(1).max(240), readRecord(input).reason);
        return requestBridge({ type: "request_attention", requestId: randomUUID(), reason });
      });
    default:
      throw new Error(`Unknown DHD tool: ${name}`);
  }
}

function readRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

export function createDhdMcpServer(
  serverName = "dhd",
  version = "0.1.0"
): McpServer {
  const server = new McpServer({ name: serverName, version });

  server.registerTool(
    "dhd_list_allowed_apps",
    {
      description: "Check the phone's app-access mode. In restricted mode, return the explicit allowlist; when Full Access is active, return a concise capability message saying you can use any launchable app without enumerating installed apps.",
      inputSchema: {}
    },
    async () => invokeDhdTool("dhd_list_allowed_apps", {})
  );

  server.registerTool(
    "dhd_observe",
    {
      description: "Capture the current physical phone display as a PNG for visual context. Use it to choose the next typed action; screenshots do not block an action if the screen changes.",
      inputSchema: dhdObserveInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_observe", input)
  );

  server.registerTool(
    "dhd_open_app",
    {
      description: "Open one launchable Android app and return a post-action screenshot. In restricted mode the app must be on the explicit allowlist; Full Access lets you use any launchable app. Include a meaningful user-facing purpose and concrete target description.",
      inputSchema: dhdOpenAppInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_open_app", input)
  );

  server.registerTool(
    "dhd_execute",
    {
      description: "Execute one typed phone interaction and return a post-action screenshot. Use tap, type, swipe, scroll, back, keypress, or wait. Include a meaningful user-facing purpose and concrete target description. Do not send shell commands.",
      inputSchema: { action: dhdExecuteActionSchema }
    },
    async (input) => invokeDhdTool("dhd_execute", input)
  );

  server.registerTool(
    "dhd_request_attention",
    {
      description: "Notify the user that the phone assistant needs their attention. This does not open the app automatically.",
      inputSchema: { reason: z.string().min(1).max(240) }
    },
    async (input) => invokeDhdTool("dhd_request_attention", input)
  );

  return server;
}

function isMainModule(): boolean {
  return process.argv[1]?.endsWith("dhd-tools.ts") === true ||
    process.argv[1]?.endsWith("dhd-tools.js") === true;
}

if (isMainModule()) {
  const server = createDhdMcpServer();
  void server.connect(new StdioServerTransport()).catch((error: unknown) => {
    console.error(`[phone-assistant-mcp] startup failed: ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  });
}
