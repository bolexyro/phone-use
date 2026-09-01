import type {
  DeviceInfo,
  DisplaySnapshot,
  ForegroundState,
  Keypress
} from "../types.js";

export interface SwipeGesture {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  durationMs: number;
}

/** A validated display-relative point for the fixed tap batch adapter. */
export interface TapPoint {
  x: number;
  y: number;
}

/** Reports the number of taps whose transport calls completed. */
export interface TapBatchResult {
  completed: number;
}

/** Internal lifecycle hooks for keeping observers aligned with tap dispatch. */
export interface TapBatchHooks {
  beforeTap?: (index: number, point: TapPoint) => Promise<void>;
}

/**
 * The only ADB surface exposed to the phone-control service.
 * There is deliberately no public method accepting arbitrary command args.
 */
export interface FixedAdbAdapter {
  listDevices(): Promise<readonly DeviceInfo[]>;
  getApiLevel(serial: string): Promise<number>;
  listDisplays(
    serial: string
  ): Promise<
    readonly {
      displayId: number;
      width: number;
      height: number;
      rotation: number;
    }[]
  >;
  getForeground(serial: string, displayId?: number): Promise<ForegroundState>;
  getDisplay(serial: string, displayId?: number): Promise<DisplaySnapshot>;
  /**
   * UI Automator's shell dump has no display selector on supported Android
   * versions. Implementations must reject displayId > 0 rather than returning
   * the focused/default hierarchy under a secondary-display binding.
   */
  dumpUiAutomatorXml(serial: string, displayId?: number): Promise<string>;
  captureScreenshot(serial: string, displayId?: number): Promise<Uint8Array>;
  launchApp(serial: string, packageName: string): Promise<void>;
  /** Launch an allowlisted package on one known display using fixed task flags. */
  launchAppOnDisplay(
    serial: string,
    packageName: string,
    displayId: number,
    options?: { multipleTask?: boolean }
  ): Promise<void>;
  tap(serial: string, x: number, y: number, displayId?: number): Promise<void>;
  /**
   * Execute a bounded, already-validated batch of display-relative taps.
   * This is intentionally typed; it is not a general ADB or shell escape
   * hatch. A rejected operation may have completed a prefix of the batch.
   */
  tapBatch(
    serial: string,
    points: readonly TapPoint[],
    displayId?: number,
    hooks?: TapBatchHooks
  ): Promise<TapBatchResult>;
  swipe(serial: string, gesture: SwipeGesture, displayId?: number): Promise<void>;
  typeText(serial: string, text: string, displayId?: number): Promise<void>;
  keypress(serial: string, key: Keypress, displayId?: number): Promise<void>;
}
