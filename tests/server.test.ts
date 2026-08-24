import { describe, expect, it } from "vitest";

import { PhoneControlError } from "../src/errors.js";
import { ObservationStore } from "../src/observation-store.js";
import {
  createMcpServer,
  phoneActionSchema,
  phoneExecuteInputSchema,
  phoneExecuteSequenceInputSchema,
  phoneSequenceActionSchema,
  phoneWaitForInputSchema,
  registerPhoneControlTools,
  toErrorResponse,
  toSuccessResponse,
  type PhoneControlToolService,
  PHONE_CONTROL_TOOL_NAMES
} from "../src/server.js";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

describe("MCP boundary schemas and result conversion", () => {
  it("accepts exactly one approved discriminated action shape", () => {
    expect(
      phoneActionSchema.parse({
        type: "scroll",
        direction: "up",
        amount: "small"
      })
    ).toEqual({ type: "scroll", direction: "up", amount: "small" });

    expect(
      phoneActionSchema.safeParse({
        type: "scroll",
        direction: "up",
        amount: "small",
        distance: 100
      }).success
    ).toBe(false);
    expect(
      phoneExecuteInputSchema.safeParse({
        observationId: "obs_1",
        action: { type: "click", elementRef: "el_1" },
        type: "keypress"
      }).success
    ).toBe(false);
  });

  it("bounds sequences and requires semantic targets instead of coordinate macros", () => {
    expect(
      phoneSequenceActionSchema.parse({
        type: "click",
        target: {
          resourceId: "com.example:id/apply",
          text: "Apply"
        }
      })
    ).toEqual({
      type: "click",
      target: {
        resourceId: "com.example:id/apply",
        text: "Apply"
      }
    });
    expect(
      phoneExecuteSequenceInputSchema.safeParse({
        observationId: "obs_1",
        actions: [{ type: "click_coordinate", x: 10, y: 20 }]
      }).success
    ).toBe(false);
    expect(
      phoneExecuteSequenceInputSchema.safeParse({
        observationId: "obs_1",
        actions: [{ type: "click", target: {} }]
      }).success
    ).toBe(false);
    expect(
      phoneExecuteSequenceInputSchema.safeParse({
        observationId: "obs_1",
        actions: Array.from({ length: 33 }, () => ({
          type: "keypress",
          key: "ENTER"
        }))
      }).success
    ).toBe(false);
    expect(
      phoneExecuteSequenceInputSchema.parse({
        observationId: "obs_1",
        executionMode: "stable_surface",
        actions: [{ type: "click", elementRef: "el_1" }]
      }).executionMode
    ).toBe("stable_surface");
    expect(
      phoneExecuteSequenceInputSchema.safeParse({
        observationId: "obs_1",
        executionMode: "blind",
        actions: [{ type: "click", elementRef: "el_1" }]
      }).success
    ).toBe(false);
  });

  it("keeps the wait baseline outside the condition", () => {
    expect(
      phoneWaitForInputSchema.parse({
        observationId: "obs_1",
        condition: { type: "ui_tree_changed" },
        timeoutMs: 250
      })
    ).toMatchObject({ observationId: "obs_1" });
    expect(
      phoneWaitForInputSchema.safeParse({
        observationId: "obs_1",
        condition: { type: "ui_tree_changed", observationId: "obs_2" }
      }).success
    ).toBe(false);
  });

  it("returns structured success, image, and stable error content", () => {
    const success = toSuccessResponse(
      { ok: true, data: { value: 3 } },
      Uint8Array.from([1, 2, 3])
    );
    expect(success.structuredContent).toEqual({ ok: true, data: { value: 3 } });
    expect(success.content[0]).toEqual({
      type: "text",
      text: JSON.stringify({ ok: true, data: { value: 3 } })
    });
    expect(success.content[1]).toMatchObject({
      type: "image",
      mimeType: "image/png",
      data: "AQID"
    });

    const error = toErrorResponse(
      new PhoneControlError("FORBIDDEN_APP", "denied", { packageName: "x" })
    );
    expect(error.isError).toBe(true);
    expect(error.structuredContent).toMatchObject({
      ok: false,
      error: { code: "FORBIDDEN_APP" }
    });
    expect(error.content[0]).toMatchObject({ type: "text" });
  });

  it("registers exactly the eight public tools", () => {
    const registered: string[] = [];
    const fakeServer = {
      registerTool(name: string): void {
        registered.push(name);
      }
    } as unknown as McpServer;
    const fakeService = {
      observationStore: new ObservationStore()
    } as PhoneControlToolService;

    registerPhoneControlTools(fakeServer, fakeService);
    expect(registered).toEqual([...PHONE_CONTROL_TOOL_NAMES]);
    expect(registered).toContain("phone_close_app");
    expect(registered).toContain("phone_execute_sequence");
    expect(createMcpServer).toBeTypeOf("function");
  });

  it("defaults to returning screenshot when elements are empty in phone_observe", async () => {
    const tools = new Map<string, (input: unknown) => Promise<any>>();
    const fakeServer = {
      registerTool(name: string, _schema: unknown, handler: (input: unknown) => Promise<any>): void {
        tools.set(name, handler);
      }
    } as unknown as McpServer;

    const store = new ObservationStore();
    const capture = {
      serial: "RFCW40B3G7X",
      packageName: "com.spotify.music",
      activity: "com.spotify.music/.MainActivity",
      display: { width: 1080, height: 2340 },
      rotation: 0,
      uiHash: "hash123",
      screenshotDimensions: { width: 1080, height: 2340 },
      observedAt: Date.now(),
      elements: [],
      screenshot: Uint8Array.from([10, 20, 30])
    };
    const obs = store.create(capture);

    const fakeService = {
      observationStore: store,
      observe: async () => ({
        ok: true,
        data: { observation: store.summary(obs) }
      })
    } as unknown as PhoneControlToolService;

    registerPhoneControlTools(fakeServer, fakeService);
    const observeHandler = tools.get("phone_observe")!;

    // Call without includeScreenshot
    const response = await observeHandler({});
    expect(response.content).toHaveLength(2);
    expect(response.content[1]).toMatchObject({
      type: "image",
      mimeType: "image/png"
    });
  });

  it("routes a bounded sequence and can return its final observation screenshot", async () => {
    const tools = new Map<string, (input: unknown) => Promise<any>>();
    const fakeServer = {
      registerTool(
        name: string,
        _schema: unknown,
        handler: (input: unknown) => Promise<any>
      ): void {
        tools.set(name, handler);
      }
    } as unknown as McpServer;
    const store = new ObservationStore();
    const capture = {
      serial: "RFCW40B3G7X",
      packageName: "com.spotify.music",
      activity: "com.spotify.music/.MainActivity",
      display: { width: 1080, height: 2340 },
      rotation: 0,
      uiHash: "sequence-hash",
      screenshotDimensions: { width: 2, height: 3 },
      observedAt: Date.now(),
      elements: [],
      screenshot: Uint8Array.from([10, 20, 30])
    };
    const observation = store.create(capture);
    let request: unknown;
    const fakeService = {
      observationStore: store,
      executeSequence: async (value: unknown) => {
        request = value;
        return {
          ok: true,
          data: {
            completed: true,
            requestedSteps: 1,
            completedSteps: 1,
            steps: [
              {
                index: 0,
                status: "success",
                action: "click",
                observation: store.summary(observation)
              }
            ],
            finalObservation: store.summary(observation)
          }
        };
      }
    } as unknown as PhoneControlToolService;

    registerPhoneControlTools(fakeServer, fakeService);
    const response = await tools.get("phone_execute_sequence")!({
      observationId: "obs_input",
      actions: [{ type: "keypress", key: "ENTER" }],
      includeScreenshot: true
    });

    expect(request).toEqual({
      observationId: "obs_input",
      actions: [{ type: "keypress", key: "ENTER" }]
    });
    expect(response.content).toHaveLength(2);
    expect(response.content[1]).toMatchObject({
      type: "image",
      mimeType: "image/png"
    });
  });
});
