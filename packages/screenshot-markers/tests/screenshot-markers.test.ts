import { Buffer } from "node:buffer";

import { describe, expect, it } from "vitest";
import { PNG } from "pngjs";

import {
  POINTER_ARROW_FILL,
  SCREENSHOT_MARKER_GUIDANCE,
  ScreenshotMarkerPresenter,
  annotateScreenshotPng,
  cropScreenshotPng,
  type ScreenshotMarkerObservation
} from "../src/index.js";

function createPng(width: number, height: number, color: [number, number, number]): Uint8Array {
  const png = new PNG({ width, height });
  for (let index = 0; index < png.data.length; index += 4) {
    png.data[index] = color[0];
    png.data[index + 1] = color[1];
    png.data[index + 2] = color[2];
    png.data[index + 3] = 255;
  }
  return Uint8Array.from(PNG.sync.write(png));
}

function observation(
  displayId: number,
  observationId: string,
  packageName = "com.example.app"
): ScreenshotMarkerObservation {
  return {
    observationId,
    displayId,
    packageName,
    rotation: 0,
    screenshotDimensions: { width: 120, height: 160 }
  };
}

describe("ScreenshotMarkerPresenter", () => {
  it("keeps a calibration anchor stable until a successful tap updates it", () => {
    const screenshot = createPng(120, 160, [20, 20, 20]);
    const presenter = new ScreenshotMarkerPresenter({
      chooseAnchor: () => ({ x: 18, y: 26 })
    });
    const first = presenter.render(screenshot, observation(0, "obs-1"));
    const second = presenter.render(screenshot, observation(0, "obs-2"));
    const tapped = presenter.render(screenshot, observation(0, "obs-3"), {
      lastTap: { x: 70, y: 90 }
    });

    expect(first.marker).toEqual({
      kind: "calibration",
      x: 18,
      y: 26,
      coordinateSpace: "display"
    });
    expect(second.marker).toEqual(first.marker);
    expect(tapped.marker).toEqual({
      kind: "last_tap",
      x: 70,
      y: 90,
      coordinateSpace: "display"
    });
  });

  it("isolates displays and resets a closed display", () => {
    const screenshot = createPng(120, 160, [20, 20, 20]);
    let next = 0;
    const presenter = new ScreenshotMarkerPresenter({
      chooseAnchor: () => ({ x: 10 + next++, y: 20 })
    });
    const first = presenter.render(screenshot, observation(1, "one"));
    const second = presenter.render(screenshot, observation(2, "two"));
    presenter.reset(1);
    const reset = presenter.render(screenshot, observation(1, "one-again"));

    expect(first.marker.x).toBe(10);
    expect(second.marker.x).toBe(11);
    expect(reset.marker.x).toBe(12);
  });

  it("returns an annotated copy with unchanged dimensions and leaves input bytes untouched", () => {
    const screenshot = createPng(120, 160, [240, 240, 240]);
    const original = Uint8Array.from(screenshot);
    const marker = {
      kind: "last_tap" as const,
      x: 40,
      y: 50,
      coordinateSpace: "display" as const
    };
    const annotated = annotateScreenshotPng(
      screenshot,
      { width: 120, height: 160 },
      marker
    );
    const decoded = PNG.sync.read(Buffer.from(annotated));

    expect(screenshot).toEqual(original);
    expect(decoded.width).toBe(120);
    expect(decoded.height).toBe(160);
    expect(annotated).not.toEqual(original);
    expect([...decoded.data].some((value) => value === 43)).toBe(true);
  });

  it("uses the shared prompt guidance", () => {
    expect(SCREENSHOT_MARKER_GUIDANCE).toContain("not part of the Android app UI");
    expect(SCREENSHOT_MARKER_GUIDANCE).toContain("last executed tap");
    expect(SCREENSHOT_MARKER_GUIDANCE).toContain("before-tap evidence crop");
    expect(SCREENSHOT_MARKER_GUIDANCE).toContain("only kind, x, y, and coordinateSpace");
    expect(POINTER_ARROW_FILL).toBe("#2b8cdb");
  });

  it("returns a small crop with display-coordinate bounds without mutating the source", () => {
    const screenshot = createPng(640, 480, [30, 30, 30]);
    const original = Uint8Array.from(screenshot);
    const annotated = annotateScreenshotPng(
      screenshot,
      { width: 640, height: 480 },
      { kind: "last_tap", x: 400, y: 280, coordinateSpace: "display" }
    );
    const crop = cropScreenshotPng(
      annotated,
      { width: 640, height: 480 },
      { x: 400, y: 280 },
      320
    );
    const decoded = PNG.sync.read(Buffer.from(crop.screenshot));

    expect(screenshot).toEqual(original);
    expect(crop.bounds).toEqual({ left: 240, top: 120, width: 320, height: 320 });
    expect(decoded.width).toBe(320);
    expect(decoded.height).toBe(320);
    expect(crop.screenshot).not.toEqual(screenshot);
  });

  it("clamps evidence crops at display edges and rejects invalid points", () => {
    const screenshot = createPng(120, 160, [30, 30, 30]);
    expect(
      cropScreenshotPng(screenshot, { width: 120, height: 160 }, { x: 0, y: 0 }, 80).bounds
    ).toEqual({ left: 0, top: 0, width: 80, height: 80 });
    expect(() =>
      cropScreenshotPng(screenshot, { width: 120, height: 160 }, { x: 120, y: 0 }, 80)
    ).toThrow("outside");
  });
});
