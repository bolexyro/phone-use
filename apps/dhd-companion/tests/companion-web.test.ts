import type { AddressInfo } from "node:net";

import { afterEach, describe, expect, it } from "vitest";

import {
  createCompanionWebServer,
  ingestCompanionToolCallEvent,
} from "../src/companion-web/server.js";
import type { CompanionState } from "../src/companion-web/api.js";

const openServers: ReturnType<typeof createCompanionWebServer>[] = [];

afterEach(async () => {
  await Promise.all(
    openServers.splice(0).map(
      (server) =>
        new Promise<void>((resolve) => {
          if (!server.listening) {
            resolve();
            return;
          }
          server.close(() => resolve());
        }),
    ),
  );
});

async function openWebServer(): Promise<{ baseUrl: string }> {
  const server = createCompanionWebServer();
  openServers.push(server);
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve());
  });
  const address = server.address() as AddressInfo;
  const baseUrl = `http://127.0.0.1:${address.port}`;
  await fetch(`${baseUrl}/api/clear-tool-calls`, { method: "POST" });
  return { baseUrl };
}

async function readState(baseUrl: string): Promise<CompanionState> {
  const response = await fetch(`${baseUrl}/api/state`);
  expect(response.ok).toBe(true);
  return response.json() as Promise<CompanionState>;
}

describe("companion tool diagnostics", () => {
  it("keeps text and structured response data while serving images outside SSE state", async () => {
    const { baseUrl } = await openWebServer();
    const imageData = Buffer.from("test-image").toString("base64");
    const beforeImageData = Buffer.from("before-image").toString("base64");

    ingestCompanionToolCallEvent({
      type: "dhd_tool_call",
      phase: "started",
      callId: "call-image",
      tool: "dhd_observe",
      arguments: {},
      timestamp: 1_000,
    });
    ingestCompanionToolCallEvent({
      type: "dhd_tool_call",
      phase: "completed",
      callId: "call-image",
      tool: "dhd_observe",
      result: {
        content: [
          { type: "text", text: '{"ok":true}' },
          { type: "image", data: imageData, mimeType: "image/png" },
        ],
        debugImages: [
          { type: "image", label: "before", data: beforeImageData, mimeType: "image/png" },
          { type: "image", label: "after", data: imageData, mimeType: "image/png" },
        ],
        structuredContent: { ok: true },
      },
      completedAt: 1_125,
    });

    const state = await readState(baseUrl);
    expect(state.toolCalls).toHaveLength(1);
    expect(state.toolCalls[0]).toMatchObject({
      id: "call-image",
      tool: "dhd_observe",
      arguments: {},
      status: "success",
      durationMs: 125,
      response: {
        images: [
          {
            type: "image",
            imageUrl: "/api/tool-calls/call-image/images/1",
            mimeType: "image/png",
            index: 1,
          }
        ],
        debugImages: [
          {
            type: "image",
            label: "before",
            imageUrl: "/api/tool-calls/call-image/images/0?source=debug",
            mimeType: "image/png",
            index: 0,
          },
          {
            type: "image",
            label: "after",
            imageUrl: "/api/tool-calls/call-image/images/1?source=debug",
            mimeType: "image/png",
            index: 1,
          },
        ],
        structuredContent: { ok: true },
      },
    });
    expect(state.toolCalls[0].response).not.toHaveProperty("content");
    expect(JSON.stringify(state)).not.toContain(imageData);
    expect(JSON.stringify(state)).not.toContain(beforeImageData);

    const imageUrl = `${baseUrl}${state.toolCalls[0].response?.images[0]?.imageUrl}`;
    const imageResponse = await fetch(imageUrl);
    expect(imageResponse.status).toBe(200);
    expect(imageResponse.headers.get("content-type")).toBe("image/png");
    expect(Buffer.from(await imageResponse.arrayBuffer())).toEqual(Buffer.from("test-image"));

    const debugImageUrl = `${baseUrl}${state.toolCalls[0].response?.debugImages?.[0]?.imageUrl}`;
    const debugImageResponse = await fetch(debugImageUrl);
    expect(debugImageResponse.status).toBe(200);
    expect(debugImageResponse.headers.get("content-type")).toBe("image/png");
    expect(Buffer.from(await debugImageResponse.arrayBuffer())).toEqual(Buffer.from("before-image"));

    const clearResponse = await fetch(`${baseUrl}/api/clear-tool-calls`, { method: "POST" });
    expect(clearResponse.ok).toBe(true);
    expect((await readState(baseUrl)).toolCalls).toEqual([]);
    expect((await fetch(imageUrl)).status).toBe(404);
    expect((await fetch(debugImageUrl)).status).toBe(404);
  });

  it("associates out-of-order completions with their stable call IDs and ignores non-DHD events", async () => {
    const { baseUrl } = await openWebServer();

    ingestCompanionToolCallEvent({
      type: "dhd_tool_call",
      phase: "started",
      callId: "call-a",
      tool: "dhd_observe",
      arguments: { purpose: "first" },
      timestamp: 2_000,
    });
    ingestCompanionToolCallEvent({
      type: "dhd_tool_call",
      phase: "started",
      callId: "call-b",
      tool: "dhd_execute",
      arguments: { action: { type: "back" } },
      timestamp: 2_010,
    });
    ingestCompanionToolCallEvent({
      type: "dhd_tool_call",
      phase: "completed",
      callId: "call-b",
      tool: "dhd_execute",
      result: { isError: true, content: [{ type: "text", text: "failed" }] },
      error: "POST_OBSERVATION_FAILED",
      completedAt: 2_040,
    });
    ingestCompanionToolCallEvent({
      type: "completed",
      phase: "completed",
      callId: "ignored",
      tool: "phone_observe",
      completedAt: 2_050,
    });
    ingestCompanionToolCallEvent({
      type: "dhd_tool_call",
      phase: "completed",
      callId: "call-a",
      tool: "dhd_observe",
      result: { content: [{ type: "text", text: "ok" }] },
      completedAt: 2_080,
    });

    const calls = (await readState(baseUrl)).toolCalls;
    expect(calls.map((call) => call.id)).toEqual(["call-a", "call-b"]);
    expect(calls[0]).toMatchObject({ status: "success", durationMs: 80 });
    expect(calls[1]).toMatchObject({
      status: "error",
      durationMs: 30,
      error: "POST_OBSERVATION_FAILED",
    });
  });

  it("retains only the latest 50 calls and evicts their image storage", async () => {
    const { baseUrl } = await openWebServer();
    const imageData = Buffer.from("evicted-image").toString("base64");

    ingestCompanionToolCallEvent({
      type: "dhd_tool_call",
      phase: "started",
      callId: "call-0",
      tool: "dhd_observe",
      arguments: {},
      timestamp: 0,
    });
    ingestCompanionToolCallEvent({
      type: "dhd_tool_call",
      phase: "completed",
      callId: "call-0",
      tool: "dhd_observe",
      result: { content: [{ type: "image", data: imageData, mimeType: "image/png" }] },
      completedAt: 1,
    });
    for (let index = 1; index <= 50; index += 1) {
      ingestCompanionToolCallEvent({
        type: "dhd_tool_call",
        phase: "started",
        callId: `call-${index}`,
        tool: "dhd_observe",
        arguments: {},
        timestamp: index,
      });
    }

    const state = await readState(baseUrl);
    expect(state.toolCalls).toHaveLength(50);
    expect(state.toolCalls[0].id).toBe("call-1");
    expect((await fetch(`${baseUrl}/api/tool-calls/call-0/images/0`)).status).toBe(404);
  });
});
