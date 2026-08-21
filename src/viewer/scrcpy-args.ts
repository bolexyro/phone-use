import type { ViewerConfig } from "./config.js";

export function buildScrcpyArgs(
  serial: string,
  config: ViewerConfig
): readonly string[] {
  return [
    "-s",
    serial,
    "--window-title=Phone Control scrcpy",
    "--render-fit=stretched",
    `--window-x=${config.x}`,
    `--window-y=${config.y}`,
    `--window-width=${config.width}`,
    `--window-height=${config.height}`,
    "--keyboard=sdk",
    "--mouse=sdk",
    "--no-audio"
  ];
}
