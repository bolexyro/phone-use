import { describe, expect, it } from "vitest";

import {
  GUARD_REGIONS_FEATURE_FLAG,
  DHD_MAX_SEQUENCE_ACTIONS,
  DHD_TOOL_NAMES,
  createDhdToolSchemas,
  dhdBrowseAppInputSchema,
  dhdGetForegroundAppInputSchema,
  dhdExecuteActionSchema,
  dhdExecuteSequenceInputSchema,
  dhdListAllowedAppsInputSchema,
  dhdObserveInputSchema,
  dhdOpenAppInputSchema,
  isGuardRegionsEnabled,
  toMcpResult
} from "../src/dhd-tools.js";
import { buildDhdDynamicTools, toDynamicToolResponse } from "../src/assistant-companion.js";
import { dhdToolDescription } from "../src/dhd-tool-contract.js";

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

const pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";

describe("DHD phone tool contract", () => {
  it("keeps screenshots out of text and maps them to image content on both transports", () => {
    const mcpResult = toMcpResult({
      type: "observation",
      ok: true,
      observation: { id: "obs-1" },
      screenshotBase64: pngBase64,
      screenshotMimeType: "image/png"
    });

    expect(mcpResult.content).toEqual([
      {
        type: "text",
        text: JSON.stringify({
          type: "observation",
          ok: true,
          observation: { id: "obs-1" },
          screenshotMimeType: "image/png"
        })
      },
      { type: "image", data: pngBase64, mimeType: "image/png" }
    ]);
    expect(mcpResult.content[0]).not.toHaveProperty("data");
    expect(JSON.stringify(mcpResult.content[0])).not.toContain(pngBase64);

    const dynamicResult = toDynamicToolResponse(mcpResult);
    expect(dynamicResult).toEqual({
      success: true,
      contentItems: [
        {
          type: "inputText",
          text: JSON.stringify({
            type: "observation",
            ok: true,
            observation: { id: "obs-1" },
            screenshotMimeType: "image/png"
          })
        },
        { type: "inputImage", imageUrl: `data:image/png;base64,${pngBase64}` }
      ]
    });
    expect(JSON.stringify(dynamicResult.contentItems[0])).not.toContain(pngBase64);
  });

  it("adds a calibration marker and then marks the last successful tap", () => {
    const first = toMcpResult({
      type: "observation",
      ok: true,
      observation: {
        id: "marker-obs-1",
        displayId: 91,
        packageName: "com.example.app",
        rotation: 0,
        width: 1,
        height: 1
      },
      screenshotBase64: pngBase64,
      screenshotMimeType: "image/png"
    });
    const firstMarker = (first.structuredContent as Record<string, any>).screenshotMarker;

    expect(firstMarker).toMatchObject({
      kind: "calibration",
      x: 0,
      y: 0,
      coordinateSpace: "display"
    });
    expect((first.content[0] as { text: string }).text).toContain('"screenshotMarker"');
    expect(first.content[1]).toMatchObject({ type: "image", mimeType: "image/png" });
    expect((first.content[1] as { data: string }).data).not.toBe(pngBase64);

    const tapped = toMcpResult(
      {
        type: "completed",
        ok: true,
        observation: {
          id: "marker-obs-2",
          displayId: 91,
          packageName: "com.example.app",
          rotation: 0,
          width: 1,
          height: 1
        },
        screenshotBase64: pngBase64,
        screenshotMimeType: "image/png"
      },
      undefined,
      { action: { type: "tap", x: 0, y: 0 } }
    );

    expect((tapped.structuredContent as Record<string, any>).screenshotMarker).toEqual({
      kind: "last_tap",
      x: 0,
      y: 0,
      coordinateSpace: "display"
    });
    expect((tapped.structuredContent as Record<string, any>).screenshotMarker).not.toEqual(firstMarker);
  });

  it("normalizes an already-prefixed screenshot data URL without double-prefixing it", () => {
    const result = toMcpResult({
      ok: true,
      screenshotBase64: `data:image/png;base64,${pngBase64}`,
      screenshotMimeType: "image/png"
    });

    expect(result.content).toContainEqual({ type: "image", data: pngBase64, mimeType: "image/png" });
    expect(toDynamicToolResponse(result).contentItems).toContainEqual({
      type: "inputImage",
      imageUrl: `data:image/png;base64,${pngBase64}`
    });
  });

  it("fails closed for unsupported or malformed screenshot payloads", () => {
    expect(() => toMcpResult({ screenshotBase64: "not base64" })).toThrow("invalid base64");
    expect(() => toMcpResult({
      screenshotBase64: pngBase64,
      screenshotMimeType: "image/jpeg"
    })).toThrow("Unsupported phone screenshot MIME type");
  });

  it("uses the same canonical names for the MCP and App Server surfaces", () => {
    const dynamicNames = buildDhdDynamicTools().map((tool) => String(tool.name));
    const listTool = record(buildDhdDynamicTools().find((tool) => tool.name === "dhd_list_allowed_apps"));
    const browseTool = record(buildDhdDynamicTools().find((tool) => tool.name === "dhd_browse_app"));
    const foregroundTool = record(buildDhdDynamicTools().find((tool) => tool.name === "dhd_get_foreground_app"));
    const observeTool = record(buildDhdDynamicTools().find((tool) => tool.name === "dhd_observe"));
    const openAppTool = record(buildDhdDynamicTools().find((tool) => tool.name === "dhd_open_app"));

    expect(DHD_TOOL_NAMES).toEqual([
      "dhd_list_allowed_apps",
      "dhd_browse_app",
      "dhd_get_foreground_app",
      "dhd_observe",
      "dhd_open_app",
      "dhd_execute",
      "dhd_execute_sequence",
      "dhd_request_attention"
    ]);
    expect(dynamicNames).toEqual([...DHD_TOOL_NAMES]);
    const dynamicTools = buildDhdDynamicTools();
    for (const name of DHD_TOOL_NAMES) {
      const tool = record(dynamicTools.find((candidate) => candidate.name === name));
      expect(tool.description).toBe(dhdToolDescription(name));
    }
    expect(dynamicNames.some((name) => name.startsWith("phone_"))).toBe(false);
    expect(String(listTool.description)).toContain("Full Access");
    expect(String(listTool.description)).toContain("includeAll");
    expect(record(listTool.inputSchema).properties).toHaveProperty("includeAll");
    expect(String(browseTool.description)).toContain("package names");
    expect(record(browseTool.inputSchema).properties).toHaveProperty("query");
    expect(String(foregroundTool.description)).toContain("read-only");
    expect(record(foregroundTool.inputSchema).properties).toEqual({});
    expect(record(observeTool.inputSchema).properties).not.toHaveProperty("guardRegions");
    expect(String(openAppTool.description)).toContain("Full Access");
    expect(String(observeTool.description)).toContain("not part of the Android app UI");
  });

  it("keeps app launch separate from typed execution", () => {
    const tools = buildDhdDynamicTools();
    const openApp = record(tools.find((tool) => tool.name === "dhd_open_app"));
    const execute = record(tools.find((tool) => tool.name === "dhd_execute"));
    const sequence = record(tools.find((tool) => tool.name === "dhd_execute_sequence"));
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

    const sequenceSchema = record(sequence.inputSchema);
    const sequenceProperties = record(sequenceSchema.properties);
    const sequenceActions = record(sequenceProperties.actions);
    const sequenceItems = record(sequenceActions.items);
    const sequenceVariants = Array.isArray(sequenceItems.oneOf) ? sequenceItems.oneOf : [];
    const sequenceMetadata = record(record(record(sequenceVariants[0]).properties).metadata);
    expect(sequenceSchema.required).toEqual(["observationId", "actions"]);
    expect(sequenceActions.maxItems).toBe(DHD_MAX_SEQUENCE_ACTIONS);
    expect(sequenceMetadata.properties).not.toHaveProperty("observationId");
    expect(sequenceMetadata.required).toEqual(["purpose", "targetDescription"]);
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
    expect(dhdGetForegroundAppInputSchema.parse({})).toEqual({});
    expect(dhdGetForegroundAppInputSchema.safeParse({ unexpected: true }).success).toBe(false);
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
    const enabledSequence = record(enabledTools.find((tool) => tool.name === "dhd_execute_sequence"));
    const enabledActionSchema = record(record(enabledExecute.inputSchema).properties).action;
    const enabledVariant = record(record(enabledActionSchema).oneOf?.[0]);
    const enabledMetadata = record(record(enabledVariant.properties).metadata);
    const enabledOpenAppMetadata = record(record(enabledOpenApp.inputSchema).properties).metadata;
    const enabledSequenceActions = record(record(enabledSequence.inputSchema).properties).actions;
    const enabledSequenceVariant = record(record(enabledSequenceActions.items).oneOf?.[0]);
    const enabledSequenceMetadata = record(record(enabledSequenceVariant.properties).metadata);

    expect(record(disabledObserve.inputSchema).properties).not.toHaveProperty("guardRegions");
    expect(record(enabledObserve.inputSchema).properties).toHaveProperty("guardRegions");
    expect(enabledMetadata.properties).toHaveProperty("guardRegions");
    expect(record(enabledOpenAppMetadata).properties).not.toHaveProperty("guardRegions");
    expect(enabledSequenceMetadata.properties).toHaveProperty("guardRegions");
    expect(String(enabledObserve.description)).toContain("guardRegions");
    for (const name of DHD_TOOL_NAMES) {
      const tool = record(enabledTools.find((candidate) => candidate.name === name));
      expect(tool.description).toBe(dhdToolDescription(name, true));
    }

    const region = { left: 0, top: 0, right: 100, bottom: 100 };
    const enabledSchemas = createDhdToolSchemas(true);
    expect(enabledSchemas.dhdObserveInputSchema.safeParse({ guardRegions: [region] }).success).toBe(true);
    expect(enabledSchemas.dhdExecuteActionSchema.safeParse({
      type: "tap",
      x: 10,
      y: 20,
      metadata: { ...metadata, guardRegions: [region] }
    }).success).toBe(true);
    expect(enabledSchemas.dhdExecuteSequenceInputSchema.safeParse({
      observationId: "obs-1",
      actions: [{
        type: "tap",
        x: 10,
        y: 20,
        metadata: {
          purpose: "Tap the button",
          targetDescription: "Button",
          guardRegions: [region]
        }
      }]
    }).success).toBe(true);
    expect(enabledSchemas.dhdOpenAppInputSchema.safeParse({
      packageName: "com.example.store",
      metadata: { ...metadata, guardRegions: [region] }
    }).success).toBe(false);
    expect(createDhdToolSchemas(false).dhdObserveInputSchema.safeParse({ guardRegions: [region] }).success).toBe(false);
    expect(createDhdToolSchemas(false).dhdExecuteSequenceInputSchema.safeParse({
      observationId: "obs-1",
      actions: [{
        type: "tap",
        x: 10,
        y: 20,
        metadata: {
          purpose: "Tap the button",
          targetDescription: "Button",
          guardRegions: [region]
        }
      }]
    }).success).toBe(false);
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

  it("requires one initial observation for a fixed typed sequence", () => {
    const action = {
      type: "tap" as const,
      x: 10,
      y: 20,
      metadata: {
        purpose: "Tap the first button",
        targetDescription: "First button"
      }
    };

    expect(dhdExecuteSequenceInputSchema.safeParse({
      observationId: "obs-1",
      actions: [action]
    }).success).toBe(true);
    expect(dhdExecuteSequenceInputSchema.safeParse({
      actions: [action]
    }).success).toBe(false);
    expect(dhdExecuteSequenceInputSchema.safeParse({
      observationId: "obs-1",
      actions: [{
        ...action,
        metadata: { ...action.metadata, observationId: "obs-1" }
      }]
    }).success).toBe(false);
    expect(dhdExecuteSequenceInputSchema.safeParse({
      observationId: "obs-1",
      actions: [{
        type: "open_app",
        packageName: "com.example.store",
        metadata: action.metadata
      }]
    }).success).toBe(false);
    expect(dhdExecuteSequenceInputSchema.safeParse({
      observationId: "obs-1",
      actions: Array.from({ length: DHD_MAX_SEQUENCE_ACTIONS + 1 }, () => action)
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
