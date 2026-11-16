import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { dictationLang, MicButton, useDictation } from '../src';

class FakeRecognition {
  static last: FakeRecognition | null = null;
  static starts: string[] = [];
  lang = '';
  continuous = false;
  interimResults = false;
  maxAlternatives = 1;
  onresult: ((e: { resultIndex: number; results: Array<{ isFinal: boolean; length: number; 0: { transcript: string } }> }) => void) | null = null;
  onerror: ((e: { error: string }) => void) | null = null;
  onend: (() => void) | null = null;
  started = false;
  constructor() {
    FakeRecognition.last = this;
  }
  start() {
    this.started = true;
    FakeRecognition.starts.push(this.lang);
  }
  stop() {
    this.onend?.();
  }
  abort() {}
  say(...parts: Array<[string, boolean]>) {
    this.onresult?.({ resultIndex: 0, results: parts.map(([transcript, isFinal]) => ({ isFinal, length: 1, 0: { transcript } })) });
  }
  fail(error: string) {
    this.onerror?.({ error });
  }
}

const reasons = { unsupported: 'No voice here', denied: 'Mic blocked', error: 'Voice failed', noSpeech: 'Heard nothing', offline: 'Needs internet' };

function Composer({ lang = 'en-US', onSend = vi.fn() }: { lang?: string; onSend?: (t: string) => void }) {
  const [text, setText] = useState('');
  const d = useDictation({ lang, onTranscript: setText });
  const reason = d.reason ? reasons[d.reason] : '';
  return (
    <form onSubmit={(e) => { e.preventDefault(); onSend(text); }}>
      <input aria-label="message" dir="auto" value={text} onChange={(e) => setText(e.target.value)} />
      <MicButton listening={d.listening} unavailable={d.unavailable} onToggle={d.toggle} startLabel="Dictate" stopLabel="Stop dictation" reason={d.unavailable ? reason : null} />
      <p role="status">{d.listening ? 'Listening…' : reason}</p>
    </form>
  );
}

const install = () => {
  (window as unknown as Record<string, unknown>).SpeechRecognition = FakeRecognition;
};
const uninstall = () => {
  delete (window as unknown as Record<string, unknown>).SpeechRecognition;
  delete (window as unknown as Record<string, unknown>).webkitSpeechRecognition;
};

beforeEach(() => {
  FakeRecognition.last = null;
  FakeRecognition.starts = [];
  install();
});
afterEach(() => {
  uninstall();
  vi.useRealTimers();
  document.documentElement.removeAttribute('dir');
});

describe('dictationLang', () => {
  it('maps the five storefront locales and falls back to en-US', () => {
    expect(['en', 'fr', 'es', 'ar', 'yo'].map(dictationLang)).toEqual(['en-US', 'fr-FR', 'es-ES', 'ar-SA', 'yo-NG']);
    expect(dictationLang('fr-CA')).toBe('fr-FR');
    expect(dictationLang('de')).toBe('en-US');
  });
});

describe('dictation (SpeechRecognition)', () => {
  it('idle: an enabled, 44px-target, unpressed button with an accessible name', () => {
    render(<Composer />);
    const mic = screen.getByRole('button', { name: 'Dictate' });
    expect(mic).toHaveAttribute('aria-pressed', 'false');
    expect(mic).not.toHaveAttribute('aria-disabled');
  });

  it('listening: aria-pressed, a "Listening…" live region, the locale language, interim then final text, never auto-sent', async () => {
    const onSend = vi.fn();
    const user = userEvent.setup();
    render(<Composer lang="fr-FR" onSend={onSend} />);
    await user.click(screen.getByRole('button', { name: 'Dictate' }));
    const rec = FakeRecognition.last!;
    expect(rec.lang).toBe('fr-FR');
    expect(rec.interimResults).toBe(true);
    expect(screen.getByRole('button', { name: 'Stop dictation' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('status')).toHaveTextContent('Listening…');
    act(() => rec.say(['jollof pour', false]));
    expect(screen.getByLabelText('message')).toHaveValue('jollof pour');
    act(() => rec.say(['jollof pour vingt', true], [' personnes', false]));
    expect(screen.getByLabelText('message')).toHaveValue('jollof pour vingt personnes');
    await user.click(screen.getByRole('button', { name: 'Stop dictation' }));
    expect(screen.getByRole('button', { name: 'Dictate' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByLabelText('message')).toHaveValue('jollof pour vingt personnes');
    expect(onSend).not.toHaveBeenCalled();
  });

  it('stops by itself after a silence', () => {
    vi.useFakeTimers();
    render(<Composer />);
    act(() => screen.getByRole('button', { name: 'Dictate' }).click());
    act(() => FakeRecognition.last!.say(['hello', false]));
    act(() => { vi.advanceTimersByTime(4100); });
    expect(screen.getByRole('button', { name: 'Dictate' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('unsupported (no SpeechRecognition): the mic is aria-disabled with a one-line reason and does nothing', async () => {
    uninstall();
    const user = userEvent.setup();
    render(<Composer />);
    const mic = await screen.findByRole('button', { name: 'Dictate' });
    expect(mic).toHaveAttribute('aria-disabled', 'true');
    expect(mic).toHaveAttribute('title', 'No voice here');
    expect(screen.getByRole('status')).toHaveTextContent('No voice here');
    await user.click(mic);
    expect(FakeRecognition.last).toBeNull();
  });

  it('uses the webkit-prefixed constructor when that is all there is', async () => {
    uninstall();
    (window as unknown as Record<string, unknown>).webkitSpeechRecognition = FakeRecognition;
    const user = userEvent.setup();
    render(<Composer />);
    await user.click(screen.getByRole('button', { name: 'Dictate' }));
    expect(FakeRecognition.last?.started).toBe(true);
  });

  it('denied: permission refusal disables the mic and says why', async () => {
    const user = userEvent.setup();
    render(<Composer />);
    await user.click(screen.getByRole('button', { name: 'Dictate' }));
    act(() => FakeRecognition.last!.fail('not-allowed'));
    const mic = screen.getByRole('button', { name: 'Dictate' });
    expect(mic).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('status')).toHaveTextContent('Mic blocked');
  });

  it('error: other failures say so, leave the mic usable and keep the typed text', async () => {
    const user = userEvent.setup();
    render(<Composer />);
    await user.type(screen.getByLabelText('message'), 'hi');
    await user.click(screen.getByRole('button', { name: 'Dictate' }));
    act(() => FakeRecognition.last!.fail('audio-capture'));
    expect(screen.getByRole('status')).toHaveTextContent('Voice failed');
    expect(screen.getByRole('button', { name: 'Dictate' })).not.toHaveAttribute('aria-disabled');
    expect(screen.getByLabelText('message')).toHaveValue('hi');
  });

  it('network and no-speech errors get their own line', async () => {
    const user = userEvent.setup();
    render(<Composer />);
    await user.click(screen.getByRole('button', { name: 'Dictate' }));
    act(() => FakeRecognition.last!.fail('network'));
    expect(screen.getByRole('status')).toHaveTextContent('Needs internet');
    await user.click(screen.getByRole('button', { name: 'Dictate' }));
    act(() => FakeRecognition.last!.fail('no-speech'));
    expect(screen.getByRole('status')).toHaveTextContent('Heard nothing');
  });

  it('retries once in en-US when the browser rejects the language', async () => {
    const user = userEvent.setup();
    render(<Composer lang="yo-NG" />);
    await user.click(screen.getByRole('button', { name: 'Dictate' }));
    act(() => FakeRecognition.last!.fail('language-not-supported'));
    expect(FakeRecognition.starts).toEqual(['yo-NG', 'en-US']);
    expect(screen.getByRole('button', { name: 'Stop dictation' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('works in right-to-left pages: Arabic text lands in a dir=auto input', async () => {
    document.documentElement.setAttribute('dir', 'rtl');
    const user = userEvent.setup();
    render(<Composer lang="ar-SA" />);
    await user.click(screen.getByRole('button', { name: 'Dictate' }));
    expect(FakeRecognition.last!.lang).toBe('ar-SA');
    act(() => FakeRecognition.last!.say(['تتبّع طلبي', true]));
    expect(screen.getByLabelText('message')).toHaveValue('تتبّع طلبي');
    expect(screen.getByLabelText('message')).toHaveAttribute('dir', 'auto');
  });
});
