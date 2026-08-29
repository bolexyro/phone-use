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
});
