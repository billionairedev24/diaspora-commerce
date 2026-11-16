'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

import { DICTATION_FALLBACK_LANG, speechRecognitionCtor, type SpeechRecognitionLike } from './speech';

export type DictationStatus = 'checking' | 'idle' | 'listening' | 'unsupported' | 'denied';
/** Why the mic is unavailable or just stopped; the caller maps each to one translated line. */
export type DictationReason = 'unsupported' | 'denied' | 'error' | 'noSpeech' | 'offline';

export interface UseDictationOptions {
  /** BCP 47 language for recognition, e.g. `fr-FR`. Falls back to `en-US` once if the browser rejects it. */
  lang: string;
  /** Receives the whole transcript of the current dictation (final plus interim) each time it changes. */
  onTranscript: (transcript: string) => void;
  /** Stop after this long without a new result. Default 4s. */
  silenceMs?: number;
}

export interface Dictation {
  status: DictationStatus;
  listening: boolean;
  /** Mic is unusable (unsupported browser or permission denied). */
  unavailable: boolean;
  reason: DictationReason | null;
  start: () => void;
  stop: () => void;
  toggle: () => void;
}

/**
 * Dictation with the browser's SpeechRecognition (the pattern of ChatGPT, Claude and Cursor): tap to start, a live transcript,
 * tap again or a pause to stop. Never sends anything on its own. Audio goes only to the browser (some browsers use their
 * vendor's speech service); AfroTransact has no audio endpoint. Feature-detected after mount (no hydration mismatch).
 */
export function useDictation({ lang, onTranscript, silenceMs = 4000 }: UseDictationOptions): Dictation {
  const [status, setStatus] = useState<DictationStatus>('checking');
  const [reason, setReason] = useState<DictationReason | null>(null);
  const rec = useRef<SpeechRecognitionLike | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const transcript = useRef(onTranscript);
  const retried = useRef(false);
  const heard = useRef(false);
  transcript.current = onTranscript;

  useEffect(() => {
    if (speechRecognitionCtor() === null) {
      setStatus('unsupported');
      setReason('unsupported');
    } else {
      setStatus('idle');
    }
  }, []);

  const clearTimer = () => {
    if (timer.current !== null) clearTimeout(timer.current);
    timer.current = null;
  };

  const release = useCallback(() => {
    clearTimer();
    const r = rec.current;
    rec.current = null;
    if (r) {
      r.onresult = null;
      r.onerror = null;
      r.onend = null;
      try {
        r.abort();
      } catch {
        /* already stopped */
      }
    }
  }, []);

  useEffect(() => release, [release]);

  const begin = useCallback(
    (language: string) => {
      const Ctor = speechRecognitionCtor();
      if (Ctor === null) return;
      const r = new Ctor();
      r.lang = language;
      r.continuous = true;
      r.interimResults = true;
      r.maxAlternatives = 1;
      const armSilence = () => {
        clearTimer();
        timer.current = setTimeout(() => r.stop(), silenceMs);
      };
      r.onresult = (e) => {
        heard.current = true;
        let text = '';
        for (let i = 0; i < e.results.length; i += 1) text += e.results[i]?.[0]?.transcript ?? '';
        transcript.current(text.replace(/\s+/g, ' ').trim());
        armSilence();
      };
      r.onerror = (e) => {
        const code = e.error;
        if (code === 'language-not-supported' && !retried.current && language !== DICTATION_FALLBACK_LANG) {
          retried.current = true;
          release();
          begin(DICTATION_FALLBACK_LANG);
          return;
        }
        if (code === 'not-allowed' || code === 'service-not-allowed') {
          release();
          setStatus('denied');
          setReason('denied');
          return;
        }
        if (code === 'aborted') return;
        release();
        setStatus('idle');
        setReason(code === 'no-speech' ? 'noSpeech' : code === 'network' ? 'offline' : 'error');
      };
      r.onend = () => {
        // Natural end (silence, or the browser's own limit): leave the text for the user to edit and send.
        const wasHeard = heard.current;
        release();
        setStatus((s) => (s === 'listening' ? 'idle' : s));
        if (!wasHeard) setReason((x) => x ?? 'noSpeech');
      };
      rec.current = r;
      try {
        r.start();
        setStatus('listening');
        armSilence();
      } catch {
        release();
        setStatus('idle');
        setReason('error');
      }
    },
    [release, silenceMs]
  );

  const start = useCallback(() => {
    if (rec.current !== null || speechRecognitionCtor() === null) return;
    retried.current = false;
    heard.current = false;
    setReason(null);
    begin(lang);
  }, [begin, lang]);

  const stop = useCallback(() => {
    const r = rec.current;
    if (r === null) return;
    clearTimer();
    try {
      r.stop();
    } catch {
      release();
      setStatus('idle');
    }
  }, [release]);

  const toggle = useCallback(() => (rec.current !== null ? stop() : start()), [start, stop]);

  return {
    status,
    listening: status === 'listening',
    unavailable: status === 'unsupported' || status === 'denied',
    reason,
    start,
    stop,
    toggle
  };
}
