import { Buffer } from "node:buffer";

import { describe, expect, it } from "vitest";

import { PhoneControlError } from "../src/errors.js";
import { ObservationStore } from "../src/observation-store.js";
import {
  createMcpServer,
  phoneActionSchema,
  phoneExecuteInputSchema,
  phoneExecuteSequenceInputSchema,
  phoneSequenceActionSchema,
  phoneOpenAppInputSchema,
  phoneObserveInputSchema,
  phoneWaitForInputSchema,
  registerPhoneControlTools,
  toErrorResponse,
  toSuccessResponse,
  type PhoneControlToolService,
  PHONE_CONTROL_TOOL_NAMES
} from "../src/server.js";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

describe("MCP boundary schemas and result conversion", () => {
  it("accepts only the optional newInstance field for phone_open_app", () => {
    expect(
      phoneOpenAppInputSchema.parse({
        packageName: "com.example.app",
        newInstance: true
      })
    ).toEqual({
      packageName: "com.example.app",
      newInstance: true
    });
    expect(
      phoneOpenAppInputSchema.safeParse({
        packageName: "com.example.app",
        instanceId: "second"
      }).success
    ).toBe(false);
    expect(
      phoneOpenAppInputSchema.safeParse({
        packageName: "com.example.app",
        newInstance: "true"
      }).success
    ).toBe(false);
    expect(
      phoneOpenAppInputSchema.parse({
        packageName: "com.example.app",
        mode: "visual"
      }).mode
    ).toBe("visual");
  });

  it("exposes an explicit semantic or screenshot-only observation mode", () => {
    expect(
      phoneObserveInputSchema.parse({ mode: "visual" }).mode
    ).toBe("visual");
    expect(
      phoneObserveInputSchema.parse({ mode: "semantic" }).mode
    ).toBe("semantic");
    expect(
      phoneObserveInputSchema.safeParse({ mode: "screenshot" }).success
    ).toBe(false);
  });

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

  it("registers exactly the nine public tools including phone_list_active_apps", () => {
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
    expect(registered).toContain("phone_list_active_apps");
    expect(registered).toContain("phone_observe_app");
    expect(registered).toContain("phone_execute_sequence");
    expect(createMcpServer).toBeTypeOf("function");
  });

  it("routes phone_list_active_apps handler to service.listActiveApps", async () => {
    const tools = new Map<string, (input: unknown) => Promise<any>>();
    const fakeServer = {
      registerTool(name: string, _schema: unknown, handler: (input: unknown) => Promise<any>): void {
        tools.set(name, handler);
      }
    } as unknown as McpServer;

    let called = false;
    const fakeService = {
      observationStore: new ObservationStore(),
      listActiveApps: () => {
        called = true;
        return {
          ok: true,
          data: {
            activeSessions: [
              {
                displayId: 2,
                packageName: "org.telegram.messenger",
                activity: null,
                width: 1080,
                height: 2340,
                startedAt: 12345
              }
            ],
            count: 1
          }
        };
      }
    } as unknown as PhoneControlToolService;

    registerPhoneControlTools(fakeServer, fakeService);
    const handler = tools.get("phone_list_active_apps")!;
    const result = await handler({});
    expect(called).toBe(true);
    expect(result.structuredContent).toMatchObject({
      ok: true,
      data: { count: 1 }
    });
  });

  it("returns the actual screenshot for an explicit visual observation", async () => {
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
      mode: "visual" as const,
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
    const observeHandler = tools.get("phone_observe_app")!;

    // Call without includeScreenshot
    const response = await observeHandler({});
    expect(response.content).toHaveLength(2);
    expect(response.content[1]).toMatchObject({
      type: "image",
      mimeType: "image/png"
    });
    expect(response.content[1]).toMatchObject({
      data: Buffer.from([10, 20, 30]).toString("base64")
    });
  });

  it("does not infer a screenshot from an empty semantic element list", async () => {
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
    const observation = store.create({
      serial: "RFCW40B3G7X",
      packageName: "com.spotify.music",
      activity: "com.spotify.music/.MainActivity",
      display: { width: 1080, height: 2340 },
      rotation: 0,
      mode: "semantic",
      uiHash: "hash123",
      screenshotDimensions: { width: 1080, height: 2340 },
      observedAt: Date.now(),
      elements: [],
      screenshot: Uint8Array.from([10, 20, 30])
    });
    const fakeService = {
      observationStore: store,
      observe: async () => ({
        ok: true,
        data: { observation: store.summary(observation) }
      })
    } as unknown as PhoneControlToolService;

    registerPhoneControlTools(fakeServer, fakeService);
    const response = await tools.get("phone_observe_app")!({});

    expect(response.content).toHaveLength(1);
    expect(response.content[0]).toMatchObject({ type: "text" });
  });

  it("returns the fresh visual post-action screenshot from phone_execute", async () => {
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
    const initial = store.create({
      serial: "RFCW40B3G7X",
      packageName: "com.spotify.music",
      activity: "com.spotify.music/.MainActivity",
      display: { width: 1080, height: 2340 },
      rotation: 0,
      mode: "visual",
      screenshotDimensions: { width: 1080, height: 2340 },
      observedAt: Date.now(),
      elements: [],
      screenshot: Uint8Array.from([10, 20, 30])
    });
    const fresh = store.create({
      serial: "RFCW40B3G7X",
      packageName: "com.spotify.music",
      activity: "com.spotify.music/.MainActivity",
      display: { width: 1080, height: 2340 },
      rotation: 0,
      mode: "visual",
      screenshotDimensions: { width: 1080, height: 2340 },
      observedAt: Date.now() + 1,
      elements: [],
      screenshot: Uint8Array.from([40, 50, 60])
    });
    let request: unknown;
    const fakeService = {
      observationStore: store,
      execute: async (value: unknown) => {
        request = value;
        return {
          ok: true,
          data: {
            action: "click_coordinate",
            observation: store.summary(fresh)
          }
        };
      }
    } as unknown as PhoneControlToolService;

    registerPhoneControlTools(fakeServer, fakeService);
    const response = await tools.get("phone_execute")!({
      observationId: initial.observationId,
      action: { type: "click_coordinate", x: 60, y: 50 }
    });

    expect(request).toEqual({
      observationId: initial.observationId,
      action: { type: "click_coordinate", x: 60, y: 50 }
    });
    expect(response.content).toHaveLength(2);
    expect(response.content[1]).toEqual({
      type: "image",
      data: Buffer.from(fresh.screenshot).toString("base64"),
      mimeType: "image/png"
    });
    const structured = response.structuredContent as {
      data: { observation: { observationId: string; mode: string } };
    };
    expect(structured.data.observation).toMatchObject({
      observationId: fresh.observationId,
      mode: "visual"
    });
    expect(structured.data.observation.observationId).not.toBe(
      initial.observationId
    );
  });

  it("does not implicitly attach a screenshot to semantic phone_execute", async () => {
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
    const observation = store.create({
      serial: "RFCW40B3G7X",
      packageName: "com.spotify.music",
      activity: "com.spotify.music/.MainActivity",
      display: { width: 1080, height: 2340 },
      rotation: 0,
      mode: "semantic",
      uiHash: "semantic-hash",
      screenshotDimensions: { width: 1080, height: 2340 },
      observedAt: Date.now(),
      elements: [],
      screenshot: Uint8Array.from([70, 80, 90])
    });
    const fakeService = {
      observationStore: store,
      execute: async () => ({
        ok: true,
        data: {
          action: "keypress",
          observation: store.summary(observation)
        }
      })
    } as unknown as PhoneControlToolService;

    registerPhoneControlTools(fakeServer, fakeService);
    const response = await tools.get("phone_execute")!({
      observationId: observation.observationId,
      action: { type: "keypress", key: "ENTER" }
    });

    expect(response.content).toHaveLength(1);
    expect(response.content[0]).toMatchObject({ type: "text" });
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

  it("passes newInstance through the phone_open_app handler", async () => {
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
    const calls: Array<{ packageName: string; options: unknown }> = [];
    const fakeService = {
      observationStore: new ObservationStore(),
      openApp: async (packageName: string, options: unknown) => {
        calls.push({ packageName, options });
        return {
          ok: true,
          data: { observation: { observationId: "obs_1", elements: [] } }
        };
      }
    } as unknown as PhoneControlToolService;

    registerPhoneControlTools(fakeServer, fakeService);
    const response = await tools.get("phone_open_app")!({
      packageName: "com.example.app",
      newInstance: true
    });

    expect(response.isError).not.toBe(true);
    expect(calls).toEqual([
      {
        packageName: "com.example.app",
        options: { useVirtualDisplay: undefined, newInstance: true }
      }
    ]);
  });
});
