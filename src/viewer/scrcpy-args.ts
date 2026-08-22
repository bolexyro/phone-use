import type { ViewerConfig } from "./config.js";

export interface ScrcpyExtraOptions {
  displayId?: number;
  startApp?: string;
  newDisplay?: string | boolean;
  mouseMode?: "disabled" | "sdk";
  noDecorations?: boolean;
  windowTitle?: string;
}

export function buildScrcpyArgs(
  serial: string,
  config: ViewerConfig,
  extra: ScrcpyExtraOptions = {}
): readonly string[] {
  const args = [
    "-s",
    serial,
    `--window-title=${extra.windowTitle ?? "Phone Control scrcpy"}`,
    "--render-fit=stretched",
    `--window-x=${config.x}`,
    `--window-y=${config.y}`,
    `--window-width=${config.width}`,
    `--window-height=${config.height}`
  ];

  if (extra.newDisplay) {
    if (typeof extra.newDisplay === "string" && extra.newDisplay.length > 0) {
      args.push(`--new-display=${extra.newDisplay}`);
    } else {
      args.push("--new-display");
    }
  } else if (extra.displayId !== undefined && extra.displayId > 0) {
    args.push(`--display-id=${extra.displayId}`);
  }

  if (extra.startApp) {
    args.push(`--start-app=${extra.startApp}`);
  }

  if (extra.noDecorations) {
    args.push("--no-vd-system-decorations");
  }

  const mouseMode = extra.mouseMode ?? "sdk";
  args.push("--keyboard=sdk");
  args.push(`--mouse=${mouseMode}`);
  args.push("--no-audio");

  return args;
}
