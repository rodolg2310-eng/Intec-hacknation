import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { LiveCapture } from "../lib/live-capture";
import type { StudioSession } from "../lib/api";
const { post } = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock("../lib/api", async importOriginal => ({ ...await importOriginal<object>(), post }));
describe("capture clock and privacy", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({ fillRect: vi.fn(), fillText: vi.fn(), drawImage: vi.fn() } as unknown as CanvasRenderingContext2D);
    vi.spyOn(HTMLCanvasElement.prototype, "toDataURL").mockReturnValue("data:image/jpeg;base64,test");
    vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
    vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
    post.mockImplementation((url: string) => url.endsWith("/frame") ? new Promise(() => {}) : Promise.resolve({}));
  });
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); post.mockReset(); });
  function runtime() {
    const capture = new LiveCapture("qa", { id: "session", phase: "capture", activeSeconds: 0, pipeline: { received: 2, nextSequence: 9 } } as StudioSession, vi.fn(), vi.fn(), vi.fn());
    return capture as unknown as { state: LiveCapture["state"]; tick: () => void };
  }
  it("samples every two seconds even while previous uploads and analysis remain unresolved", () => {
    const capture = runtime(); capture.state.recording = true;
    for (let i = 0; i < 6; i++) { vi.advanceTimersByTime(2000); capture.tick(); }
    const frames = post.mock.calls.filter(([url]) => url.endsWith("/frame"));
    expect(frames).toHaveLength(6);
    expect(frames.map(([, body]) => body.sequence)).toEqual([9, 10, 11, 12, 13, 14]);
    expect(frames.map(([, body]) => body.elapsedSeconds)).toEqual([2, 4, 6, 8, 10, 12]);
    expect(frames.every(([, body]) => body.cameraImage === "data:image/jpeg;base64,test")).toBe(true);
  });
  it("off record stops screenshots, microphone sending and the active recording clock", () => {
    const capture = runtime(); capture.state.recording = true; capture.state.offRecord = true;
    vi.advanceTimersByTime(600000); capture.tick();
    expect(post).not.toHaveBeenCalled(); expect(capture.state.activeSeconds).toBe(0); expect(capture.state.mediaSeconds).toBe(0);
  });
  it("continues sampling camera context during debrief without adding screen moments", () => {
    const capture = new LiveCapture("qa", { id: "session", phase: "debrief", activeSeconds: 120, mediaSeconds: 150 } as StudioSession, vi.fn(), vi.fn(), vi.fn()) as unknown as { state: LiveCapture["state"]; tick: () => void };
    capture.state.recording = true;
    for (let i = 0; i < 3; i++) { vi.advanceTimersByTime(2000); capture.tick(); }
    const cameraFrames = post.mock.calls.filter(([url]) => url.endsWith("/camera-frame"));
    expect(cameraFrames.map(([, body]) => body.sequence)).toEqual([0, 1, 2]);
    expect(cameraFrames.map(([, body]) => body.elapsedSeconds)).toEqual([152, 154, 156]);
    expect(post.mock.calls.filter(([url]) => url.endsWith("/frame"))).toHaveLength(0);
    expect(capture.state.activeSeconds).toBe(120);
  });
  it("off record disables camera and microphone tracks and disposal releases them", async () => {
    const capture = new LiveCapture("qa", { id: "session", phase: "capture" } as StudioSession, vi.fn(), vi.fn(), vi.fn());
    const video = { enabled: true, stop: vi.fn() }, audio = { enabled: true, stop: vi.fn() };
    Object.assign(capture, { devices: { getTracks: () => [video, audio] } });
    capture.state.recording = true;
    await capture.pause(true);
    expect(video.enabled).toBe(false); expect(audio.enabled).toBe(false);
    expect(capture.state.camera).toBe("Paused"); expect(capture.state.microphone).toBe("Paused");
    expect(video.stop).not.toHaveBeenCalled(); expect(audio.stop).not.toHaveBeenCalled();
    await capture.dispose();
    expect(video.stop).toHaveBeenCalledOnce(); expect(audio.stop).toHaveBeenCalledOnce();
  });
  it("resumes both devices without replacing an existing live stream", async () => {
    const capture = new LiveCapture("qa", { id: "session", phase: "debrief" } as StudioSession, vi.fn(), vi.fn(), vi.fn());
    const video = { enabled: false, readyState: "live" }, audio = { enabled: false, readyState: "live" };
    Object.assign(capture, { devices: { getTracks: () => [video, audio] }, context: { state: "running" }, mix: {}, pcm: {} });
    await capture.connectDevices();
    expect(video.enabled).toBe(true); expect(audio.enabled).toBe(true);
    expect(capture.state.camera).toBe("Live"); expect(capture.state.microphone).toBe("Live");
  });
});
