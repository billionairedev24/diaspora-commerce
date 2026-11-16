import { describe, expect, it } from 'vitest';

import { classifyDictationError, dictationLocale, mergeTranscript } from '../src/domain/dictation';
import { LOLA_PROMPTS } from '../src/domain/lola';

describe('dictation rules', () => {
  it('maps the five app languages to recognition locales, US English otherwise', () => {
    expect(['en', 'fr', 'es', 'ar', 'yo'].map(dictationLocale)).toEqual(['en-US', 'fr-FR', 'es-ES', 'ar-SA', 'yo-NG']);
    expect(dictationLocale('de')).toBe('en-US');
  });

  it('classifies engine errors', () => {
    expect(classifyDictationError('not-allowed')).toMatchObject({ reason: 'denied', blocked: true });
    expect(classifyDictationError('service-not-allowed')).toMatchObject({ reason: 'denied', blocked: true });
    expect(classifyDictationError('language-not-supported')).toMatchObject({ retryFallbackLocale: true });
    expect(classifyDictationError('network').reason).toBe('offline');
    expect(classifyDictationError('no-speech').reason).toBe('noSpeech');
    expect(classifyDictationError('aborted').reason).toBeNull();
    expect(classifyDictationError('audio-capture')).toMatchObject({ reason: 'error', blocked: false });
  });

  it('merges typed text with the live transcript', () => {
    expect(mergeTranscript('', ' jollof  for 20 ')).toBe('jollof for 20');
    expect(mergeTranscript('Please ', 'track my order')).toBe('Please track my order');
    expect(mergeTranscript('hi', '')).toBe('hi');
  });
});

describe('Lola prompts', () => {
  it('are capability hints without festival names', () => {
    expect(LOLA_PROMPTS.length).toBeGreaterThanOrEqual(4);
    for (const p of LOLA_PROMPTS) expect(p).not.toMatch(/\b(diwali|divali|deepavali|eid|ramadan|holi|hanukkah)\b/i);
  });
});
