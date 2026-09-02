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

export const GUARD_REGIONS_FEATURE_FLAG = "PHONE_ASSISTANT_ENABLE_GUARD_REGIONS";

export const DHD_MAX_SEQUENCE_ACTIONS = 16;

const ENABLED_FEATURE_VALUES = new Set(["1", "true", "yes", "on"]);

export function isGuardRegionsEnabled(
  environment: NodeJS.ProcessEnv = process.env
): boolean {
  return ENABLED_FEATURE_VALUES.has(
    (environment[GUARD_REGIONS_FEATURE_FLAG] ?? "").trim().toLowerCase()
  );
}

const guardRegionSchema = z
  .object({
    left: z.number().int().min(0),
    top: z.number().int().min(0),
    right: z.number().int().min(0),
    bottom: z.number().int().min(0)
  })
  .strict();

function createActionMetadataSchema(
  enableGuardRegions: boolean,
  requireObservationId = true
) {
  return z
    .object({
      purpose: z.string().min(1).max(240),
      targetDescription: z.string().min(1).max(240),
      ...(requireObservationId
        ? { observationId: z.string().min(1).max(240) }
        : {}),
      ...(enableGuardRegions
        ? { guardRegions: z.array(guardRegionSchema).max(8).optional().default([]) }
        : {})
    })
    .strict();
}

export function createDhdToolSchemas(enableGuardRegions: boolean = isGuardRegionsEnabled()) {
  const actionMetadataSchema = createActionMetadataSchema(enableGuardRegions);
  const openAppMetadataSchema = createActionMetadataSchema(false);
  const sequenceActionMetadataSchema = createActionMetadataSchema(
    enableGuardRegions,
    false
  );

  const dhdOpenAppInputSchema = z
    .object({
      packageName: packageNameSchema,
      metadata: openAppMetadataSchema
    })
    .strict();

  const dhdListAllowedAppsInputSchema = z
    .object({
      includeAll: z.boolean().optional().default(false)
    })
    .strict();

  const dhdBrowseAppInputSchema = z
    .object({
      query: z.string().trim().min(1).max(120)
    })
    .strict();

  const dhdExecuteActionSchema = z.discriminatedUnion("type", [
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

  const dhdExecuteSequenceInputSchema = z
    .object({
      observationId: z.string().min(1).max(240),
      actions: z.array(
        z.discriminatedUnion("type", [
          z
            .object({
              type: z.literal("tap"),
              x: z.number().int().min(0),
              y: z.number().int().min(0),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal("type"),
              text: z.string().min(1).max(4096),
              metadata: sequenceActionMetadataSchema
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
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal("scroll"),
              direction: z.enum(["up", "down", "left", "right"]),
              amount: z.enum(["small", "medium", "large"]),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal("back"),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal("keypress"),
              key: z.enum(["BACK", "HOME", "ENTER", "DELETE"]),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal("wait"),
              durationMs: z.number().int().min(1).max(30_000),
              metadata: sequenceActionMetadataSchema
            })
            .strict()
        ])
      ).min(1).max(DHD_MAX_SEQUENCE_ACTIONS)
    })
    .strict();

  const dhdObserveInputSchema = z
    .object({
      expectedPackageName: packageNameSchema.optional(),
      purpose: z.string().min(1).max(240).optional(),
      targetDescription: z.string().min(1).max(240).optional(),
      ...(enableGuardRegions
        ? { guardRegions: z.array(guardRegionSchema).max(8).optional().default([]) }
        : {})
    })
    .strict();

  return {
    dhdOpenAppInputSchema,
    dhdListAllowedAppsInputSchema,
    dhdBrowseAppInputSchema,
    dhdExecuteActionSchema,
    dhdObserveInputSchema,
    dhdExecuteSequenceInputSchema
  };
}

const defaultDhdToolSchemas = createDhdToolSchemas(isGuardRegionsEnabled());

/**
 * This is deliberately a phone-owned action contract. It does not include a
 * shell command, package-manager operation, or arbitrary code payload.
 * `metadata.purpose` is user-visible in the phone timeline/notification.
 */
export const dhdOpenAppInputSchema = defaultDhdToolSchemas.dhdOpenAppInputSchema;
export const dhdListAllowedAppsInputSchema = defaultDhdToolSchemas.dhdListAllowedAppsInputSchema;
export const dhdBrowseAppInputSchema = defaultDhdToolSchemas.dhdBrowseAppInputSchema;
export const dhdExecuteActionSchema = defaultDhdToolSchemas.dhdExecuteActionSchema;
export const dhdObserveInputSchema = defaultDhdToolSchemas.dhdObserveInputSchema;
export const dhdExecuteSequenceInputSchema = defaultDhdToolSchemas.dhdExecuteSequenceInputSchema;

export const DHD_TOOL_NAMES = [
  "dhd_list_allowed_apps",
  "dhd_browse_app",
  "dhd_observe",
  "dhd_open_app",
  "dhd_execute",
  "dhd_execute_sequence",
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
  const schemas = createDhdToolSchemas();
  switch (name) {
    case "dhd_list_allowed_apps":
      return safely(() => {
        const parsed = parseInput(schemas.dhdListAllowedAppsInputSchema, input);
        return requestBridge({
          type: "allowed_apps",
          requestId: randomUUID(),
          includeAll: parsed.includeAll
        });
      });
    case "dhd_browse_app":
      return safely(() => {
        const parsed = parseInput(schemas.dhdBrowseAppInputSchema, input);
        return requestBridge({
          type: "browse_apps",
          requestId: randomUUID(),
          query: parsed.query
        });
      });
    case "dhd_observe":
      return safely(() => {
        const parsed = parseInput(schemas.dhdObserveInputSchema, input);
        const parsedGuardRegions = (parsed as Record<string, unknown>).guardRegions;
        const guardRegions = Array.isArray(parsedGuardRegions) && parsedGuardRegions.length > 0
          ? parsedGuardRegions
          : undefined;
        return requestBridge({
          type: "observe",
          requestId: randomUUID(),
          ...(parsed.expectedPackageName ? { expectedPackageName: parsed.expectedPackageName } : {}),
          ...(parsed.purpose ? { purpose: parsed.purpose } : {}),
          ...(parsed.targetDescription ? { targetDescription: parsed.targetDescription } : {}),
          ...(guardRegions ? { guardRegions } : {})
        });
      });
    case "dhd_open_app":
      return safely(() => {
        const parsed = parseInput(schemas.dhdOpenAppInputSchema, input);
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
        const action = parseInput(schemas.dhdExecuteActionSchema, readRecord(input).action);
        return requestBridge({ type: "execute_action", requestId: randomUUID(), action });
      });
    case "dhd_execute_sequence":
      return safely(() => {
        const parsed = parseInput(schemas.dhdExecuteSequenceInputSchema, input);
        return requestBridge({
          type: "execute_sequence",
          requestId: randomUUID(),
          observationId: parsed.observationId,
          actions: parsed.actions
        });
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
  const enableGuardRegions = isGuardRegionsEnabled();
  const schemas = createDhdToolSchemas(enableGuardRegions);
  const guardRegionGuidance = enableGuardRegions
    ? " When enabled, provide the same guardRegions to dhd_observe and the corresponding action or first sequence step only when the target must remain unchanged."
    : "";
  const server = new McpServer({ name: serverName, version });

  server.registerTool(
    "dhd_list_allowed_apps",
    {
      description: "Check the phone's app-access mode. By default, keep the result compact: return the explicit allowlist in restricted mode or Full Access capability metadata. Set includeAll to true only when Full Access is active and you need the complete launchable app list.",
      inputSchema: schemas.dhdListAllowedAppsInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_list_allowed_apps", input)
  );

  server.registerTool(
    "dhd_browse_app",
    {
      description: "Search launchable phone apps by label or package name. Return matching app labels and package names without opening an app or changing permissions. In restricted mode, results are limited to the explicit allowlist.",
      inputSchema: schemas.dhdBrowseAppInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_browse_app", input)
  );

  server.registerTool(
    "dhd_observe",
    {
      description: `Capture the current physical phone display as a PNG for visual context. Use this before every phone action; copy the returned observation.id into the action metadata.observationId.${guardRegionGuidance}`,
      inputSchema: schemas.dhdObserveInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_observe", input)
  );

  server.registerTool(
    "dhd_open_app",
    {
      description: "Open one launchable Android app and return the actual post-action observation. Observe first and copy its observation.id into metadata.observationId. In restricted mode the app must be on the explicit allowlist; Full Access lets you use any launchable app. Include a meaningful user-facing purpose and concrete target description.",
      inputSchema: schemas.dhdOpenAppInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_open_app", input)
  );

  server.registerTool(
    "dhd_execute",
    {
      description: `Execute one typed phone interaction and return the actual post-action observation. Observe first and copy its observation.id into metadata.observationId. Use tap, type, swipe, scroll, back, keypress, or wait.${guardRegionGuidance} If post-action observation fails, treat the action as unknown and observe before retrying. Do not send shell commands.`,
      inputSchema: { action: schemas.dhdExecuteActionSchema }
    },
    async (input) => invokeDhdTool("dhd_execute", input)
  );

  server.registerTool(
    "dhd_execute_sequence",
    {
      description: `Execute up to ${DHD_MAX_SEQUENCE_ACTIONS} typed phone interactions in order from one observation baseline. Use this only when every later target is predictable without inspecting intermediate screenshots; use dhd_execute for adaptive or branching work. The phone captures and verifies a post-action observation after every step, and returns the final observation only after the full sequence is verified. Observe first and pass its observation.id as the top-level observationId. Do not include open_app, shell commands, semantic targets, or execution modes.${enableGuardRegions ? " When enabled, provide guardRegions on the first dhd_observe and the corresponding sequence steps when the guarded target must remain unchanged." : ""} If any step fails, the sequence stops; if post-action observation fails, treat the outcome as unknown and observe before retrying.`,
      inputSchema: schemas.dhdExecuteSequenceInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_execute_sequence", input)
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
