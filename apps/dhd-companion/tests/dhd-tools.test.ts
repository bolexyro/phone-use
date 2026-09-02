import { describe, expect, it } from "vitest";

import {
  GUARD_REGIONS_FEATURE_FLAG,
  DHD_TOOL_NAMES,
  createDhdToolSchemas,
  dhdBrowseAppInputSchema,
  dhdExecuteActionSchema,
  dhdListAllowedAppsInputSchema,
  dhdObserveInputSchema,
  dhdOpenAppInputSchema,
  isGuardRegionsEnabled
} from "../src/dhd-tools.js";
import { buildDhdDynamicTools, toDynamicToolResponse } from "../src/assistant-companion.js";

const metadata = {
  purpose: "Searching for iced tea",
  targetDescription: "Store search field",
  observationId: "obs-1"
};

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

describe("DHD phone tool contract", () => {
  it("uses the same six canonical names for the MCP and App Server surfaces", () => {
    const dynamicNames = buildDhdDynamicTools().map((tool) => String(tool.name));
    const listTool = record(buildDhdDynamicTools().find((tool) => tool.name === "dhd_list_allowed_apps"));
    const browseTool = record(buildDhdDynamicTools().find((tool) => tool.name === "dhd_browse_app"));
    const observeTool = record(buildDhdDynamicTools().find((tool) => tool.name === "dhd_observe"));
    const openAppTool = record(buildDhdDynamicTools().find((tool) => tool.name === "dhd_open_app"));

    expect(DHD_TOOL_NAMES).toEqual([
      "dhd_list_allowed_apps",
      "dhd_browse_app",
      "dhd_observe",
      "dhd_open_app",
      "dhd_execute",
      "dhd_request_attention"
    ]);
    expect(dynamicNames).toEqual([...DHD_TOOL_NAMES]);
    expect(dynamicNames.some((name) => name.startsWith("phone_"))).toBe(false);
    expect(String(listTool.description)).toContain("Full Access");
    expect(String(listTool.description)).toContain("includeAll");
    expect(record(listTool.inputSchema).properties).toHaveProperty("includeAll");
    expect(String(browseTool.description)).toContain("package names");
    expect(record(browseTool.inputSchema).properties).toHaveProperty("query");
    expect(record(observeTool.inputSchema).properties).not.toHaveProperty("guardRegions");
    expect(String(openAppTool.description)).toContain("Full Access");
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
    const firstActionVariant = record(variants[0]);
    const actionMetadata = record(record(firstActionVariant.properties).metadata);
    expect(actionMetadata.properties).toHaveProperty("observationId");
    expect(actionMetadata.properties).not.toHaveProperty("guardRegions");
    expect(actionMetadata.required).toContain("observationId");
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

  it("validates explicit app discovery inputs", () => {
    expect(dhdListAllowedAppsInputSchema.parse({})).toEqual({ includeAll: false });
    expect(dhdListAllowedAppsInputSchema.parse({ includeAll: true })).toEqual({ includeAll: true });
    expect(dhdBrowseAppInputSchema.parse({ query: "  Spotify  " })).toEqual({ query: "Spotify" });
    expect(dhdBrowseAppInputSchema.safeParse({ query: " " }).success).toBe(false);
    expect(dhdObserveInputSchema.safeParse({
      guardRegions: [{ left: 10, top: 20, right: 100, bottom: 120 }]
    }).success).toBe(false);
  });

  it("exposes guard regions only when the feature flag is enabled", () => {
    expect(isGuardRegionsEnabled({})).toBe(false);
    expect(isGuardRegionsEnabled({ [GUARD_REGIONS_FEATURE_FLAG]: "true" })).toBe(true);

    const disabledObserve = record(buildDhdDynamicTools({ enableGuardRegions: false })
      .find((tool) => tool.name === "dhd_observe"));
    const enabledTools = buildDhdDynamicTools({ enableGuardRegions: true });
    const enabledObserve = record(enabledTools.find((tool) => tool.name === "dhd_observe"));
    const enabledOpenApp = record(enabledTools.find((tool) => tool.name === "dhd_open_app"));
    const enabledExecute = record(enabledTools.find((tool) => tool.name === "dhd_execute"));
    const enabledActionSchema = record(record(enabledExecute.inputSchema).properties).action;
    const enabledVariant = record(record(enabledActionSchema).oneOf?.[0]);
    const enabledMetadata = record(record(enabledVariant.properties).metadata);
    const enabledOpenAppMetadata = record(record(enabledOpenApp.inputSchema).properties).metadata;

    expect(record(disabledObserve.inputSchema).properties).not.toHaveProperty("guardRegions");
    expect(record(enabledObserve.inputSchema).properties).toHaveProperty("guardRegions");
    expect(enabledMetadata.properties).toHaveProperty("guardRegions");
    expect(record(enabledOpenAppMetadata).properties).not.toHaveProperty("guardRegions");
    expect(String(enabledObserve.description)).toContain("guardRegions");

    const region = { left: 0, top: 0, right: 100, bottom: 100 };
    const enabledSchemas = createDhdToolSchemas(true);
    expect(enabledSchemas.dhdObserveInputSchema.safeParse({ guardRegions: [region] }).success).toBe(true);
    expect(enabledSchemas.dhdExecuteActionSchema.safeParse({
      type: "tap",
      x: 10,
      y: 20,
      metadata: { ...metadata, guardRegions: [region] }
    }).success).toBe(true);
    expect(enabledSchemas.dhdOpenAppInputSchema.safeParse({
      packageName: "com.example.store",
      metadata: { ...metadata, guardRegions: [region] }
    }).success).toBe(false);
    expect(createDhdToolSchemas(false).dhdObserveInputSchema.safeParse({ guardRegions: [region] }).success).toBe(false);
  });

  it("requires an observation baseline for actions", () => {
    expect(dhdExecuteActionSchema.safeParse({
      type: "tap",
      x: 10,
      y: 20,
      metadata: {
        purpose: "Tap the button",
        targetDescription: "Button",
        observationId: "obs-1"
      }
    }).success).toBe(true);
    expect(dhdExecuteActionSchema.safeParse({
      type: "tap",
      x: 10,
      y: 20,
      metadata: {
        purpose: "Tap the button",
        targetDescription: "Button"
      }
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
