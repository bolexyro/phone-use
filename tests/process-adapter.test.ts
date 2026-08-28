import { describe, expect, it } from "vitest";

import {
  buildLaunchArgs,
  buildLaunchOnDisplayArgs,
  buildResolveLaunchActivityArgs,
  buildTypeTextArgs,
  buildUiDumpArgs,
  buildUiDumpReadArgs,
  parseLogicalDisplayUniqueId,
  parseSurfaceFlingerDisplayId,
  parseUniqueSurfaceFlingerVirtualDisplayId,
  parseResolvedLaunchActivity,
  UI_AUTOMATOR_DUMP_PATH
} from "../src/adb/process-adapter.js";

import {
  parseDisplaySnapshotForId,
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

  it("builds a fixed display-specific multiple-task launch", () => {
    expect(
      buildLaunchOnDisplayArgs("phone-1", "com.example.calculator/.Main", 7, {
        multipleTask: true
      })
    ).toEqual([
      "-s",
      "phone-1",
      "shell",
      "am",
      "start",
      "-W",
      "--display",
      "7",
      "-f",
      "0x18080000",
      "-n",
      "com.example.calculator/.Main"
    ]);

    expect(
      buildResolveLaunchActivityArgs("phone-1", "com.example.calculator")
    ).toEqual([
      "-s",
      "phone-1",
      "shell",
      "cmd",
      "package",
      "resolve-activity",
      "--brief",
      "-a",
      "android.intent.action.MAIN",
      "-c",
      "android.intent.category.LAUNCHER",
      "com.example.calculator"
    ]);
    expect(
      parseResolvedLaunchActivity(
        "priority=0\r\ncom.example.calculator/.Main\r\n",
        "com.example.calculator"
      )
    ).toBe("com.example.calculator/.Main");
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

    const isolatedPath = "/sdcard/phone_control_window_dump-1.xml";
    expect(buildUiDumpArgs("phone-1", isolatedPath).at(-1)).toBe(isolatedPath);
    expect(buildUiDumpReadArgs("phone-1", isolatedPath).at(-1)).toBe(
      isolatedPath
    );
  });

  it("only resolves the SurfaceFlinger id whose line names the requested display", () => {
    const output = [
      "Virtual Display 17 (scrcpy): 1080x2400",
      "Virtual Display 134 (scrcpy): 1080x2400",
      "Virtual Display 206 (scrcpy): 1920x1080"
    ].join("\n");

    expect(parseSurfaceFlingerDisplayId(output, 134)).toBe("134");
    expect(parseSurfaceFlingerDisplayId(output, 206)).toBe("206");
    expect(parseSurfaceFlingerDisplayId(output, 99)).toBeUndefined();

    expect(
      parseSurfaceFlingerDisplayId(
        'Display 134 (Virtual display): displayName="scrcpy"',
        134
      )
    ).toBe("134");
  });

  it("maps a logical display to SurfaceFlinger through DisplayInfo.uniqueId", () => {
    const displayInfo =
      'Display id 134: DisplayInfo{"scrcpy", displayId 134, uniqueId "virtual:com.android.shell,2000,scrcpy,3"}';
    const uniqueId = parseLogicalDisplayUniqueId(displayInfo, 134);
    expect(uniqueId).toBe("virtual:com.android.shell,2000,scrcpy,3");
    expect(
      parseSurfaceFlingerDisplayId(
        'Display 9223372036854775811 (Virtual display): displayName="scrcpy" uniqueId="virtual:com.android.shell,2000,scrcpy,3"',
        134,
        uniqueId
      )
    ).toBe("9223372036854775811");
    expect(
      parseSurfaceFlingerDisplayId(
        'Display 134 (Virtual display): displayName="other" uniqueId="virtual:other,2000,scrcpy,4"',
        134,
        uniqueId
      )
    ).toBeUndefined();
    expect(
      parseSurfaceFlingerDisplayId(
        [
          'Display 9223372036854775811 (Virtual display): uniqueId="virtual:com.android.shell,2000,scrcpy,3"',
          'Display 9223372036854775812 (Virtual display): uniqueId="virtual:com.android.shell,2000,scrcpy,3"'
        ].join("\n"),
        134,
        uniqueId
      )
    ).toBeUndefined();
  });

  it("maps a physical local unique id only when SurfaceFlinger lists that token", () => {
    const displayInfo =
      'Display id 2: DisplayInfo{"HDMI", displayId 2, uniqueId "local:4630947059332006275"}';
    const uniqueId = parseLogicalDisplayUniqueId(displayInfo, 2);
    expect(
      parseSurfaceFlingerDisplayId(
        "Display 4630947059332006275 (HWC display 1): port=1",
        2,
        uniqueId
      )
    ).toBe("4630947059332006275");
    expect(
      parseSurfaceFlingerDisplayId(
        "Display 4630947059332006276 (HWC display 1): port=1",
        2,
        uniqueId
      )
    ).toBeUndefined();
  });

  it("does not treat an unrelated physical display line as a virtual target", () => {
    const output = [
      "Display 134 (HWC display 0): port=0 displayName=\"Built-in\"",
      "Display 206 (HWC display 1): port=1 displayName=\"HDMI\""
    ].join("\n");

    expect(parseSurfaceFlingerDisplayId(output, 134)).toBeUndefined();
  });

  it("accepts one named virtual candidate from the full SurfaceFlinger dump", () => {
    const output = [
      'Virtual Display 9223372036854775811',
      ' + DisplayDevice{9223372036854775811, virtual, "scrcpy"}',
      'Virtual Display 9223372036854775812',
      ' + DisplayDevice{9223372036854775812, virtual, "other"}'
    ].join("\n");

    expect(parseUniqueSurfaceFlingerVirtualDisplayId(output)).toBe(
      "9223372036854775811"
    );
  });

  it("accepts one named virtual candidate from Android 15/16 --display-id and --displays dumps", () => {
    const sfDisplayIdOutput = [
      'Display 4630947059332006275 (HWC display 0): port=131 pnpId=QCM screenPartStatus=UNSUPPORTED displayName=""',
      'Display 11529215048760749642 (Virtual display): displayName="scrcpy"'
    ].join("\n");

    expect(parseUniqueSurfaceFlingerVirtualDisplayId(sfDisplayIdOutput)).toBe(
      "11529215048760749642"
    );

    const sfDisplaysOutput = [
      "Display 4630947059332006275",
      "    port=131",
      "    connectionType=Internal",
      "Virtual Display 11529215048760749642",
      '    name="scrcpy"',
      "    powerMode=ON"
    ].join("\n");

    expect(parseUniqueSurfaceFlingerVirtualDisplayId(sfDisplaysOutput)).toBe(
      "11529215048760749642"
    );
  });

  it("rejects ambiguous named virtual candidates without a unique-id bridge", () => {
    const output = [
      'DisplayDevice{9223372036854775811, virtual, "scrcpy"}',
      'DisplayDevice{9223372036854775812, virtual, "scrcpy"}'
    ].join("\n");

    expect(parseUniqueSurfaceFlingerVirtualDisplayId(output)).toBeUndefined();
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

    const displayOnlyArgs = buildVirtualDisplayScrcpyArgs(
      "phone-1",
      "com.example.app",
      { startApp: false }
    );
    expect(displayOnlyArgs).not.toContain("--start-app=com.example.app");
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

  it("fails closed instead of relabeling primary geometry for a missing secondary display", () => {
    expect(() =>
      parseDisplaySnapshotForId(
        "Physical size: 1080x2400",
        "Display: mDisplayId=0 init=1080x2400 420dpi cur=1080x2400 app=1080x2400 mCurrentRotation=0",
        2
      )
    ).toThrowError(
      expect.objectContaining({
        code: "OBSERVATION_FAILED",
        details: { displayId: 2, availableDisplayIds: [0] }
      })
    );
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
