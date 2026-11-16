/* Dictation for the Lola composer with the phone's own speech recogniser (expo-speech-recognition: Apple Speech on iOS, Android
   SpeechRecognizer). Tap to start, a live transcript fills the box, tap again to stop; it never sends. The OS may use its vendor's
   speech service; AfroTransact receives no audio. Not usable in Expo Go (native module): a development build is needed. */
import { ExpoSpeechRecognitionModule, useSpeechRecognitionEvent } from 'expo-speech-recognition';
import { useCallback, useRef, useState } from 'react';

import { classifyDictationError, DICTATION_FALLBACK_LOCALE, dictationLocale,type DictationReason } from '@/domain/dictation';

export interface Dictation {
  listening: boolean;
  /** The mic cannot be used (no recogniser on the device, or permission refused). */
  unavailable: boolean;
  reason: DictationReason | null;
  toggle: () => void;
  stop: () => void;
}

export function useDictation(appLocale: string, onTranscript: (transcript: string) => void): Dictation {
  const [listening, setListening] = useState(false);
  const [blocked, setBlocked] = useState(false);
  const [reason, setReason] = useState<DictationReason | null>(null);
  const retried = useRef(false);
  const available = (() => {
    try {
      return ExpoSpeechRecognitionModule.isRecognitionAvailable();
    } catch {
      return false;
    }
  })();

  const begin = useCallback((lang: string) => {
    ExpoSpeechRecognitionModule.start({ lang, interimResults: true, continuous: true, maxAlternatives: 1 });
  }, []);

  useSpeechRecognitionEvent('result', (e) => onTranscript(e.results[0]?.transcript ?? ''));
  useSpeechRecognitionEvent('start', () => setListening(true));
  useSpeechRecognitionEvent('end', () => setListening(false));
  useSpeechRecognitionEvent('error', (e) => {
    const failure = classifyDictationError(e.error);
    if (failure.retryFallbackLocale && !retried.current) {
      retried.current = true;
      begin(DICTATION_FALLBACK_LOCALE);
      return;
    }
    setListening(false);
    if (failure.blocked) setBlocked(true);
    setReason(failure.reason);
  });

  const start = useCallback(async () => {
    setReason(null);
    retried.current = false;
    const permission = await ExpoSpeechRecognitionModule.requestPermissionsAsync();
    if (!permission.granted) {
      setBlocked(true);
      setReason('denied');
      return;
    }
    begin(dictationLocale(appLocale));
  }, [appLocale, begin]);

  const stop = useCallback(() => ExpoSpeechRecognitionModule.stop(), []);
  const toggle = useCallback(() => {
    if (listening) stop();
    else void start();
  }, [listening, start, stop]);

  const unavailable = blocked || !available;
  return { listening, unavailable, reason: unavailable ? (blocked ? 'denied' : 'unsupported') : reason, toggle, stop };
}
