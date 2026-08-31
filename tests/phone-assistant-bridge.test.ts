import { describe, expect, it } from "vitest";

import {
  bridgeConfigurationError,
  buildBridgePayload,
  isLoopbackBridgeHost,
  parsePort,
  type BridgeRequest,
} from "../src/phone-assistant-bridge.js";

describe("phone assistant bridge configuration", () => {
  it("recognizes loopback hosts, including IPv6 loopback", () => {
    expect(isLoopbackBridgeHost("127.0.0.1")).toBe(true);
    expect(isLoopbackBridgeHost("LOCALHOST")).toBe(true);
    expect(isLoopbackBridgeHost("[::1]")).toBe(true);
    expect(isLoopbackBridgeHost("192.168.1.42")).toBe(false);
  });

  it("requires a token for non-loopback bridge targets", () => {
    expect(bridgeConfigurationError("127.0.0.1", undefined)).toBeNull();
    expect(bridgeConfigurationError("192.168.1.42", "  ")).toContain("TOKEN");
    expect(bridgeConfigurationError("192.168.1.42", "paired-token")).toBeNull();
  });

  it("adds a trimmed token without changing the typed request", () => {
    const request: BridgeRequest = { type: "status", requestId: "request-1" };

    expect(buildBridgePayload(request, "  paired-token  ")).toEqual({
      type: "status",
      requestId: "request-1",
      authToken: "paired-token",
    });
    expect(buildBridgePayload(request, undefined)).toEqual(request);
  });

  it("keeps bridge ports within the TCP port range", () => {
    expect(parsePort("8765")).toBe(8765);
    expect(() => parsePort("0")).toThrow();
    expect(() => parsePort("65536")).toThrow();
  });
});
