import { Buffer } from "node:buffer";

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";

import {
  ObservationStore,
  PhoneControlError,
  asPhoneControlError,
  toMachineError,
  MAX_SEQUENCE_ACTIONS
} from "@dhd/phone-control";
import type {
  ActionData,
  ActiveAppsData,
  AllowedAppsData,
  CloseAppData,
  ObservationSummary,
  PhoneExecuteRequest,
  PhoneExecuteSequenceRequest,
  PhoneSequenceAction,
  PhoneStatusData,
  ObservationMode,
  SequenceExecutionMode,
  SequenceData,
  ToolSuccessResult,
  WaitCondition
} from "@dhd/phone-control";
import {
  SCREENSHOT_MARKER_GUIDANCE,
  ScreenshotMarkerPresenter,
  annotateScreenshotPng,
  cropScreenshotPng,
  type ScreenshotMarker,
  type ScreenshotMarkerObservation,
  type ScreenshotMarkerPoint,
  type ScreenshotEvidenceMetadata
} from "@dhd/screenshot-markers";

export const PHONE_CONTROL_TOOL_NAMES = [
  "phone_status",
  "phone_list_allowed_apps",
  "phone_list_active_apps",
  "phone_open_app",
  "phone_close_app",
  "phone_observe_app",
  "phone_execute",
  "phone_execute_sequence",
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
      amount: z.enum(["small", "medium", "large"]),
      elementRef: z.string().min(1).optional()
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

const elementTargetSchema = z
  .object({
    text: z.string().min(1).max(4096).optional(),
    contentDescription: z.string().min(1).max(4096).optional(),
    resourceId: z.string().min(1).max(255).optional(),
    class: z.string().min(1).max(255).optional()
  })
  .strict()
  .refine(
    (target) => Object.values(target).some((value) => value !== undefined),
    { message: "A semantic element target must specify at least one field." }
  );

/**
 * Sequence actions intentionally omit click_coordinate. A sequence may use
 * an observation-bound elementRef or a semantic target, but never a list of
 * blind coordinates.
 */
export const phoneSequenceActionSchema: z.ZodType<PhoneSequenceAction> = z.union([
  z
    .object({
      type: z.literal("click"),
      elementRef: z.string().min(1)
    })
    .strict(),
  z
    .object({
      type: z.literal("click"),
      target: elementTargetSchema
    })
    .strict(),
  z
    .object({
      type: z.literal("scroll"),
      direction: z.enum(["up", "down", "left", "right"]),
      amount: z.enum(["small", "medium", "large"]),
      elementRef: z.string().min(1).optional()
    })
    .strict(),
  z
    .object({
      type: z.literal("scroll"),
      direction: z.enum(["up", "down", "left", "right"]),
      amount: z.enum(["small", "medium", "large"]),
      target: elementTargetSchema
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

export const phoneExecuteSequenceInputSchema = z
  .object({
    observationId: z.string().min(1),
    actions: z.array(phoneSequenceActionSchema).min(1).max(MAX_SEQUENCE_ACTIONS),
    executionMode: z.enum(["validated", "stable_surface"]).optional(),
    includeScreenshot: z.boolean().optional()
  })
  .strict();

export const phoneObserveInputSchema = z
  .object({
    displayId: z.number().int().min(0).optional(),
    packageName: packageNameSchema.optional(),
    mode: z.enum(["semantic", "visual"]).optional(),
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
  .object({
    packageName: packageNameSchema,
    useVirtualDisplay: z.boolean().optional(),
    newInstance: z.boolean().optional(),
    mode: z.enum(["semantic", "visual"]).optional()
  })
  .strict();

export const phoneCloseAppInputSchema = z
  .object({
    packageName: packageNameSchema.optional(),
    displayId: z.number().int().min(0).optional()
  })
  .strict();

export interface PhoneControlToolService {
  readonly observationStore: ObservationStore;
  status(): Promise<ToolSuccessResult<PhoneStatusData>>;
  allowedApps(): ToolSuccessResult<AllowedAppsData>;
  listActiveApps(): ToolSuccessResult<ActiveAppsData>;
  openApp(
    packageName: string,
    options?: {
      useVirtualDisplay?: boolean;
      newInstance?: boolean;
      mode?: ObservationMode;
    }
  ): Promise<ToolSuccessResult<{ observation: ObservationSummary }>>;
  closeApp(target: {
    packageName?: string;
    displayId?: number;
  }): Promise<ToolSuccessResult<CloseAppData>>;
  observe(options?: {
    displayId?: number;
    packageName?: string;
    mode?: ObservationMode;
  }): Promise<ToolSuccessResult<{ observation: ObservationSummary }>>;
  execute(request: PhoneExecuteRequest): Promise<ToolSuccessResult<ActionData>>;
  executeSequence(
    request: PhoneExecuteSequenceRequest
  ): Promise<ToolSuccessResult<SequenceData>>;
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

type BeforeTapCapture = {
  screenshot: Uint8Array;
  observation: ScreenshotMarkerObservation;
  point: ScreenshotMarkerPoint;
};

type ScreenshotAttachment = {
  bytes: Uint8Array;
  marker?: ScreenshotMarker;
  beforeTapEvidence?: {
    bytes: Uint8Array;
    metadata: ScreenshotEvidenceMetadata;
  };
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
  screenshot?: Uint8Array,
  screenshotMarker?: ScreenshotMarker,
  beforeTapEvidence?: ScreenshotAttachment["beforeTapEvidence"]
): PhoneControlMcpResult {
  const response = screenshotMarker || beforeTapEvidence
    ? {
        ...result,
        ...(screenshotMarker ? { screenshotMarker } : {}),
        ...(beforeTapEvidence ? { screenshotEvidence: beforeTapEvidence.metadata } : {})
      }
    : result;
  const content: Array<TextContent | ImageContent> = [
    { type: "text", text: JSON.stringify(response) }
  ];
  if (beforeTapEvidence) {
    content.push({
      type: "image",
      data: Buffer.from(beforeTapEvidence.bytes).toString("base64"),
      mimeType: "image/png"
    });
  }
  if (screenshot) {
    content.push({
      type: "image",
      data: Buffer.from(screenshot).toString("base64"),
      mimeType: "image/png"
    });
  }
  return {
    content,
    structuredContent: response as unknown as Record<string, unknown>
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
  screenshot?: (result: T) => Uint8Array | ScreenshotAttachment | undefined
): Promise<PhoneControlMcpResult> {
  try {
    const result = await work();
    const success = result as ToolSuccessResult<object>;
    const attachment = screenshot?.(result);
    if (attachment instanceof Uint8Array) {
      return toSuccessResponse(success, attachment);
    }
    return toSuccessResponse(
      success,
      attachment?.bytes,
      attachment?.marker,
      attachment?.beforeTapEvidence
    );
  } catch (error) {
    const normalized = asPhoneControlError(error);
    console.error(`[phone-control] ${normalized.code}: ${normalized.message}`);
    return toErrorResponse(normalized);
  }
}

function resolveScreenshot(
  service: PhoneControlToolService,
  result: unknown,
  markerPresenter: ScreenshotMarkerPresenter,
  includeScreenshot = false,
  lastTap?: ScreenshotMarkerPoint,
  beforeTap?: BeforeTapCapture
): Uint8Array | ScreenshotAttachment | undefined {
  const data = (result as {
    data?: {
      observation?: ObservationSummary;
      finalObservation?: ObservationSummary;
    };
  })?.data;
  const summary = data?.observation ?? data?.finalObservation;
  if (!summary) return undefined;
  if (includeScreenshot || summary.mode === "visual") {
    const screenshot = service.observationStore.get(summary.observationId)?.screenshot;
    if (!screenshot) return undefined;
    const markerObservation = markerObservationFromSummary(summary);
    try {
      const rendered = markerPresenter.render(screenshot, markerObservation, {
        ...(lastTap ? { lastTap } : {})
      });
      if (
        !beforeTap ||
        !lastTap ||
        beforeTap.point.x !== lastTap.x ||
        beforeTap.point.y !== lastTap.y
      ) {
        return { bytes: rendered.screenshot, marker: rendered.marker };
      }

      try {
        const beforeAnnotated = annotateScreenshotPng(
          beforeTap.screenshot,
          beforeTap.observation.screenshotDimensions,
          {
            kind: "last_tap",
            ...lastTap,
            coordinateSpace: "display"
          }
        );
        const crop = cropScreenshotPng(
          beforeAnnotated,
          beforeTap.observation.screenshotDimensions,
          lastTap
        );
        return {
          bytes: rendered.screenshot,
          marker: rendered.marker,
          beforeTapEvidence: {
            bytes: crop.screenshot,
            metadata: {
              kind: "before_tap_crop",
              sourceObservationId: beforeTap.observation.observationId,
              tap: lastTap,
              coordinateSpace: "display",
              crop: crop.bounds
            }
          }
        };
      } catch (error) {
        console.error(
          `[phone-control] before-tap evidence render failed: ${
            error instanceof Error ? error.message : String(error)
          }`
        );
        return { bytes: rendered.screenshot, marker: rendered.marker };
      }
    } catch (error) {
      console.error(
        `[phone-control] screenshot marker render failed: ${
          error instanceof Error ? error.message : String(error)
        }`
      );
      return { bytes: screenshot };
    }
  }
  return undefined;
}

function markerObservationFromSummary(summary: ObservationSummary): ScreenshotMarkerObservation {
  return {
    observationId: summary.observationId,
    displayId: summary.displayId,
    packageName: summary.packageName,
    rotation: summary.rotation,
    screenshotDimensions: {
      width: summary.screenshot.width,
      height: summary.screenshot.height
    }
  };
}

function clickPointFromResult(result: unknown): ScreenshotMarkerPoint | undefined {
  const pointerEvent = (result as {
    data?: { pointerEvent?: { action?: string; x?: number; y?: number } };
  })?.data?.pointerEvent;
  const x = pointerEvent?.x;
  const y = pointerEvent?.y;
  if (pointerEvent?.action !== "click" || !Number.isInteger(x) || !Number.isInteger(y)) {
    return undefined;
  }
  return { x: x as number, y: y as number };
}

function lastSequenceClick(result: unknown): ScreenshotMarkerPoint | undefined {
  const steps = (result as {
    data?: {
      steps?: Array<{
        status?: string;
        pointerEvent?: { action?: string; x?: number; y?: number };
      }>;
    };
  })?.data?.steps;
  if (!Array.isArray(steps)) return undefined;
  for (let index = steps.length - 1; index >= 0; index -= 1) {
    const step = steps[index];
    if (step.status !== "success" || step.pointerEvent?.action !== "click") continue;
    const x = step.pointerEvent.x;
    const y = step.pointerEvent.y;
    if (Number.isInteger(x) && Number.isInteger(y)) {
      return { x: x as number, y: y as number };
    }
  }
  return undefined;
}

export function registerPhoneControlTools(
  server: McpServer,
  service: PhoneControlToolService
): void {
  const markerPresenter = new ScreenshotMarkerPresenter();

  server.registerTool(
    "phone_status",
    {
      description: "Report the selected Android device connection, authorization state, and physical screen foreground status.",
      inputSchema: {}
    },
    async () => safely(() => service.status())
  );

  server.registerTool(
    "phone_list_allowed_apps",
    {
      description:
        "Show the server-side Android app access policy, including whether full app access is enabled.",
      inputSchema: {}
    },
    async () => safely(() => service.allowedApps())
  );

  server.registerTool(
    "phone_list_active_apps",
    {
      description: "List all currently running app sessions, their virtual display IDs, and dimensions.",
      inputSchema: {}
    },
    async () => safely(() => service.listActiveApps())
  );

  server.registerTool(
    "phone_open_app",
    {
      description:
        `Launch a policy-permitted package in an isolated virtual display. This is the required first step to interact with an app; it returns an initial observation with observationId. Reuses an existing session for the package by default; set newInstance true to request another display. Use mode 'visual' for a screenshot-only observation/action loop; semantic remains the default on display 0 and is unavailable on secondary displays until a display-scoped UI adapter exists. ${SCREENSHOT_MARKER_GUIDANCE}`,
      inputSchema: phoneOpenAppInputSchema.shape
    },
    async (input) => {
      return safely(
        () => {
          const parsed = parseInput(phoneOpenAppInputSchema, input);
          return service.openApp(parsed.packageName, {
            useVirtualDisplay: parsed.useVirtualDisplay,
            newInstance: parsed.newInstance,
            ...(parsed.mode ? { mode: parsed.mode as ObservationMode } : {})
          });
        },
        (result) => resolveScreenshot(service, result, markerPresenter)
      );
    }
  );

  server.registerTool(
    "phone_close_app",
    {
      description: "Close an active virtual display session or running app.",
      inputSchema: phoneCloseAppInputSchema.shape
    },
    async (input) => {
      return safely(async () => {
        const parsed = parseInput(phoneCloseAppInputSchema, input);
        const result = await service.closeApp(parsed);
        if (result.data.closed) {
          if (result.data.displayId !== undefined) {
            markerPresenter.reset(result.data.displayId);
          } else if (parsed.displayId !== undefined) {
            markerPresenter.reset(parsed.displayId);
          } else {
            markerPresenter.reset();
          }
        }
        return result;
      });
    }
  );

  server.registerTool(
    "phone_observe_app",
    {
      description:
        `Capture a fresh observation and native PNG screenshot from an active app session or display. Use mode 'visual' for screenshot-only capture (no shell UI Automator call); its screenshot fingerprint is checked before coordinate actions and a fresh screenshot observation is returned after every action. Pass displayId explicitly when multiple virtual display sessions are active; visual coordinates require exact screenshot/display dimensions with no implicit transform. Semantic is the default on display 0 and is unavailable on secondary displays until a display-scoped UI adapter exists. Do not use this to launch apps; call phone_open_app first. ${SCREENSHOT_MARKER_GUIDANCE}`,
      inputSchema: phoneObserveInputSchema.shape
    },
    async (input) => {
      let includeScreenshot = false;
      return safely(
        () => {
          const parsed = parseInput(phoneObserveInputSchema, input);
          includeScreenshot = parsed.includeScreenshot === true;
          return service.observe({
            displayId: parsed.displayId,
            packageName: parsed.packageName,
            ...(parsed.mode ? { mode: parsed.mode as ObservationMode } : {})
          });
        },
        (result) => resolveScreenshot(service, result, markerPresenter, includeScreenshot)
      );
    }
  );

  server.registerTool(
    "phone_execute",
    {
      description: `Execute exactly one typed phone action against a current observation. ${SCREENSHOT_MARKER_GUIDANCE}`,
      inputSchema: phoneExecuteInputSchema.shape
    },
    async (input) => {
      let beforeTap: BeforeTapCapture | undefined;
      return safely(
        () => {
          const parsed = parseInput(phoneExecuteInputSchema, input);
          if (parsed.action.type === "click_coordinate") {
            const reference = service.observationStore.get(parsed.observationId);
            if (reference) {
              beforeTap = {
                screenshot: Uint8Array.from(reference.screenshot),
                observation: markerObservationFromSummary(
                  service.observationStore.summary(reference)
                ),
                point: { x: parsed.action.x, y: parsed.action.y }
              };
            }
          }
          return service.execute(parsed);
        },
        (result) => resolveScreenshot(
          service,
          result,
          markerPresenter,
          false,
          clickPointFromResult(result),
          beforeTap
        )
      );
    }
  );

  server.registerTool(
    "phone_execute_sequence",
    {
      description:
        `Execute up to ${MAX_SEQUENCE_ACTIONS} typed semantic actions in one MCP call. Choose executionMode for each workflow: use stable_surface whenever every action is a click uniquely resolvable from one surface and earlier clicks cannot change the position or meaning of later targets; examples such as keypads and button grids are illustrative, not exhaustive. Use validated when later actions depend on intermediate UI changes or when uncertain. The default validated mode reuses each authorized post-action UI capture for the next immediate step, rechecks foreground, rematches targets, and captures one final screenshot. stable_surface dispatches a bounded typed tap batch and performs one final observation. Coordinates and state-dependent actions are never accepted in stable_surface mode. ${SCREENSHOT_MARKER_GUIDANCE}`,
      inputSchema: phoneExecuteSequenceInputSchema.shape
    },
    async (input) => {
      let includeScreenshot = false;
      return safely(
        () => {
          const parsed = parseInput(phoneExecuteSequenceInputSchema, input);
          includeScreenshot = parsed.includeScreenshot === true;
          const request: PhoneExecuteSequenceRequest = {
            observationId: parsed.observationId,
            actions: parsed.actions,
            ...(parsed.executionMode
              ? { executionMode: parsed.executionMode as SequenceExecutionMode }
              : {})
          };
          return service.executeSequence(request);
        },
        (result) => resolveScreenshot(
          service,
          result,
          markerPresenter,
          includeScreenshot,
          lastSequenceClick(result)
        )
      );
    }
  );

  server.registerTool(
    "phone_wait_for",
    {
      description: "Wait for a bounded foreground, visible-text, resource-ID, or UI-tree condition.",
      inputSchema: phoneWaitForInputSchema.shape
    },
    async (input) => {
      return safely(
        () => {
          const parsed = parseInput(phoneWaitForInputSchema, input);
          return service.waitFor(parsed.observationId, parsed.condition, {
            timeoutMs: parsed.timeoutMs
          });
        },
        (result) => resolveScreenshot(service, result, markerPresenter)
      );
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
