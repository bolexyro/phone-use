import type { ViewerGeometry } from "./coordinate-mapping.js";

export interface DipWorkArea extends ViewerGeometry {}

export function fitDipRectToWorkArea(
  requested: ViewerGeometry,
  workArea: DipWorkArea,
  margin = 8
): ViewerGeometry {
  const safeMargin = Math.max(0, margin);
  const maxX = workArea.x + Math.max(1, workArea.width - safeMargin);
  const maxY = workArea.y + Math.max(1, workArea.height - safeMargin);
  const x = Math.min(Math.max(requested.x, workArea.x + safeMargin), maxX - 1);
  const y = Math.min(Math.max(requested.y, workArea.y + safeMargin), maxY - 1);
  const availableWidth = Math.max(1, maxX - x);
  const availableHeight = Math.max(1, maxY - y);
  const scale = Math.min(
    1,
    availableWidth / requested.width,
    availableHeight / requested.height
  );

  return {
    x,
    y,
    width: Math.max(1, Math.floor(requested.width * scale)),
    height: Math.max(1, Math.floor(requested.height * scale))
  };
}
