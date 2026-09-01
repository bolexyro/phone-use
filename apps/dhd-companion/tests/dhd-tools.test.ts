import { describe, expect, it } from "vitest";

import {
  DHD_TOOL_NAMES,
  dhdExecuteActionSchema,
  dhdOpenAppInputSchema
} from "../src/dhd-tools.js";
import { buildDhdDynamicTools, toDynamicToolResponse } from "../src/assistant-companion.js";

const metadata = {
  purpose: "Searching for iced tea",
  targetDescription: "Store search field"
};

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

describe("DHD phone tool contract", () => {
  it("uses the same five canonical names for the MCP and App Server surfaces", () => {
    const dynamicNames = buildDhdDynamicTools().map((tool) => String(tool.name));

    expect(DHD_TOOL_NAMES).toEqual([
      "dhd_list_allowed_apps",
      "dhd_observe",
      "dhd_open_app",
      "dhd_execute",
      "dhd_request_attention"
    ]);
    expect(dynamicNames).toEqual([...DHD_TOOL_NAMES]);
    expect(dynamicNames.some((name) => name.startsWith("phone_"))).toBe(false);
  });

  it("keeps app launch separate from typed execution", () => {
    const tools = buildDhdDynamicTools();
    const openApp = record(tools.find((tool) => tool.name === "dhd_open_app"));
    const execute = record(tools.find((tool) => tool.name === "dhd_execute"));
    const executeSchema = record(execute.inputSchema);
    const actionSchema = record(record(executeSchema.properties).action);
    const variants = Array.isArray(actionSchema.oneOf) ? actionSchema.oneOf : [];
    const actionTypes = variants.map((variant) => {
      const typeSchema = record(record(record(variant).properties).type);
      return typeSchema.const;
    });

    expect(record(openApp.inputSchema).properties).toHaveProperty("packageName");
    expect(actionTypes).toEqual(["tap", "type", "swipe", "scroll", "back", "keypress", "wait"]);
    expect(actionTypes).not.toContain("open_app");
    expect(actionTypes).not.toContain("click_coordinate");
  });

  it("rejects removed action variants at the typed-action boundary", () => {
    expect(dhdOpenAppInputSchema.safeParse({ packageName: "com.example.store", metadata }).success).toBe(true);
    expect(dhdExecuteActionSchema.safeParse({ type: "tap", x: 10, y: 20, metadata }).success).toBe(true);
    expect(dhdExecuteActionSchema.safeParse({
      type: "open_app",
      packageName: "com.example.store",
      metadata
    }).success).toBe(false);
    expect(dhdExecuteActionSchema.safeParse({
      type: "click_coordinate",
      x: 10,
      y: 20,
      metadata
    }).success).toBe(false);
  });

  it("preserves phone-tool failures as unsuccessful App Server results", () => {
    const response = toDynamicToolResponse({
      isError: true,
      content: [{
        type: "text",
        text: JSON.stringify({
          ok: false,
          code: "SHIZUKU_UNAVAILABLE",
          outcome: "failed",
          message: "Shizuku is unavailable."
        })
      }],
      structuredContent: {
        ok: false,
        code: "SHIZUKU_UNAVAILABLE",
        outcome: "failed",
        message: "Shizuku is unavailable."
      }
    });

    expect(response.success).toBe(false);
    expect(response.contentItems).toHaveLength(1);
    expect(response.contentItems[0]).toEqual({
      type: "inputText",
      text: JSON.stringify({
        ok: false,
        code: "SHIZUKU_UNAVAILABLE",
        outcome: "failed",
        message: "Shizuku is unavailable."
      })
    });
  });
});
