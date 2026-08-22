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
  /** UI Automator's shell dump has no display selector on supported Android
   * versions; displayId is accepted so callers cannot accidentally omit the
   * binding, but the implementation documents the primary-display limitation.
   */
  dumpUiAutomatorXml(serial: string, displayId?: number): Promise<string>;
  captureScreenshot(serial: string, displayId?: number): Promise<Uint8Array>;
  launchApp(serial: string, packageName: string): Promise<void>;
  tap(serial: string, x: number, y: number, displayId?: number): Promise<void>;
  swipe(serial: string, gesture: SwipeGesture, displayId?: number): Promise<void>;
  typeText(serial: string, text: string, displayId?: number): Promise<void>;
  keypress(serial: string, key: Keypress, displayId?: number): Promise<void>;
}
