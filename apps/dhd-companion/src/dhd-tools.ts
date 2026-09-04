import { randomUUID } from "node:crypto";
import { Buffer } from "node:buffer";

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

import { requestBridge, type BridgeMessage } from "./phone-assistant-bridge.js";
import {
  DHD_ACTION_TYPES,
  DHD_KEYPRESS_KEYS,
  DHD_MAX_GUARD_REGIONS,
  DHD_MAX_SEQUENCE_ACTIONS,
  DHD_MAX_SWIPE_DURATION_MS,
  DHD_MAX_TEXT_CHARS,
  DHD_MAX_TYPE_TEXT_CHARS,
  DHD_MAX_WAIT_DURATION_MS,
  DHD_SCROLL_AMOUNTS,
  DHD_SCROLL_DIRECTIONS,
  dhdToolDescription,
  isGuardRegionsEnabled,
} from "./dhd-tool-contract.js";
import {
  ScreenshotMarkerPresenter,
  type ScreenshotMarker,
  type ScreenshotMarkerObservation,
  type ScreenshotMarkerPoint,
} from "@dhd/screenshot-markers";

export * from "./dhd-tool-contract.js";

const packageNameSchema = z
  .string()
  .min(1)
  .max(255)
  .regex(/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/);

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
      purpose: z.string().min(1).max(DHD_MAX_TEXT_CHARS),
      targetDescription: z.string().min(1).max(DHD_MAX_TEXT_CHARS),
      ...(requireObservationId
        ? { observationId: z.string().min(1).max(DHD_MAX_TEXT_CHARS) }
        : {}),
      ...(enableGuardRegions
        ? { guardRegions: z.array(guardRegionSchema).max(DHD_MAX_GUARD_REGIONS).optional().default([]) }
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

  const dhdGetForegroundAppInputSchema = z.object({}).strict();

  const dhdExecuteActionSchema = z.discriminatedUnion("type", [
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.tap),
        x: z.number().int().min(0),
        y: z.number().int().min(0),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.type),
        text: z.string().min(1).max(DHD_MAX_TYPE_TEXT_CHARS),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.swipe),
        startX: z.number().int().min(0),
        startY: z.number().int().min(0),
        endX: z.number().int().min(0),
        endY: z.number().int().min(0),
        durationMs: z.number().int().min(1).max(DHD_MAX_SWIPE_DURATION_MS).optional(),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.scroll),
        direction: z.enum(DHD_SCROLL_DIRECTIONS),
        amount: z.enum(DHD_SCROLL_AMOUNTS),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.back),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.keypress),
        key: z.enum(DHD_KEYPRESS_KEYS),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.wait),
        durationMs: z.number().int().min(1).max(DHD_MAX_WAIT_DURATION_MS),
        metadata: actionMetadataSchema
      })
      .strict()
  ]);

  const dhdExecuteSequenceInputSchema = z
    .object({
      observationId: z.string().min(1).max(DHD_MAX_TEXT_CHARS),
      actions: z.array(
        z.discriminatedUnion("type", [
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.tap),
              x: z.number().int().min(0),
              y: z.number().int().min(0),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.type),
              text: z.string().min(1).max(DHD_MAX_TYPE_TEXT_CHARS),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.swipe),
              startX: z.number().int().min(0),
              startY: z.number().int().min(0),
              endX: z.number().int().min(0),
              endY: z.number().int().min(0),
              durationMs: z.number().int().min(1).max(DHD_MAX_SWIPE_DURATION_MS).optional(),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.scroll),
              direction: z.enum(DHD_SCROLL_DIRECTIONS),
              amount: z.enum(DHD_SCROLL_AMOUNTS),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.back),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.keypress),
              key: z.enum(DHD_KEYPRESS_KEYS),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.wait),
              durationMs: z.number().int().min(1).max(DHD_MAX_WAIT_DURATION_MS),
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
      purpose: z.string().min(1).max(DHD_MAX_TEXT_CHARS).optional(),
      targetDescription: z.string().min(1).max(DHD_MAX_TEXT_CHARS).optional(),
      ...(enableGuardRegions
        ? { guardRegions: z.array(guardRegionSchema).max(DHD_MAX_GUARD_REGIONS).optional().default([]) }
        : {})
    })
    .strict();

  return {
    dhdOpenAppInputSchema,
    dhdListAllowedAppsInputSchema,
    dhdBrowseAppInputSchema,
    dhdGetForegroundAppInputSchema,
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
export const dhdGetForegroundAppInputSchema = defaultDhdToolSchemas.dhdGetForegroundAppInputSchema;
export const dhdExecuteActionSchema = defaultDhdToolSchemas.dhdExecuteActionSchema;
export const dhdObserveInputSchema = defaultDhdToolSchemas.dhdObserveInputSchema;
export const dhdExecuteSequenceInputSchema = defaultDhdToolSchemas.dhdExecuteSequenceInputSchema;

function parseInput<T>(schema: z.ZodType<T>, input: unknown): T {
  const parsed = schema.safeParse(input);
  if (!parsed.success) {
    throw new Error(`Invalid phone assistant input: ${parsed.error.message}`);
  }
  return parsed.data;
}

const DHD_SCREENSHOT_MIME_TYPE = "image/png" as const;
const SCREENSHOT_DATA_URL_PATTERN = /^data:([^;,]+);base64,([\s\S]*)$/i;
const screenshotMarkerPresenter = new ScreenshotMarkerPresenter();

export interface NormalizedScreenshot {
  base64: string;
  mimeType: typeof DHD_SCREENSHOT_MIME_TYPE;
  dataUrl: string;
}

/**
 * Keep the two transport representations explicit:
 *
 * - MCP image content carries bare base64 in `data`.
 * - App Server dynamic-tool content carries a `data:` URL in `imageUrl`.
 *
 * The phone bridge currently sends bare base64, but accepting an already
 * prefixed data URL here prevents an accidental double prefix if another
 * bridge adapter is introduced later.
 */
export function normalizeScreenshot(
  value: unknown,
  declaredMimeType: unknown = DHD_SCREENSHOT_MIME_TYPE,
): NormalizedScreenshot | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "string") {
    throw new Error("The phone assistant returned a non-string screenshot payload.");
  }

  const raw = value.trim();
  if (!raw) return undefined;

  const declared = typeof declaredMimeType === "string" && declaredMimeType.trim()
    ? declaredMimeType.trim().toLowerCase()
    : DHD_SCREENSHOT_MIME_TYPE;
  let mimeType = declared;
  let base64 = raw;
  if (raw.startsWith("data:")) {
    const match = SCREENSHOT_DATA_URL_PATTERN.exec(raw);
    if (!match) {
      throw new Error("The phone assistant returned an invalid screenshot data URL.");
    }
    mimeType = match[1].toLowerCase();
    base64 = match[2];
    if (declared !== DHD_SCREENSHOT_MIME_TYPE && declared !== mimeType) {
      throw new Error("The screenshot MIME type does not match its data URL.");
    }
  }
  if (mimeType !== DHD_SCREENSHOT_MIME_TYPE) {
    throw new Error(`Unsupported phone screenshot MIME type: ${mimeType}.`);
  }

  base64 = base64.replace(/\s+/g, "");
  if (!base64 || !/^[A-Za-z0-9+/]+={0,2}$/.test(base64) || base64.length % 4 !== 0) {
    throw new Error("The phone assistant returned invalid base64 screenshot data.");
  }

  return {
    base64,
    mimeType: DHD_SCREENSHOT_MIME_TYPE,
    dataUrl: `data:${DHD_SCREENSHOT_MIME_TYPE};base64,${base64}`,
  };
}

function withoutScreenshot(message: BridgeMessage): Record<string, unknown> {
  const copy = { ...message };
  delete copy.screenshotBase64;
  return copy;
}

type AssistantTextContent = { type: "text"; text: string };
type AssistantImageContent = {
  type: "image";
  data: string;
  mimeType: typeof DHD_SCREENSHOT_MIME_TYPE;
};

export interface PhoneAssistantToolResult {
  [key: string]: unknown;
  isError?: boolean;
  content: Array<AssistantTextContent | AssistantImageContent>;
  structuredContent?: Record<string, unknown>;
}

interface DhdMarkerContext {
  resetMarker?: boolean;
  action?: Record<string, unknown>;
  sequenceActions?: readonly Record<string, unknown>[];
}

function markerObservation(message: BridgeMessage): ScreenshotMarkerObservation | undefined {
  const observation = readRecord(message.observation);
  const observationId = typeof observation.id === "string" ? observation.id : undefined;
  const displayId = typeof observation.displayId === "number" ? observation.displayId : undefined;
  const rotation = typeof observation.rotation === "number" ? observation.rotation : undefined;
  const width = typeof observation.width === "number" ? observation.width : undefined;
  const height = typeof observation.height === "number" ? observation.height : undefined;
  if (!observationId || width === undefined || height === undefined) return undefined;
  return {
    observationId,
    displayId,
    packageName: typeof observation.packageName === "string" ? observation.packageName : undefined,
    rotation,
    screenshotDimensions: { width, height },
  };
}

function tapPoint(value: Record<string, unknown> | undefined): ScreenshotMarkerPoint | undefined {
  if (value?.type !== "tap" || !Number.isInteger(value.x) || !Number.isInteger(value.y)) {
    return undefined;
  }
  return { x: value.x as number, y: value.y as number };
}

function successfulSequenceTap(
  message: BridgeMessage,
  actions: readonly Record<string, unknown>[] | undefined
): ScreenshotMarkerPoint | undefined {
  if (!actions) return undefined;
  const steps = Array.isArray(message.steps) ? message.steps : [];
  for (let index = steps.length - 1; index >= 0; index -= 1) {
    const step = readRecord(steps[index]);
    if (step.status !== "success" || !Number.isInteger(step.index)) continue;
    const action = actions[step.index as number];
    const point = tapPoint(action);
    if (point) return point;
  }
  return undefined;
}

function markerForContext(
  message: BridgeMessage,
  context: DhdMarkerContext | undefined
): ScreenshotMarkerPoint | undefined {
  if (!context) return undefined;
  if (context.action) {
    return message.ok === true ? tapPoint(context.action) : undefined;
  }
  return successfulSequenceTap(message, context.sequenceActions);
}

function renderScreenshot(
  message: BridgeMessage,
  screenshot: NormalizedScreenshot,
  context: DhdMarkerContext | undefined
): { screenshot: NormalizedScreenshot; marker?: ScreenshotMarker } {
  const observation = markerObservation(message);
  if (!observation) return { screenshot };
  if (context?.resetMarker) {
    screenshotMarkerPresenter.reset(observation.displayId);
  }
  try {
    const rendered = screenshotMarkerPresenter.render(
      Buffer.from(screenshot.base64, "base64"),
      observation,
      { lastTap: markerForContext(message, context) }
    );
    const base64 = Buffer.from(rendered.screenshot).toString("base64");
    return {
      screenshot: {
        base64,
        mimeType: screenshot.mimeType,
        dataUrl: `data:${screenshot.mimeType};base64,${base64}`,
      },
      marker: rendered.marker,
    };
  } catch (error) {
    console.error(
      `[phone-assistant-mcp] screenshot marker render failed: ${
        error instanceof Error ? error.message : String(error)
      }`
    );
    return { screenshot };
  }
}

export function toMcpResult(
  message: BridgeMessage,
  error?: unknown,
  markerContext?: DhdMarkerContext
): PhoneAssistantToolResult {
  const isError = Boolean(error) || message.ok === false;
  if (message.type === "stopped") screenshotMarkerPresenter.reset();
  const content: Array<AssistantTextContent | AssistantImageContent> = [
    { type: "text", text: "" }
  ];
  let screenshot = normalizeScreenshot(message.screenshotBase64, message.screenshotMimeType);
  let marker: ScreenshotMarker | undefined;
  if (screenshot && !error) {
    const rendered = renderScreenshot(message, screenshot, markerContext);
    screenshot = rendered.screenshot;
    marker = rendered.marker;
  }
  const responseMessage = withoutScreenshot(message);
  if (marker) responseMessage.screenshotMarker = marker;
  content[0] = {
    type: "text",
    text: JSON.stringify(error ? { ok: false, message: error instanceof Error ? error.message : String(error) } : responseMessage)
  };
  if (screenshot) {
    content.push({ type: "image", data: screenshot.base64, mimeType: screenshot.mimeType });
  }
  return {
    ...(isError ? { isError: true } : {}),
    content,
    structuredContent: error
      ? { ok: false, message: error instanceof Error ? error.message : String(error) }
      : responseMessage
  };
}

async function safely(
  work: () => Promise<BridgeMessage>,
  markerContext?: () => DhdMarkerContext | undefined
) {
  try {
    return toMcpResult(await work(), undefined, markerContext?.());
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
    case "dhd_get_foreground_app":
      return safely(() => {
        parseInput(schemas.dhdGetForegroundAppInputSchema, input);
        return requestBridge({
          type: "foreground_app",
          requestId: randomUUID()
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
      let openedAction: Record<string, unknown> | undefined;
      return safely(() => {
        const parsed = parseInput(schemas.dhdOpenAppInputSchema, input);
        openedAction = {
          type: "open_app",
          packageName: parsed.packageName,
        };
        return requestBridge({
          type: "execute_action",
          requestId: randomUUID(),
          action: {
            type: "open_app",
            packageName: parsed.packageName,
            metadata: parsed.metadata
          }
        });
      }, () => ({ resetMarker: true, action: openedAction }));
    case "dhd_execute":
      let executedAction: Record<string, unknown> | undefined;
      return safely(() => {
        const action = parseInput(schemas.dhdExecuteActionSchema, readRecord(input).action);
        executedAction = action as unknown as Record<string, unknown>;
        return requestBridge({ type: "execute_action", requestId: randomUUID(), action });
      }, () => ({ action: executedAction }));
    case "dhd_execute_sequence":
      let sequenceActions: readonly Record<string, unknown>[] | undefined;
      return safely(() => {
        const parsed = parseInput(schemas.dhdExecuteSequenceInputSchema, input);
        sequenceActions = parsed.actions as readonly Record<string, unknown>[];
        return requestBridge({
          type: "execute_sequence",
          requestId: randomUUID(),
          observationId: parsed.observationId,
          actions: parsed.actions
        });
      }, () => ({ sequenceActions }));
    case "dhd_request_attention":
      return safely(() => {
        const reason = parseInput(z.string().min(1).max(DHD_MAX_TEXT_CHARS), readRecord(input).reason);
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
  const server = new McpServer({ name: serverName, version });

  server.registerTool(
    "dhd_list_allowed_apps",
    {
      description: dhdToolDescription("dhd_list_allowed_apps", enableGuardRegions),
      inputSchema: schemas.dhdListAllowedAppsInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_list_allowed_apps", input)
  );

  server.registerTool(
    "dhd_browse_app",
    {
      description: dhdToolDescription("dhd_browse_app", enableGuardRegions),
      inputSchema: schemas.dhdBrowseAppInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_browse_app", input)
  );

  server.registerTool(
    "dhd_get_foreground_app",
    {
      description: dhdToolDescription("dhd_get_foreground_app", enableGuardRegions),
      inputSchema: schemas.dhdGetForegroundAppInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_get_foreground_app", input)
  );

  server.registerTool(
    "dhd_observe",
    {
      description: dhdToolDescription("dhd_observe", enableGuardRegions),
      inputSchema: schemas.dhdObserveInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_observe", input)
  );

  server.registerTool(
    "dhd_open_app",
    {
      description: dhdToolDescription("dhd_open_app", enableGuardRegions),
      inputSchema: schemas.dhdOpenAppInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_open_app", input)
  );

  server.registerTool(
    "dhd_execute",
    {
      description: dhdToolDescription("dhd_execute", enableGuardRegions),
      inputSchema: { action: schemas.dhdExecuteActionSchema }
    },
    async (input) => invokeDhdTool("dhd_execute", input)
  );

  server.registerTool(
    "dhd_execute_sequence",
    {
      description: dhdToolDescription("dhd_execute_sequence", enableGuardRegions),
      inputSchema: schemas.dhdExecuteSequenceInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_execute_sequence", input)
  );

  server.registerTool(
    "dhd_request_attention",
    {
      description: dhdToolDescription("dhd_request_attention", enableGuardRegions),
      inputSchema: { reason: z.string().min(1).max(DHD_MAX_TEXT_CHARS) }
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
