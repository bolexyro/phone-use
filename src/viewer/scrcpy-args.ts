import type { ViewerConfig } from "./config.js";

export function buildScrcpyArgs(
  serial: string,
  config: ViewerConfig
): readonly string[] {
  return [
    "-s",
    serial,
    "--window-title=Phone Control scrcpy",
    "--window-borderless",
    "--render-fit=stretched",
    `--window-x=${config.x}`,
    `--window-y=${config.y}`,
    `--window-width=${config.width}`,
    `--window-height=${config.height}`,
    "--always-on-top",
    "--mouse=disabled",
    "--no-audio"
  ];
}
