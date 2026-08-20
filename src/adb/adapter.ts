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
  getForeground(serial: string): Promise<ForegroundState>;
  getDisplay(serial: string): Promise<DisplaySnapshot>;
  dumpUiAutomatorXml(serial: string): Promise<string>;
  captureScreenshot(serial: string): Promise<Uint8Array>;
  launchApp(serial: string, packageName: string): Promise<void>;
  tap(serial: string, x: number, y: number): Promise<void>;
  swipe(serial: string, gesture: SwipeGesture): Promise<void>;
  typeText(serial: string, text: string): Promise<void>;
  keypress(serial: string, key: Keypress): Promise<void>;
}
