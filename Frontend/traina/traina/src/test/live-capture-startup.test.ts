import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { LiveCapture } from "../lib/live-capture";
import type { StudioSession } from "../lib/api";

const { post } = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock("../lib/api", async importOriginal => ({ ...await importOriginal<object>(), post }));

type StartupRuntime = Pick<LiveCapture, "state" | "connectDevices" | "sandbox" | "begin" | "canLeave" | "dispose"> & { connectScribe: () => Promise<void> };
let order: string[];
let workletFailures: number;
class Track {
  readyState = "live"; enabled = true; muted = false;
  onended: (() => void) | null = null; onmute: (() => void) | null = null;
  constructor(readonly kind: "video" | "audio") {}
  stop = vi.fn(() => { this.readyState = "ended"; });
}
class Stream {
  constructor(readonly tracks: Track[] = []) {}
  getTracks() { return this.tracks; }
  getAudioTracks() { return this.tracks.filter(t => t.kind === "audio"); }
  getVideoTracks() { return this.tracks.filter(t => t.kind === "video"); }
}
function node() { return { connect: vi.fn((target: unknown) => target) }; }
class Context {
  static instances: Context[] = [];
  state = "running"; destination = node();
  audioWorklet = { addModule: vi.fn(async () => { order.push("worklet"); if (workletFailures-- > 0) throw new Error("Worklet failed to load"); }) };
  constructor() { Context.instances.push(this); }
  resume = vi.fn(async () => { order.push("audio-ready"); });
  close = vi.fn(async () => { this.state = "closed"; });
  createMediaStreamDestination() { return { ...node(), stream: new Stream([new Track("audio")]) }; }
  createMediaStreamSource() { return node(); }
  createGain() { return { ...node(), gain: { value: 1 } }; }
}
class Worklet {
  port = { onmessage: null }; connect = vi.fn((target: unknown) => target);
}
class Socket {
  static OPEN = 1; static instances: Socket[] = [];
  readyState = 0;
  onopen: (() => void) | null = null; onerror: (() => void) | null = null; onclose: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  send = vi.fn();
  close = vi.fn(() => { this.readyState = 3; this.onclose?.(); });
  constructor(readonly url: string) { Socket.instances.push(this); }
  open() { this.readyState = Socket.OPEN; this.onopen?.(); }
  message(event: Record<string, unknown>) { this.onmessage?.({ data: JSON.stringify(event) }); }
}
class Recorder {
  static instances: Recorder[] = [];
  static isTypeSupported() { return true; }
  state = "inactive";
  onstop: (() => void) | null = null; onerror: (() => void) | null = null;
  ondataavailable: ((event: { data: Blob }) => void) | null = null;
  constructor(readonly stream: Stream, readonly options: { mimeType: string }) { Recorder.instances.push(this); }
  start() { this.state = "recording"; order.push(this.options.mimeType.startsWith("video") ? "video-started" : "audio-started"); }
  requestData() {}
  stop() { this.state = "inactive"; queueMicrotask(() => this.onstop?.()); }
}

describe("recording startup and recovery", () => {
  let originalCaptureStream: PropertyDescriptor | undefined;
  beforeEach(() => {
    vi.useFakeTimers(); order = []; workletFailures = 0; Context.instances = []; Socket.instances = []; Recorder.instances = [];
    vi.stubGlobal("MediaStream", Stream); vi.stubGlobal("AudioContext", Context); vi.stubGlobal("AudioWorkletNode", Worklet); vi.stubGlobal("WebSocket", Socket); vi.stubGlobal("MediaRecorder", Recorder);
    vi.stubGlobal("navigator", { mediaDevices: { getUserMedia: vi.fn(async () => { order.push("devices"); return new Stream([new Track("video"), new Track("audio")]); }) } });
    originalCaptureStream = Object.getOwnPropertyDescriptor(HTMLCanvasElement.prototype, "captureStream");
    Object.defineProperty(HTMLCanvasElement.prototype, "captureStream", { configurable: true, value: vi.fn(() => new Stream([new Track("video")])) });
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({ fillRect: vi.fn(), fillText: vi.fn(), drawImage: vi.fn() } as unknown as CanvasRenderingContext2D);
    vi.spyOn(HTMLCanvasElement.prototype, "toDataURL").mockReturnValue("data:image/jpeg;base64,test");
    vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
    vi.spyOn(HTMLMediaElement.prototype, "play").mockImplementation(async () => { order.push("preview-ready"); });
    post.mockImplementation(async (url: string) => { if (url.endsWith("/scribe-token")) { order.push("scribe-token"); return { token: "test-token" }; } if (url.endsWith("/recordings/open")) order.push("recording-open"); if (url.endsWith("/heartbeat")) order.push("heartbeat"); return {}; });
  });
  afterEach(() => {
    vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); post.mockReset();
    if (originalCaptureStream) Object.defineProperty(HTMLCanvasElement.prototype, "captureStream", originalCaptureStream); else delete (HTMLCanvasElement.prototype as unknown as { captureStream?: unknown }).captureStream;
  });
  function runtime() { return new LiveCapture("qa", { id: "session", phase: "capture", activeSeconds: 0 } as StudioSession, vi.fn(), vi.fn(), vi.fn()) as unknown as StartupRuntime; }
  async function settle() { for (let i = 0; i < 24; i++) await Promise.resolve(); }
  function socket() { expect(Socket.instances).toHaveLength(1); return Socket.instances[0]!; }

  it("waits for the accepted Scribe session rather than treating the open socket as ready", async () => {
    const capture = runtime(); const connected = capture.connectScribe(); let ready = false; void connected.then(() => { ready = true; });
    await settle(); socket().open(); await settle(); expect(ready).toBe(false); expect(capture.state.connection).toBe("Connecting live transcription…");
    socket().message({ message_type: "session_started", session_id: "scribe-qa" }); await connected;
    expect(ready).toBe(true); expect(capture.state.connection).toBe("Listening / language detected automatically");
  });
  it("rejects a socket closed before it opens so Start can be retried", async () => {
    const capture = runtime(); const connected = capture.connectScribe(); const rejected = expect(connected).rejects.toThrow("disconnected");
    await settle(); socket().close(); await rejected; expect(capture.state.connection).toBe("Transcription unavailable");
  });
  it.each(["auth_error", "quota_exceeded", "input_error", "rate_limited"])("rejects %s startup errors without waiting for the timeout", async message_type => {
    const capture = runtime(); const connected = capture.connectScribe(); const rejected = expect(connected).rejects.toThrow(message_type);
    await settle(); socket().open(); socket().message({ message_type }); await rejected; expect(socket().close).toHaveBeenCalledOnce();
  });
  it("rejects a session_started response without a session identifier", async () => {
    const capture = runtime(); const connected = capture.connectScribe(); const rejected = expect(connected).rejects.toThrow("did not initialize");
    await settle(); socket().message({ message_type: "session_started" }); await rejected;
  });
  it("bounds the Scribe session handshake even when the socket remains open", async () => {
    const capture = runtime(); const connected = capture.connectScribe(); const rejected = expect(connected).rejects.toThrow("12 seconds");
    await settle(); socket().open(); await vi.advanceTimersByTimeAsync(12000); await rejected; expect(socket().close).toHaveBeenCalledOnce();
  });
  it("bounds and aborts a transcription token request that never returns", async () => {
    post.mockImplementation(() => new Promise(() => {}));
    const capture = runtime(); const connected = capture.connectScribe(); const rejected = expect(connected).rejects.toThrow("server did not respond");
    await vi.advanceTimersByTimeAsync(15000); await rejected;
    expect(Socket.instances).toHaveLength(0); expect(post.mock.calls[0]![2].signal.aborted).toBe(true);
  });
  it("rejects a missing token before opening a Scribe socket", async () => {
    post.mockResolvedValue({});
    await expect(runtime().connectScribe()).rejects.toThrow("did not return a transcription token"); expect(Socket.instances).toHaveLength(0);
  });
  it("cleans up a partially initialized worklet and creates a complete audio pipeline on retry", async () => {
    const capture = runtime(); workletFailures = 1;
    await expect(capture.connectDevices()).rejects.toThrow("Worklet failed");
    const firstStream = await vi.mocked(navigator.mediaDevices.getUserMedia).mock.results[0]!.value as unknown as Stream;
    expect(firstStream.getTracks().every(t => t.stop.mock.calls.length === 1)).toBe(true); expect(Context.instances[0]!.close).toHaveBeenCalledOnce(); expect(capture.state.camera).toBe("Not connected");
    await capture.connectDevices(); expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalledTimes(2); expect(Context.instances).toHaveLength(2); expect(capture.state.camera).toBe("Live"); expect(capture.state.microphone).toBe("Live");
    await capture.connectDevices(); expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalledTimes(2);
  });
  it("starts after explicitly connecting devices and creates only one recording during concurrent Start calls", async () => {
    const capture = runtime(); await capture.sandbox(); await capture.connectDevices(); expect(capture.state.recording).toBe(false);
    const first = capture.begin(), second = capture.begin(); await settle(); expect(Recorder.instances).toHaveLength(0); expect(capture.canLeave()).toBe(false);
    socket().open(); socket().message({ message_type: "session_started", session_id: "scribe-qa" }); await Promise.all([first, second]);
    expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalledOnce(); expect(Socket.instances).toHaveLength(1); expect(Recorder.instances).toHaveLength(2); expect(capture.state.recording).toBe(true);
    expect(order.indexOf("worklet")).toBeLessThan(order.indexOf("scribe-token")); expect(order.indexOf("scribe-token")).toBeLessThan(order.indexOf("video-started")); expect(order.indexOf("video-started")).toBeLessThan(order.indexOf("audio-started")); expect(order.at(-1)).toBe("heartbeat");
    const stopped = capture.dispose(); await vi.advanceTimersByTimeAsync(1800); await stopped;
  });
  it("starts with a live device whose browser track is temporarily marked muted", async () => {
    const video = new Track("video"); video.muted = true;
    vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue(new Stream([video, new Track("audio")]) as unknown as MediaStream);
    const capture = runtime(); await capture.sandbox(); const started = capture.begin(); await settle();
    socket().open(); socket().message({ message_type: "session_started", session_id: "scribe-qa" }); await settle();
    await started; expect(video.muted).toBe(true);
    expect(capture.state.recording).toBe(true); expect(Recorder.instances).toHaveLength(2);
    const stopped = capture.dispose(); await vi.advanceTimersByTimeAsync(1800); await stopped;
  });
});
