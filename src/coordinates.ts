import { PhoneControlError } from "./errors.js";
import type {
  DisplayInfo,
  ScrollAmount,
  ScrollDirection
} from "./types.js";
import type { SwipeGesture } from "./adb/adapter.js";

export function assertCoordinateInBounds(
  x: number,
  y: number,
  dimensions: DisplayInfo
): void {
  if (
    !Number.isInteger(x) ||
    !Number.isInteger(y) ||
    x < 0 ||
    y < 0 ||
    x >= dimensions.width ||
    y >= dimensions.height
  ) {
    throw new PhoneControlError(
      "INVALID_COORDINATE",
      `Coordinate (${x}, ${y}) is outside the ${dimensions.width}x${dimensions.height} display.`,
      { x, y, display: dimensions }
    );
  }
}

const SCROLL_DISTANCE_RATIO: Record<ScrollAmount, number> = {
  small: 0.25,
  medium: 0.45,
  large: 0.7
};

const SCROLL_DURATION_MS: Record<ScrollAmount, number> = {
  small: 220,
  medium: 350,
  large: 500
};

export function calculateScrollGesture(
  display: DisplayInfo,
  direction: ScrollDirection,
  amount: ScrollAmount
): SwipeGesture {
  const distance = Math.max(
    1,
    Math.floor(Math.min(display.width, display.height) * SCROLL_DISTANCE_RATIO[amount])
  );
  const centerX = Math.floor(display.width / 2);
  const centerY = Math.floor(display.height / 2);
  let x1 = centerX;
  let y1 = centerY;
  let x2 = centerX;
  let y2 = centerY;

  if (direction === "up") {
    y1 = centerY + Math.floor(distance / 2);
    y2 = centerY - Math.ceil(distance / 2);
  } else if (direction === "down") {
    y1 = centerY - Math.floor(distance / 2);
    y2 = centerY + Math.ceil(distance / 2);
  } else if (direction === "left") {
    x1 = centerX + Math.floor(distance / 2);
    x2 = centerX - Math.ceil(distance / 2);
  } else {
    x1 = centerX - Math.floor(distance / 2);
    x2 = centerX + Math.ceil(distance / 2);
  }

  assertCoordinateInBounds(x1, y1, display);
  assertCoordinateInBounds(x2, y2, display);
  return { x1, y1, x2, y2, durationMs: SCROLL_DURATION_MS[amount] };
}
