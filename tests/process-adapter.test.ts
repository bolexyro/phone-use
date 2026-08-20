import { describe, expect, it } from "vitest";

import {
  buildLaunchArgs,
  buildTypeTextArgs,
  buildUiDumpArgs,
  buildUiDumpReadArgs,
  UI_AUTOMATOR_DUMP_PATH
} from "../src/adb/process-adapter.js";

describe("fixed ADB process argument construction", () => {
  it("passes launch package and category as raw argv values", () => {
    expect(buildLaunchArgs("phone-1", "com.example.calculator")).toEqual([
      "-s",
      "phone-1",
      "shell",
      "monkey",
      "-p",
      "com.example.calculator",
      "-c",
      "android.intent.category.LAUNCHER",
      "1"
    ]);
    expect(buildLaunchArgs("phone-1", "com.example.calculator")).not.toContain(
      "'com.example.calculator'"
    );
  });

  it("encodes Android spaces and percent signs without adding shell quotes", () => {
    const args = buildTypeTextArgs("phone-1", "hello world 50%");
    expect(args.at(-1)).toBe("hello%sworld%s50%25");
    expect(args.at(-1)).not.toContain("'");
  });

  it("uses only the fixed UI Automator dump path and fixed cat operation", () => {
    expect(buildUiDumpArgs("phone-1").at(-1)).toBe(UI_AUTOMATOR_DUMP_PATH);
    expect(buildUiDumpReadArgs("phone-1")).toEqual([
      "-s",
      "phone-1",
      "exec-out",
      "cat",
      UI_AUTOMATOR_DUMP_PATH
    ]);
  });
});
