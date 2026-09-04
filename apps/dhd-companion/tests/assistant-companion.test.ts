import { describe, expect, it } from "vitest";

import type { CompanionToolCallEvent } from "../src/companion-events.js";
import {
  CodexAppServerClient,
  handleDynamicToolCall,
} from "../src/assistant-companion.js";

describe("Codex App Server agent-message extraction", () => {
  it("uses the final answer instead of concatenating commentary from the same turn", async () => {
    const client = new CodexAppServerClient();
    const streamed: Array<{ itemId: string; text: string }> = [];
    const resultPromise = new Promise<{ text: string; threadId: string }>((resolve, reject) => {
      const testClient = client as any;
      testClient.activeThreadId = "thread-test";
      testClient.turnCompletion = {
        resolve,
        reject,
        agentMessages: new Map(),
        nextAgentMessageOrder: 0,
        phoneToolFailures: [],
        onAgentMessageDelta: (update: { itemId: string; text: string }) => streamed.push(update),
      };
    });
    const send = (message: unknown) => (client as any).handleLine(JSON.stringify(message));

    send({
      method: "item/started",
      params: { item: { id: "commentary-1", type: "agentMessage", phase: "commentary" } }
    });
    send({
      method: "item/agentMessage/delta",
      params: { itemId: "commentary-1", delta: "I’ll configure the benchmark first. " }
    });
    send({
      method: "item/completed",
      params: {
        item: {
          id: "commentary-1",
          type: "agentMessage",
          phase: "commentary",
          text: "I’ll configure the benchmark first."
        }
      }
    });
    send({
      method: "item/started",
      params: { item: { id: "final-1", type: "agentMessage", phase: "final_answer" } }
    });
    send({
      method: "item/agentMessage/delta",
      params: { itemId: "final-1", delta: "The run failed on round 4; I requested your attention." }
    });
    send({
      method: "item/completed",
      params: {
        item: {
          id: "final-1",
          type: "agentMessage",
          phase: "final_answer",
          text: "The run failed on round 4; I requested your attention."
        }
      }
    });
    send({ method: "turn/completed", params: { turn: { status: "completed" } } });

    await expect(resultPromise).resolves.toEqual({
      text: "The run failed on round 4; I requested your attention.",
      threadId: "thread-test",
      phoneToolFailures: []
    });
    expect(streamed).toEqual([
      { itemId: "final-1", text: "The run failed on round 4; I requested your attention." },
      { itemId: "final-1", text: "The run failed on round 4; I requested your attention." },
    ]);
  });

  it("retains a failed dynamic phone tool when the App Server turn completes", async () => {
    const client = new CodexAppServerClient() as any;
    const resultPromise = new Promise<any>((resolve, reject) => {
      client.activeThreadId = "thread-failure";
      client.turnCompletion = {
        resolve,
        reject,
        agentMessages: new Map(),
        nextAgentMessageOrder: 0,
        phoneToolFailures: []
      };
    });
    client.send = () => undefined;

    await client.handleServerRequest({
      id: "tool-1",
      method: "item/tool/call",
      params: { tool: "unsupported_phone_tool", arguments: {} }
    });
    client.handleLine(JSON.stringify({ method: "turn/completed", params: { turn: { status: "completed" } } }));

    await expect(resultPromise).resolves.toMatchObject({
      threadId: "thread-failure",
      phoneToolFailures: [{
        tool: "unsupported_phone_tool",
        message: "Unsupported dynamic phone tool: unsupported_phone_tool"
      }]
    });
  });

  it("emits a complete diagnostic event with normalized arguments and images", async () => {
    const events: CompanionToolCallEvent[] = [];
    const imageData = Buffer.from("test-image").toString("base64");
    const response = await handleDynamicToolCall(
      { tool: "dhd_observe", arguments: '{"expectedPackageName":"com.example.app"}' },
      {
        emit: (event) => events.push(event),
        invoke: async (_name, input) => {
          expect(input).toEqual({ expectedPackageName: "com.example.app" });
          return {
            content: [
              { type: "text", text: '{"ok":true}' },
              { type: "image", data: imageData, mimeType: "image/png" },
            ],
            structuredContent: { ok: true },
          };
        },
      },
    );

    expect(response).toEqual({
      contentItems: [
        { type: "inputText", text: '{"ok":true}' },
        { type: "inputImage", imageUrl: `data:image/png;base64,${imageData}` },
      ],
      success: true,
    });
    expect(events).toHaveLength(2);
    expect(events[0]).toMatchObject({
      type: "dhd_tool_call",
      phase: "started",
      tool: "dhd_observe",
      arguments: { expectedPackageName: "com.example.app" },
    });
    expect(events[1]).toMatchObject({
      type: "dhd_tool_call",
      phase: "completed",
      callId: events[0].callId,
      tool: "dhd_observe",
      result: {
        structuredContent: { ok: true },
        content: [
          { type: "text", text: '{"ok":true}' },
          { type: "image", data: imageData, mimeType: "image/png" },
        ],
      },
    });
  });

  it("emits raw invalid arguments and thrown tool errors without changing the error path", async () => {
    const events: CompanionToolCallEvent[] = [];
    await expect(
      handleDynamicToolCall(
        { tool: "dhd_observe", arguments: "not-json" },
        {
          emit: (event) => events.push(event),
          invoke: async (_name, input) => {
            expect(input).toEqual({ __invalidArguments: "not-json" });
            throw new Error("bridge unavailable");
          },
        },
      ),
    ).rejects.toThrow("bridge unavailable");

    expect(events).toHaveLength(2);
    expect(events[0]).toMatchObject({
      phase: "started",
      rawArguments: "not-json",
      arguments: { __invalidArguments: "not-json" },
    });
    expect(events[1]).toMatchObject({
      phase: "completed",
      callId: events[0].callId,
      error: "bridge unavailable",
    });
  });

  it("initializes once and reuses a loaded thread across turns", async () => {
    const client = new CodexAppServerClient();
    const internals = client as any;
    const requests: string[] = [];
    const turnInputs: unknown[] = [];
    const timingLogs: string[] = [];
    const originalError = console.error;
    console.error = (...args: unknown[]) => {
      const line = args.map(String).join(" ");
      if (line.includes("[dhd-timing]")) timingLogs.push(line);
    };

    internals.startProcess = () => {
      internals.child = { pid: 1234, stdin: { destroyed: false } };
    };
    internals.notify = () => undefined;
    let threadStarts = 0;
    const turnStartParams: Array<Record<string, unknown>> = [];
    internals.request = async (method: string, params?: Record<string, unknown>) => {
      requests.push(method);
      if (method === "initialize") return { result: {} };
      if (method === "thread/unsubscribe") return { result: {} };
      if (method === "thread/start") {
        threadStarts += 1;
        return { result: { thread: { id: threadStarts === 1 ? "thread-loaded" : "thread-new" } } };
      }
      if (method === "turn/start") {
        turnStartParams.push(params ?? {});
        turnInputs.push(params?.input);
        queueMicrotask(() => {
          internals.handleLine(JSON.stringify({
            method: "turn/started",
            params: { turn: { id: `turn-${requests.filter((entry) => entry === "turn/start").length}` } }
          }));
          internals.handleLine(JSON.stringify({
            method: "item/started",
            params: { item: { id: "user-message", type: "userMessage" } }
          }));
          internals.handleLine(JSON.stringify({
            method: "turn/completed",
            params: { turn: { status: "completed" } }
          }));
        });
        return { result: { turn: { id: "turn-response" } } };
      }
      throw new Error(`Unexpected App Server request in test: ${method}`);
    };

    try {
      await expect(client.runTurn("hi")).resolves.toMatchObject({
        threadId: "thread-loaded"
      });
      await expect(client.runTurn("second request", "thread-loaded")).resolves.toMatchObject({
        threadId: "thread-loaded"
      });
      await expect(client.runTurn("rotated request")).resolves.toMatchObject({
        threadId: "thread-new"
      });
    } finally {
      console.error = originalError;
    }

    expect(requests.filter((method) => method === "initialize")).toHaveLength(1);
    expect(requests.filter((method) => method === "thread/start")).toHaveLength(2);
    expect(requests.filter((method) => method === "thread/resume")).toHaveLength(0);
    expect(requests.filter((method) => method === "thread/unsubscribe")).toHaveLength(1);
    expect(requests.filter((method) => method === "turn/start")).toHaveLength(3);
    expect(turnInputs).toEqual([
      [{ type: "text", text: "hi" }],
      [{ type: "text", text: "second request" }],
      [{ type: "text", text: "rotated request" }]
    ]);
    expect(turnStartParams.map((params) => params.serviceTier)).toEqual([
      "default",
      "default",
      "default"
    ]);
    expect(timingLogs.some((line) => line.includes("phase=turn/started"))).toBe(true);
    expect(timingLogs.some((line) => line.includes("phase=userMessage"))).toBe(true);
  });
});

describe("Codex App Server turn steering", () => {
  it("sends steer input to the active turn and preserves its expected turn id", async () => {
    const client = new CodexAppServerClient() as any;
    client.activeThreadId = "thread-steer";
    client.activeTurnId = "turn-steer";
    client.turnCompletion = {
      resolve: () => undefined,
      reject: () => undefined,
      agentMessages: new Map(),
      nextAgentMessageOrder: 0
    };
    const requests: Array<{ method: string; params: Record<string, unknown> }> = [];
    client.request = async (method: string, params: Record<string, unknown>) => {
      requests.push({ method, params });
      return { result: { turnId: "turn-steer" } };
    };

    await client.steer("Actually stop after verifying the current screen.");

    expect(requests).toEqual([{
      method: "turn/steer",
      params: {
        threadId: "thread-steer",
        input: [{ type: "text", text: "Actually stop after verifying the current screen." }],
        expectedTurnId: "turn-steer"
      }
    }]);
  });

  it("rejects steering when the App Server turn is no longer active", async () => {
    const client = new CodexAppServerClient();

    await expect(client.steer("Continue")).rejects.toThrow("no active turn");
  });
});
