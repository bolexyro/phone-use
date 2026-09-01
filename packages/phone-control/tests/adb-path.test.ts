import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

import { resolveAdbPath } from "../src/adb/path.js";
import { PhoneControlError } from "../src/errors.js";

describe("ADB path resolution", () => {
  const cwd = resolve("phone-control-path-test");

  it("prefers PHONE_CONTROL_ADB_PATH", () => {
    const configured = resolve(cwd, "custom", "adb.exe");
    const bundled = resolve(cwd, "scrcpy-win64-v4.1", "adb.exe");
    const existing = new Set([configured, bundled]);

    expect(
      resolveAdbPath({
        cwd,
        platform: "win32",
        env: { PHONE_CONTROL_ADB_PATH: configured },
        fileExists: (candidate) => existing.has(candidate)
      })
    ).toBe(configured);
  });

  it("falls back to the bundled executable before PATH", () => {
    const bundled = resolve(cwd, "scrcpy-win64-v4.1", "adb.exe");
    const pathAdb = resolve(cwd, "platform-tools", "adb.exe");
    const existing = new Set([bundled, pathAdb]);

    expect(
      resolveAdbPath({
        cwd,
        platform: "win32",
        env: {
          PHONE_CONTROL_ADB_PATH: resolve(cwd, "missing", "adb.exe"),
          Path: resolve(cwd, "platform-tools")
        },
        fileExists: (candidate) => existing.has(candidate)
      })
    ).toBe(bundled);
  });

  it("uses PATH when no earlier candidate exists and returns a stable error otherwise", () => {
    const pathAdb = resolve(cwd, "platform-tools", "adb.exe");
    expect(
      resolveAdbPath({
        cwd,
        platform: "win32",
        env: { Path: resolve(cwd, "platform-tools") },
        fileExists: (candidate) => candidate === pathAdb
      })
    ).toBe(pathAdb);

    try {
      resolveAdbPath({ cwd, platform: "win32", env: {}, fileExists: () => false });
      throw new Error("expected resolveAdbPath to fail");
    } catch (error) {
      expect(error).toBeInstanceOf(PhoneControlError);
      expect((error as PhoneControlError).code).toBe("ADB_NOT_FOUND");
    }
  });
});
