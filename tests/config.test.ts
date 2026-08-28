import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

import { loadPolicy, resolveProfile } from "../src/config.js";
import { PhoneControlError } from "../src/errors.js";

const exampleConfigPath = fileURLToPath(
  new URL("../config/phone-control.example.json", import.meta.url)
);

describe("policy configuration", () => {
  it("defaults to the local profile and loads the server-side allowlist", () => {
    expect(resolveProfile({})).toBe("local");

    const policy = loadPolicy({
      configPath: exampleConfigPath,
      env: {}
    });

    expect(policy.profile).toBe("local");
    expect(policy.allowedApps).toEqual([
      "com.sec.android.app.popupcalculator",
      "com.spotify.music",
      "org.telegram.messenger",
      "com.phonecontrol.coordinatebenchmark"
    ]);
  });

  it("uses PHONE_CONTROL_PROFILE and rejects unknown profiles with a stable code", () => {
    expect(resolveProfile({ PHONE_CONTROL_PROFILE: "  work  " })).toBe("work");

    try {
      loadPolicy({
        configPath: exampleConfigPath,
        env: { PHONE_CONTROL_PROFILE: "work" }
      });
      throw new Error("expected loadPolicy to fail");
    } catch (error) {
      expect(error).toBeInstanceOf(PhoneControlError);
      expect((error as PhoneControlError).code).toBe("POLICY_INVALID");
    }
  });
});
