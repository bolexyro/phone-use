import { describe, expect, it } from "vitest";

import { CodexAppServerClient } from "../src/assistant-companion.js";

describe("DHD App Server thread contract", () => {
  it("starts a fresh thread instead of resuming a stored thread on a new client", async () => {
    const client = new CodexAppServerClient();
    const internals = client as any;
    const requests: Array<{ method: string; params?: Record<string, unknown> }> = [];

    internals.startProcess = () => {
      internals.child = { pid: 1234, stdin: { destroyed: false } };
    };
    internals.notify = () => undefined;
    internals.request = async (method: string, params?: Record<string, unknown>) => {
      requests.push({ method, params });
      if (method === "initialize") return { result: {} };
      if (method === "thread/start") return { result: { thread: { id: "fresh-thread" } } };
      if (method === "turn/start") {
        queueMicrotask(() => {
          internals.handleLine(JSON.stringify({
            method: "turn/completed",
            params: { turn: { status: "completed" } }
          }));
        });
        return { result: { turn: { id: "turn-fresh" } } };
      }
      throw new Error(`Unexpected App Server request in test: ${method}`);
    };

    await expect(client.runTurn("use the phone", "legacy-thread", undefined, undefined, "xhigh")).resolves.toMatchObject({
      threadId: "fresh-thread"
    });

    expect(requests.map(({ method }) => method)).toEqual([
      "initialize",
      "thread/start",
      "turn/start"
    ]);
    const dynamicTools = requests[1]?.params?.dynamicTools as Array<Record<string, unknown>>;
    expect(dynamicTools.map((tool) => tool.name)).toEqual([
      "dhd_list_allowed_apps",
      "dhd_observe",
      "dhd_open_app",
      "dhd_execute",
      "dhd_request_attention"
    ]);
    expect(requests.find(({ method }) => method === "turn/start")?.params?.effort).toBe("xhigh");
  });
});
