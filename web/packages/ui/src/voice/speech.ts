/* Minimal typings for the Web Speech API's recognition half (not in lib.dom). Dictation is on-device or the browser
   vendor's own service: AfroTransact never receives audio. */

export interface SpeechRecognitionAlternativeLike {
  transcript: string;
}
export interface SpeechRecognitionResultLike {
  readonly isFinal: boolean;
  readonly length: number;
  [index: number]: SpeechRecognitionAlternativeLike;
}
export interface SpeechRecognitionEventLike {
  readonly resultIndex: number;
  readonly results: { readonly length: number; [index: number]: SpeechRecognitionResultLike };
}
export interface SpeechRecognitionErrorEventLike {
  readonly error: string;
}
export interface SpeechRecognitionLike {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  maxAlternatives: number;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onerror: ((event: SpeechRecognitionErrorEventLike) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
}
export type SpeechRecognitionCtor = new () => SpeechRecognitionLike;

interface SpeechWindow {
  SpeechRecognition?: SpeechRecognitionCtor;
  webkitSpeechRecognition?: SpeechRecognitionCtor;
}

/** The browser's recognition constructor, or null (Firefox, older browsers, server rendering). */
export function speechRecognitionCtor(): SpeechRecognitionCtor | null {
  if (typeof window === 'undefined') return null;
  const w = window as unknown as SpeechWindow;
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

/** BCP 47 tags for the storefront locales; anything else falls back to US English. */
const LANG: Record<string, string> = { en: 'en-US', fr: 'fr-FR', es: 'es-ES', ar: 'ar-SA', yo: 'yo-NG' };
export const DICTATION_FALLBACK_LANG = 'en-US';

export function dictationLang(locale: string): string {
  return LANG[locale.toLowerCase().split('-')[0] ?? ''] ?? DICTATION_FALLBACK_LANG;
}
