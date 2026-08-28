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

const guardRegionSchema = z
  .object({
    left: z.number().int().min(0),
    top: z.number().int().min(0),
    right: z.number().int().min(1),
    bottom: z.number().int().min(1)
  })
  .strict()
  .refine((region) => region.right > region.left, {
    message: "right must be greater than left"
  })
  .refine((region) => region.bottom > region.top, {
    message: "bottom must be greater than top"
  });

const actionMetadataSchema = z
  .object({
    purpose: z.string().min(1).max(240),
    observationId: z.string().min(1).max(240),
    targetDescription: z.string().min(1).max(240),
    guardRegions: z.array(guardRegionSchema).max(8).optional()
  })
  .strict();

/**
 * This is deliberately a phone-owned action contract. It does not include a
 * shell command, package-manager operation, or arbitrary code payload.
 * `metadata.purpose` is user-visible in the phone timeline/notification.
 */
export const phoneAssistantActionSchema = z.discriminatedUnion("type", [
  z
    .object({
      type: z.literal("open_app"),
      packageName: packageNameSchema,
      metadata: actionMetadataSchema
    })
    .strict(),
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
      type: z.literal("click_coordinate"),
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

const observeInputSchema = z
  .object({
    expectedPackageName: packageNameSchema.optional(),
    guardRegions: z.array(guardRegionSchema).max(8).optional()
  })
  .strict();

export const PHONE_ASSISTANT_TOOL_NAMES = [
  "phone_assistant_status",
  "phone_assistant_pending_request",
  "phone_assistant_list_allowed_apps",
  "phone_assistant_start",
  "phone_assistant_observe",
  "phone_assistant_execute",
  "phone_assistant_stop"
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

function toMcpResult(message: BridgeMessage, error?: unknown) {
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

export function createPhoneAssistantMcpServer(
  serverName = "phone-assistant",
  version = "0.1.0"
): McpServer {
  const server = new McpServer({ name: serverName, version });

  server.registerTool(
    "phone_assistant_status",
    {
      description: "Report whether the phone-side assistant session is idle, running, paused, stopped, or completed.",
      inputSchema: {}
    },
    async () => safely(() => requestBridge({ type: "status", requestId: randomUUID() }))
  );

  server.registerTool(
    "phone_assistant_pending_request",
    {
      description: "Check whether the phone has a typed request waiting for the desktop Codex companion. This is read-only; the companion claims requests separately.",
      inputSchema: {}
    },
    async () => safely(() => requestBridge({ type: "pending_request", requestId: randomUUID() }))
  );

  server.registerTool(
    "phone_assistant_list_allowed_apps",
    {
      description: "List the installed Android packages currently enabled in the phone-side per-app allowlist. Apps are off by default; use the phone settings screen to change this list.",
      inputSchema: {}
    },
    async () => safely(() => requestBridge({ type: "allowed_apps", requestId: randomUUID() }))
  );

  server.registerTool(
    "phone_assistant_start",
    {
      description: "Start a phone-assistant session for a natural-language request. The phone remains the authority for app permissions, stale observations, confirmations, and cancellation.",
      inputSchema: { request: z.string().min(1).max(16_384) }
    },
    async (input) => safely(() => requestBridge({
      type: "start_session",
      requestId: randomUUID(),
      request: parseInput(z.string().min(1).max(16_384), input.request)
    }))
  );

  server.registerTool(
    "phone_assistant_observe",
    {
      description: "Capture the current physical phone display as a PNG plus a fresh observationId. Call this before proposing an action and use the returned observationId in that action's metadata.",
      inputSchema: observeInputSchema.shape
    },
    async (input) => safely(() => {
      const parsed = parseInput(observeInputSchema, input);
      return requestBridge({
        type: "observe",
        requestId: randomUUID(),
        ...(parsed.expectedPackageName ? { expectedPackageName: parsed.expectedPackageName } : {}),
        ...(parsed.guardRegions ? { guardRegions: parsed.guardRegions } : {})
      });
    })
  );

  server.registerTool(
    "phone_assistant_execute",
    {
      description: "Execute one typed phone action against the exact observationId supplied in its metadata, then return a fresh post-action screenshot. Include a concise human-readable purpose such as 'Searching for jollof rice' or 'Selecting the delivery address'. Do not send shell commands.",
      inputSchema: { action: phoneAssistantActionSchema }
    },
    async (input) => safely(() => {
      const action = parseInput(phoneAssistantActionSchema, input.action);
      return requestBridge({ type: "execute_action", requestId: randomUUID(), action });
    })
  );

  server.registerTool(
    "phone_assistant_stop",
    {
      description: "Stop the active phone-assistant session and cancel further execution.",
      inputSchema: { reason: z.string().min(1).max(240).optional() }
    },
    async (input) => safely(() => {
      const reason = input.reason === undefined
        ? undefined
        : parseInput(z.string().min(1).max(240), input.reason);
      return requestBridge({
        type: "stop_session",
        requestId: randomUUID(),
        ...(reason ? { reason } : {})
      });
    })
  );

  return server;
}

function isMainModule(): boolean {
  return process.argv[1]?.endsWith("assistant-mcp-server.ts") === true ||
    process.argv[1]?.endsWith("assistant-mcp-server.js") === true;
}

if (isMainModule()) {
  const server = createPhoneAssistantMcpServer();
  void server.connect(new StdioServerTransport()).catch((error: unknown) => {
    console.error(`[phone-assistant-mcp] startup failed: ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  });
}
