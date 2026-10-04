import { describe, expect, it, vi } from "vitest";
import { clampPoint, drawMasks, normalizeMask } from "@/lib/capture-privacy";
describe("Privacy for screenshots and recorded canvas", () => {
  it("normalizes reverse dragging and constrains coordinates", () => { expect(clampPoint(-1, 2)).toEqual({ x: 0, y: 1 }); expect(normalizeMask({ x: .8, y: .7 }, { x: .2, y: .1 })).toEqual({ x: .2, y: .1, width: expect.closeTo(.6), height: expect.closeTo(.6) }); });
  it("draws opaque masks into the outgoing canvas", () => { const fillRect = vi.fn(), c = { fillRect, fillStyle: "" } as unknown as CanvasRenderingContext2D; drawMasks(c, 1000, 500, [{ x: .2, y: .1, width: .3, height: .4 }]); expect(c.fillStyle).toBe("#111827"); expect(fillRect).toHaveBeenCalledWith(200, 50, 300, 200); });
});
