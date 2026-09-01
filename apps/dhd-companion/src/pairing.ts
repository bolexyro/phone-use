import dgram from "node:dgram";
import { randomUUID } from "node:crypto";
import { networkInterfaces } from "node:os";
import net from "node:net";

import {
  normalizePairingCode,
  PAIRING_CODE_LENGTH,
  PAIRING_CODE_ALPHABET
} from "./companion-web/pairing-code.js";

export const PAIRING_PROTOCOL_VERSION = 1;
export const PAIRING_DISCOVERY_PORT = 8766;
export const DEFAULT_PAIRING_TIMEOUT_MS = 5_000;

export interface PairingOffer {
  type: "dhd_pair_offer";
  version: 1;
  requestId: string;
  deviceId: string;
  addresses: string[];
  port: number;
  token: string;
}

export interface ResolvedPairing {
  host: string;
  port: number;
  token: string;
  deviceId: string;
  pairingCode: string;
  addresses: string[];
}

export interface PairingDiscoveryOptions {
  timeoutMs?: number;
  discoveryPort?: number;
  /** Override destinations for deterministic local tests or custom networks. */
  broadcastAddresses?: string[];
}

export function isPairingCode(value: string): boolean {
  const normalized = value.replace(/[\s-]/g, "").trim().toUpperCase();
  return normalized.length === PAIRING_CODE_LENGTH &&
    [...normalized].every((character) => PAIRING_CODE_ALPHABET.includes(character));
}

function ipv4ToNumber(value: string): number | null {
  const parts = value.split(".");
  if (parts.length !== 4) return null;
  let result = 0;
  for (const part of parts) {
    const octet = Number(part);
    if (!Number.isInteger(octet) || octet < 0 || octet > 255) return null;
    result = (result << 8) | octet;
  }
  return result >>> 0;
}

function numberToIpv4(value: number): string {
  return [24, 16, 8, 0].map((shift) => (value >>> shift) & 0xff).join(".");
}

function broadcastAddresses(): string[] {
  const addresses = new Set<string>(["255.255.255.255"]);
  for (const entries of Object.values(networkInterfaces())) {
    for (const entry of entries ?? []) {
      if (entry.family !== "IPv4") continue;
      const address = ipv4ToNumber(entry.address);
      const mask = ipv4ToNumber(entry.netmask);
      if (address === null || mask === null) continue;
      addresses.add(numberToIpv4((address & mask) | (~mask >>> 0)));
    }
  }
  return [...addresses];
}

function parsePairingOffer(value: unknown, requestId: string, sourceAddress: string): PairingOffer | null {
  if (!value || typeof value !== "object") return null;
  const message = value as Record<string, unknown>;
  if (message.type !== "dhd_pair_offer" || message.version !== PAIRING_PROTOCOL_VERSION) return null;
  if (message.requestId !== requestId) return null;
  if (typeof message.deviceId !== "string" || !message.deviceId.trim()) return null;
  if (typeof message.token !== "string" || !message.token.trim()) return null;
  if (typeof message.port !== "number" || !Number.isInteger(message.port) || message.port < 1 || message.port > 65_535) return null;

  const advertisedAddresses = Array.isArray(message.addresses)
    ? message.addresses.filter((address): address is string => typeof address === "string" && net.isIP(address) === 4)
    : [];
  const addresses = [...new Set([sourceAddress, ...advertisedAddresses].filter((address) => net.isIP(address) === 4))];
  if (addresses.length === 0) return null;

  return {
    type: "dhd_pair_offer",
    version: 1,
    requestId,
    deviceId: message.deviceId.trim(),
    addresses,
    port: message.port,
    token: message.token.trim()
  };
}

/**
 * Discover a DHD phone on the local network using only the short pairing code.
 * The response contains the bridge details but is never written to the UI.
 */
export function discoverPhone(
  value: string,
  options: PairingDiscoveryOptions = {}
): Promise<ResolvedPairing> {
  const pairingCode = normalizePairingCode(value);
  const requestId = randomUUID();
  const discoveryPort = options.discoveryPort ?? PAIRING_DISCOVERY_PORT;
  const timeoutMs = options.timeoutMs ?? DEFAULT_PAIRING_TIMEOUT_MS;
  const payload = Buffer.from(JSON.stringify({
    type: "dhd_pair_request",
    version: PAIRING_PROTOCOL_VERSION,
    requestId,
    code: pairingCode
  }), "utf8");

  return new Promise((resolve, reject) => {
    const socket = dgram.createSocket("udp4");
    let settled = false;
    let timer: NodeJS.Timeout | undefined;

    const finish = (error?: Error, offer?: PairingOffer) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      try {
        socket.close();
      } catch {
        // The socket may not have finished binding yet.
      }
      if (error) {
        reject(error);
        return;
      }
      const [host, ...rest] = offer!.addresses;
      resolve({
        host,
        port: offer!.port,
        token: offer!.token,
        deviceId: offer!.deviceId,
        pairingCode,
        addresses: [host, ...rest]
      });
    };

    socket.on("error", (error) => finish(new Error(`Pairing discovery failed: ${error.message}`)));
    socket.on("message", (message, remote) => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(message.toString("utf8"));
      } catch {
        return;
      }
      const offer = parsePairingOffer(parsed, requestId, remote.address);
      if (offer) finish(undefined, offer);
    });
    socket.bind(0, "0.0.0.0", () => {
      socket.setBroadcast(true);
      for (const address of options.broadcastAddresses ?? broadcastAddresses()) {
        socket.send(payload, discoveryPort, address, (error) => {
          if (error) finish(new Error(`Could not send the pairing request: ${error.message}`));
        });
      }
    });
    timer = setTimeout(() => {
      finish(new Error("No DHD phone answered that pairing code. Make sure the phone and companion can reach the same network."));
    }, timeoutMs);
  });
}
