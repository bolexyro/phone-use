import { describe, expect, it } from "vitest";

import {
  displayPairingCode,
  formatPairingCodeDraft,
  normalizePairingCode
} from "../src/companion-web/pairing-code.js";

describe("DHD pairing codes", () => {
  it("normalizes codes copied with spaces or a separator", () => {
    expect(normalizePairingCode(" abcd-2345 ")).toBe("ABCD2345");
    expect(normalizePairingCode("abcd–2345")).toBe("ABCD2345"); // en dash
    expect(normalizePairingCode("abcd—2345")).toBe("ABCD2345"); // em dash
    expect(normalizePairingCode("abcd 2345")).toBe("ABCD2345");
    expect(displayPairingCode("abcd2345")).toBe("ABCD-2345");
  });

  it("formats draft input as user types", () => {
    expect(formatPairingCodeDraft("abc")).toBe("ABC");
    expect(formatPairingCodeDraft("abcd")).toBe("ABCD-");
    expect(formatPairingCodeDraft("abcd2")).toBe("ABCD-2");
    expect(formatPairingCodeDraft("abcd-2345")).toBe("ABCD-2345");
    expect(formatPairingCodeDraft("abcd 2345 extra")).toBe("ABCD-2345");
    expect(formatPairingCodeDraft("ab-cd-2345")).toBe("ABCD-2345");
  });

  it("rejects malformed or ambiguous codes", () => {
    expect(() => normalizePairingCode("ABC123")).toThrow();
    expect(() => normalizePairingCode("ABCD-0I45")).toThrow();
  });
});
