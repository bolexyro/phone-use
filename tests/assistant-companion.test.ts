import { describe, expect, it } from "vitest";

import { CodexAppServerClient } from "../src/assistant-companion.js";

describe("Codex App Server agent-message extraction", () => {
  it("uses the final answer instead of concatenating commentary from the same turn", async () => {
    const client = new CodexAppServerClient();
    const resultPromise = new Promise<{ text: string; threadId: string }>((resolve, reject) => {
      const testClient = client as any;
      testClient.activeThreadId = "thread-test";
      testClient.turnCompletion = {
        resolve,
        reject,
        agentMessages: new Map(),
        nextAgentMessageOrder: 0
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
      threadId: "thread-test"
    });
  });

  it("initializes once and reuses a loaded thread across turns", async () => {
    const client = new CodexAppServerClient();
    const internals = client as any;
    const requests: string[] = [];
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
    internals.request = async (method: string) => {
      requests.push(method);
      if (method === "initialize") return { result: {} };
      if (method === "thread/unsubscribe") return { result: {} };
      if (method === "thread/start") {
        threadStarts += 1;
        return { result: { thread: { id: threadStarts === 1 ? "thread-loaded" : "thread-new" } } };
      }
      if (method === "turn/start") {
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
      await expect(client.runTurn("first request")).resolves.toMatchObject({
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
    expect(timingLogs.some((line) => line.includes("phase=turn/started"))).toBe(true);
    expect(timingLogs.some((line) => line.includes("phase=userMessage"))).toBe(true);
  });
});
