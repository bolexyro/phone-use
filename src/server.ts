import { Buffer } from "node:buffer";

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";

import { asPhoneControlError, PhoneControlError, toMachineError } from "./errors.js";
import { ObservationStore } from "./observation-store.js";
import type {
  ActionData,
  AllowedAppsData,
  ObservationSummary,
  PhoneExecuteRequest,
  PhoneStatusData,
  ToolSuccessResult,
  WaitCondition
} from "./types.js";

export const PHONE_CONTROL_TOOL_NAMES = [
  "phone_status",
  "phone_list_allowed_apps",
  "phone_open_app",
  "phone_observe",
  "phone_execute",
  "phone_wait_for"
] as const;

const packageNameSchema = z.string().min(1).max(255);

export const phoneActionSchema = z.discriminatedUnion("type", [
  z
    .object({
      type: z.literal("click"),
      elementRef: z.string().min(1)
    })
    .strict(),
  z
    .object({
      type: z.literal("click_coordinate"),
      x: z.number().int(),
      y: z.number().int()
    })
    .strict(),
  z
    .object({
      type: z.literal("scroll"),
      direction: z.enum(["up", "down", "left", "right"]),
      amount: z.enum(["small", "medium", "large"])
    })
    .strict(),
  z
    .object({
      type: z.literal("type"),
      text: z.string().min(1).max(4096)
    })
    .strict(),
  z
    .object({
      type: z.literal("keypress"),
      key: z.enum(["BACK", "HOME", "ENTER", "DELETE"])
    })
    .strict()
]);

export const phoneExecuteInputSchema = z
  .object({
    observationId: z.string().min(1),
    action: phoneActionSchema
  })
  .strict();

export const phoneObserveInputSchema = z
  .object({
    includeScreenshot: z.boolean().optional()
  })
  .strict();

export const waitConditionSchema = z.discriminatedUnion("type", [
  z
    .object({
      type: z.literal("foreground_package"),
      packageName: packageNameSchema
    })
    .strict(),
  z
    .object({
      type: z.literal("visible_text"),
      text: z.string().min(1)
    })
    .strict(),
  z
    .object({
      type: z.literal("visible_resource_id"),
      resourceId: z.string().min(1)
    })
    .strict(),
  z
    .object({
      type: z.literal("ui_tree_changed")
    })
    .strict()
]);

export const phoneWaitForInputSchema = z
  .object({
    observationId: z.string().min(1),
    condition: waitConditionSchema,
    timeoutMs: z.number().int().min(0).max(30_000).optional()
  })
  .strict();

export const phoneOpenAppInputSchema = z
  .object({ packageName: packageNameSchema })
  .strict();

export interface PhoneControlToolService {
  readonly observationStore: ObservationStore;
  status(): Promise<ToolSuccessResult<PhoneStatusData>>;
  allowedApps(): ToolSuccessResult<AllowedAppsData>;
  openApp(
    packageName: string
  ): Promise<ToolSuccessResult<{ observation: ObservationSummary }>>;
  observe(): Promise<ToolSuccessResult<{ observation: ObservationSummary }>>;
  execute(request: PhoneExecuteRequest): Promise<ToolSuccessResult<ActionData>>;
  waitFor(
    observationId: string,
    condition: WaitCondition,
    options?: { timeoutMs?: number }
  ): Promise<ToolSuccessResult<{ observation: ObservationSummary }>>;
}

type TextContent = { type: "text"; text: string };
type ImageContent = { type: "image"; data: string; mimeType: "image/png" };
export type PhoneControlMcpResult = {
  content: Array<TextContent | ImageContent>;
  structuredContent: Record<string, unknown>;
  isError?: boolean;
};

function parseInput<T>(schema: z.ZodType<T>, input: unknown): T {
  const parsed = schema.safeParse(input);
  if (parsed.success) {
    return parsed.data;
  }
  throw new PhoneControlError("INVALID_ACTION", "The tool input is invalid.", {
    issues: parsed.error.issues
  });
}

function observationBytes(
  service: PhoneControlToolService,
  result: { data: { observation: ObservationSummary } }
): Uint8Array | undefined {
  return service.observationStore.get(result.data.observation.observationId)?.screenshot;
}

export function toSuccessResponse(
  result: ToolSuccessResult<object>,
  screenshot?: Uint8Array
): PhoneControlMcpResult {
  const content: Array<TextContent | ImageContent> = [
    { type: "text", text: JSON.stringify(result) }
  ];
  if (screenshot) {
    content.push({
      type: "image",
      data: Buffer.from(screenshot).toString("base64"),
      mimeType: "image/png"
    });
  }
  return {
    content,
    structuredContent: result as unknown as Record<string, unknown>
  };
}

export function toErrorResponse(error: unknown): PhoneControlMcpResult {
  const normalized = asPhoneControlError(error);
  const structuredContent = toMachineError(normalized);
  return {
    isError: true,
    content: [{ type: "text", text: JSON.stringify(structuredContent) }],
    structuredContent: structuredContent as unknown as Record<string, unknown>
  };
}

async function safely<T extends object>(
  work: () => Promise<T> | T,
  screenshot?: (result: T) => Uint8Array | undefined
): Promise<PhoneControlMcpResult> {
  try {
    const result = await work();
    const success = result as ToolSuccessResult<object>;
    return toSuccessResponse(success, screenshot?.(result));
  } catch (error) {
    const normalized = asPhoneControlError(error);
    console.error(`[phone-control] ${normalized.code}: ${normalized.message}`);
    return toErrorResponse(normalized);
  }
}

export function registerPhoneControlTools(
  server: McpServer,
  service: PhoneControlToolService
): void {
  server.registerTool(
    "phone_status",
    {
      description: "Report the selected Android device and current foreground app.",
      inputSchema: {}
    },
    async () => safely(() => service.status())
  );

  server.registerTool(
    "phone_list_allowed_apps",
    {
      description: "List the server-side allowlisted Android packages.",
      inputSchema: {}
    },
    async () => safely(() => service.allowedApps())
  );

  server.registerTool(
    "phone_open_app",
    {
      description: "Launch one package from the server-side allowlist.",
      inputSchema: phoneOpenAppInputSchema.shape
    },
    async (input) => {
      return safely(
        () => {
          const parsed = parseInput(phoneOpenAppInputSchema, input);
          return service.openApp(parsed.packageName);
        },
        (result) => observationBytes(service, result as { data: { observation: ObservationSummary } })
      );
    }
  );

  server.registerTool(
    "phone_observe",
    {
      description: "Capture a fresh UI observation and native PNG screenshot.",
      inputSchema: phoneObserveInputSchema.shape
    },
    async (input) => {
      let includeScreenshot = false;
      return safely(
        () => {
          const parsed = parseInput(phoneObserveInputSchema, input);
          includeScreenshot = parsed.includeScreenshot === true;
          return service.observe();
        },
        (result) =>
          includeScreenshot
            ? observationBytes(
                service,
                result as { data: { observation: ObservationSummary } }
              )
            : undefined
      );
    }
  );

  server.registerTool(
    "phone_execute",
    {
      description: "Execute exactly one typed phone action against a current observation.",
      inputSchema: phoneExecuteInputSchema.shape
    },
    async (input) => {
      return safely(() => {
        const parsed = parseInput(phoneExecuteInputSchema, input);
        return service.execute(parsed);
      }, (result) => observationBytes(service, result as { data: { observation: ObservationSummary } }));
    }
  );

  server.registerTool(
    "phone_wait_for",
    {
      description: "Wait for a bounded foreground, visible-text, resource-ID, or UI-tree condition.",
      inputSchema: phoneWaitForInputSchema.shape
    },
    async (input) => {
      return safely(() => {
        const parsed = parseInput(phoneWaitForInputSchema, input);
        return service.waitFor(parsed.observationId, parsed.condition, {
          timeoutMs: parsed.timeoutMs
        });
      }, (result) => observationBytes(service, result as { data: { observation: ObservationSummary } }));
    }
  );
}

export function createMcpServer(
  service: PhoneControlToolService,
  serverName = "phone-control",
  version = "0.1.0"
): McpServer {
  const server = new McpServer({ name: serverName, version });
  registerPhoneControlTools(server, service);
  return server;
}
