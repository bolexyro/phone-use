import { describe, expect, it } from "vitest";
import dgram from "node:dgram";

import { discoverPhone, isPairingCode } from "../src/pairing.js";

describe("pairing discovery input", () => {
  it("accepts the displayed code format", () => {
    expect(isPairingCode("ABCD-2345")).toBe(true);
    expect(isPairingCode("abcd 2345")).toBe(true);
  });

  it("rejects codes containing ambiguous characters", () => {
    expect(isPairingCode("ABCD-0I45")).toBe(false);
    expect(isPairingCode("TOO-SHORT")).toBe(false);
  });

  it("discovers a phone offer without a separate rendezvous server", async () => {
    const server = dgram.createSocket("udp4");
    await new Promise<void>((resolve, reject) => {
      server.once("error", reject);
      server.bind(0, "127.0.0.1", resolve);
    });
    const address = server.address();
    if (typeof address === "string") throw new Error("The test discovery socket did not expose a port.");
    const pairingCode = "ABCD2345";
    server.on("message", (message, remote) => {
      const request = JSON.parse(message.toString()) as { requestId: string };
      const response = Buffer.from(JSON.stringify({
        type: "dhd_pair_offer",
        version: 1,
        requestId: request.requestId,
        deviceId: "test-phone",
        addresses: ["192.168.1.42"],
        port: 8765,
        token: "test-token"
      }));
      server.send(response, remote.port, remote.address);
    });

    try {
      await expect(discoverPhone(pairingCode, {
        discoveryPort: address.port,
        broadcastAddresses: ["127.0.0.1"],
        timeoutMs: 1_000
      })).resolves.toMatchObject({
        host: "127.0.0.1",
        port: 8765,
        token: "test-token",
        deviceId: "test-phone",
        pairingCode
      });
    } finally {
      server.close();
    }
  });
});
