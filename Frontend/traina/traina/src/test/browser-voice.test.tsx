import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useBrowserVoice } from "@/hooks/use-browser-voice";

class RecognitionFixture {
  static instance: RecognitionFixture;
  lang = "";
  continuous = false;
  interimResults = false;
  start = vi.fn();
  abort = vi.fn();
  onresult:
    | ((e: {
        resultIndex: number;
        results: { isFinal: boolean; 0: { transcript: string } }[];
      }) => void)
    | null = null;
  onerror: ((e: { error: string }) => void) | null = null;
  onend: (() => void) | null = null;
  onspeechstart: (() => void) | null = null;
  onspeechend: (() => void) | null = null;
  constructor() {
    RecognitionFixture.instance = this;
  }
}
describe("Browser dictation for Claude", () => {
  beforeEach(() => {
    Object.defineProperty(window, "webkitSpeechRecognition", {
      configurable: true,
      value: RecognitionFixture,
    });
  });
  afterEach(() => {
    Reflect.deleteProperty(window, "webkitSpeechRecognition");
  });
  it("does not turn on the microphone before the user chooses dictation", () => {
    const { result } = renderHook(() => useBrowserVoice(vi.fn(), false));
    expect(result.current.supported).toBe(true);
    expect(RecognitionFixture.instance.start).not.toHaveBeenCalled();
    act(() => result.current.toggle());
    expect(RecognitionFixture.instance.start).toHaveBeenCalledOnce();
    expect(RecognitionFixture.instance.lang).toBe("es-MX");
  });
  it("forwards only final words and drops words while off record", () => {
    const final = vi.fn();
    const { rerender } = renderHook(({ paused }) => useBrowserVoice(final, paused), {
      initialProps: { paused: false },
    });
    act(() =>
      RecognitionFixture.instance.onresult?.({
        resultIndex: 0,
        results: [{ isFinal: false, 0: { transcript: "Todavía" } }],
      }),
    );
    expect(final).not.toHaveBeenCalled();
    act(() =>
      RecognitionFixture.instance.onresult?.({
        resultIndex: 0,
        results: [{ isFinal: true, 0: { transcript: " Detengo el registro " } }],
      }),
    );
    expect(final).toHaveBeenCalledWith("Detengo el registro");
    rerender({ paused: true });
    act(() =>
      RecognitionFixture.instance.onresult?.({
        resultIndex: 0,
        results: [{ isFinal: true, 0: { transcript: "Privado" } }],
      }),
    );
    expect(final).toHaveBeenCalledTimes(1);
  });
  it("pauses for Claude speech and resumes the chosen dictation mode", () => {
    const { result, rerender } = renderHook(({ paused }) => useBrowserVoice(vi.fn(), paused), {
      initialProps: { paused: false },
    });
    act(() => result.current.toggle());
    rerender({ paused: true });
    expect(RecognitionFixture.instance.abort).toHaveBeenCalled();
    rerender({ paused: false });
    expect(RecognitionFixture.instance.start).toHaveBeenCalledTimes(2);
  });
  it("offers typing when microphone permission is denied", () => {
    const { result } = renderHook(() => useBrowserVoice(vi.fn(), false));
    act(() => RecognitionFixture.instance.onerror?.({ error: "not-allowed" }));
    expect(result.current.enabled).toBe(false);
    expect(result.current.error).toContain("escribe tu respuesta");
  });
});
