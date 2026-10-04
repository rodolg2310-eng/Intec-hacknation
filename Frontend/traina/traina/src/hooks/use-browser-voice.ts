import { useCallback, useEffect, useRef, useState } from "react";

type Recognition = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult:
    | ((event: {
        resultIndex: number;
        results: { isFinal: boolean; 0: { transcript: string } }[];
      }) => void)
    | null;
  onerror: ((event: { error: string }) => void) | null;
  onend: (() => void) | null;
  onspeechstart: (() => void) | null;
  onspeechend: (() => void) | null;
  start: () => void;
  abort: () => void;
};
type SpeechWindow = Window & {
  SpeechRecognition?: new () => Recognition;
  webkitSpeechRecognition?: new () => Recognition;
};

/** Dictado del navegador; Claude recibe el texto. ElevenLabs solo recibe respuestas para TTS. */
export function useBrowserVoice(onFinal: (text: string) => void, paused: boolean) {
  const [supported, setSupported] = useState(false);
  const [enabled, setEnabled] = useState(false);
  const [listening, setListening] = useState(false);
  const [speaking, setSpeaking] = useState(false);
  const [interim, setInterim] = useState("");
  const [error, setError] = useState("");
  const rec = useRef<Recognition | null>(null);
  const wanted = useRef(false),
    pausedRef = useRef(paused),
    finalRef = useRef(onFinal);
  const restart = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    finalRef.current = onFinal;
  }, [onFinal]);
  useEffect(() => {
    const win = window as SpeechWindow;
    const Constructor = win.SpeechRecognition || win.webkitSpeechRecognition;
    setSupported(!!Constructor);
    if (!Constructor) return;
    const recognition = new Constructor();
    recognition.lang = "es-MX";
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.onresult = (event) => {
      let temporary = "";
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i];
        if (!result) continue;
        if (result.isFinal) {
          if (!pausedRef.current) finalRef.current(result[0].transcript.trim());
        } else temporary += result[0].transcript;
      }
      setInterim(temporary);
    };
    recognition.onspeechstart = () => setSpeaking(true);
    recognition.onspeechend = () => setSpeaking(false);
    recognition.onerror = (event) => {
      if (event.error === "aborted" || event.error === "no-speech") return;
      wanted.current = false;
      setEnabled(false);
      setError(
        event.error === "not-allowed"
          ? "Permite el micrófono en el navegador o escribe tu respuesta."
          : "El dictado no está disponible ahora. Puedes escribir tu respuesta.",
      );
    };
    recognition.onend = () => {
      setListening(false);
      setSpeaking(false);
      setInterim("");
      if (wanted.current && !pausedRef.current)
        restart.current = setTimeout(() => {
          try {
            recognition.start();
            setListening(true);
          } catch {
            /* An existing recognition may still be ending. */
          }
        }, 350);
    };
    rec.current = recognition;
    return () => {
      wanted.current = false;
      if (restart.current) clearTimeout(restart.current);
      recognition.onend = null;
      recognition.abort();
      rec.current = null;
    };
  }, []);
  useEffect(() => {
    pausedRef.current = paused;
    if (paused) {
      rec.current?.abort();
      setListening(false);
    } else if (wanted.current) {
      try {
        rec.current?.start();
        setListening(true);
      } catch {
        /* Already listening. */
      }
    }
  }, [paused]);
  const toggle = useCallback(() => {
    if (!rec.current) return;
    wanted.current = !wanted.current;
    setEnabled(wanted.current);
    setError("");
    if (wanted.current && !pausedRef.current) {
      try {
        rec.current.start();
        setListening(true);
      } catch {
        /* Already listening. */
      }
    } else {
      rec.current.abort();
      setListening(false);
    }
  }, []);
  return { supported, enabled, listening, speaking, interim, error, toggle };
}
