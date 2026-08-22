import { describe, expect, it } from "vitest";

import {
  buildLaunchArgs,
  buildTypeTextArgs,
  buildUiDumpArgs,
  buildUiDumpReadArgs,
  UI_AUTOMATOR_DUMP_PATH
} from "../src/adb/process-adapter.js";

import {
  parseDisplaysList,
  parseForegroundForDisplay
} from "../src/adb/process-parsers.js";
import { buildVirtualDisplayScrcpyArgs } from "../src/virtual-display.js";

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

    const displayArgs = buildTypeTextArgs("phone-1", "test", 2);
    expect(displayArgs).toContain("-d");
    expect(displayArgs).toContain("2");
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

  it("builds virtual display scrcpy args with required flags", () => {
    const args = buildVirtualDisplayScrcpyArgs("phone-1", "com.example.app", {
      width: 1080,
      height: 2400,
      dpi: 420,
      mouseMode: "disabled",
      noDecorations: true
    });
    expect(args).toEqual([
      "-s",
      "phone-1",
      "--new-display=1080x2400/420",
      "--start-app=com.example.app",
      "--no-vd-system-decorations",
      "--mouse=disabled",
      "--no-audio",
      "--stay-awake",
      "--window-title=Phone Control: com.example.app",
      "--window-width=420"
    ]);
  });

  it("parses multiple displays from dumpsys output", () => {
    const output = `
Display: mDisplayId=0 init=1080x2400 420dpi cur=1080x2400 app=1080x2400 mCurrentRotation=0
Display: mDisplayId=2 init=1920x1080 320dpi cur=1920x1080 app=1920x1080 mCurrentRotation=1
`;
    const parsed = parseDisplaysList(output);
    expect(parsed).toHaveLength(2);
    expect(parsed[0]).toEqual({
      displayId: 0,
      width: 1080,
      height: 2400,
      rotation: 0
    });
    expect(parsed[1]).toEqual({
      displayId: 2,
      width: 1920,
      height: 1080,
      rotation: 1
    });
  });

  it("parses foreground activity for specific displayId", () => {
    const output = `
Display #0 (activities from top to bottom):
  Stack #0:
    Task id #1
      mResumedActivity: ActivityRecord{1234 u0 com.example.launcher/.LauncherActivity t1}
Display #2 (activities from top to bottom):
  Stack #1:
    Task id #2
      mResumedActivity: ActivityRecord{5678 u0 com.example.calculator/.MainActivity t2}
`;
    expect(parseForegroundForDisplay(output, 0).packageName).toBe(
      "com.example.launcher"
    );
    expect(parseForegroundForDisplay(output, 2).packageName).toBe(
      "com.example.calculator"
    );
    expect(parseForegroundForDisplay(output, 2).displayId).toBe(2);
  });
});
