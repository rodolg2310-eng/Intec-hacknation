export type Mask = { x: number; y: number; width: number; height: number };
export function drawMasks(context: CanvasRenderingContext2D, width: number, height: number, masks: Mask[]) {
  context.fillStyle = "#111827";
  for (const mask of masks) context.fillRect(mask.x * width, mask.y * height, mask.width * width, mask.height * height);
}
export function clampPoint(x: number, y: number) { return { x: Math.max(0, Math.min(1, x)), y: Math.max(0, Math.min(1, y)) }; }
export function normalizeMask(a: { x: number; y: number }, b: { x: number; y: number }): Mask {
  return { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), width: Math.abs(b.x - a.x), height: Math.abs(b.y - a.y) };
}
