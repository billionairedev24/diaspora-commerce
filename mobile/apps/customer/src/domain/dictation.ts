/* Dictation rules for the Lola composer (pure, unit-tested). The speech engine itself (the phone's own recogniser through
   expo-speech-recognition) lives in features/lola/useDictation.ts. AfroTransact never receives audio. */

/** Reasons the mic is unavailable or has just stopped; each maps to one translated line (`cu.lola.mic.<reason>`). */
export type DictationReason = 'unsupported' | 'denied' | 'error' | 'noSpeech' | 'offline';

const LOCALES: Record<string, string> = { en: 'en-US', fr: 'fr-FR', es: 'es-ES', ar: 'ar-SA', yo: 'yo-NG' };
export const DICTATION_FALLBACK_LOCALE = 'en-US';

/** BCP 47 tag for recognition from the app language; US English when the language is not one of the five. */
export function dictationLocale(appLocale: string): string {
  return LOCALES[appLocale.toLowerCase().split('-')[0] ?? ''] ?? DICTATION_FALLBACK_LOCALE;
}

export interface DictationFailure {
  /** The reason to show, or null when the stop is silent (the user or the system aborted it). */
  reason: DictationReason | null;
  /** The mic cannot work until the shopper changes a setting. */
  blocked: boolean;
  /** Try again once in US English (the engine does not know the language). */
  retryFallbackLocale: boolean;
}

/** Maps an expo-speech-recognition error code to what the composer does. */
export function classifyDictationError(code: string): DictationFailure {
  switch (code) {
    case 'not-allowed':
    case 'service-not-allowed':
      return { reason: 'denied', blocked: true, retryFallbackLocale: false };
    case 'language-not-supported':
      return { reason: 'unsupported', blocked: false, retryFallbackLocale: true };
    case 'network':
      return { reason: 'offline', blocked: false, retryFallbackLocale: false };
    case 'no-speech':
      return { reason: 'noSpeech', blocked: false, retryFallbackLocale: false };
    case 'aborted':
    case 'interrupted':
      return { reason: null, blocked: false, retryFallbackLocale: false };
    default:
      return { reason: 'error', blocked: false, retryFallbackLocale: false };
  }
}

/** Text typed before dictation started plus the live transcript, joined by one space. */
export function mergeTranscript(base: string, transcript: string): string {
  return [base.trim(), transcript.replace(/\s+/g, ' ').trim()].filter(Boolean).join(' ');
}
