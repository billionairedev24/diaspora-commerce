import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LolaFab } from '@/components/shell/LolaFab';
import { LolaProvider } from '@/components/shell/LolaLauncher';
import ar from '@/i18n/ar.json';
import en from '@/i18n/en.json';
import es from '@/i18n/es.json';
import fr from '@/i18n/fr.json';
import { tEn } from '@/i18n/t';
import yo from '@/i18n/yo.json';
import { openingChips } from '@/lib/lola/useLola';

import { installMockBff } from './mockBff';
import { renderWithProviders } from './render';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => '/',
  useSearchParams: () => new URLSearchParams()
}));

installMockBff();

/** Festival names the owner does not want in Lola's hints, placeholders or chips. */
const FESTIVALS = /(diwali|divali|deepavali|\beid\b|ramadan|\bholi\b|hanukkah|ديوالي|عيد|رمضان)/i;

describe('Lola hints (all five languages)', () => {
  const catalogs = { en, fr, es, ar, yo } as unknown as Record<string, Record<string, string>>;
  it('mention no festival and the placeholder is capability-oriented', () => {
    for (const [loc, c] of Object.entries(catalogs)) {
      const keys = Object.keys(c).filter((k) => k.startsWith('lola.'));
      expect(keys.length, loc).toBeGreaterThan(20);
      for (const k of keys) expect(c[k], `${loc} ${k}`).not.toMatch(FESTIVALS);
    }
    expect(en['lola.placeholder']).toBe('Ask Lola to find a product, track an order or plan a meal…');
  });

  it('every language has all six chips and the mic strings', () => {
    for (const [loc, c] of Object.entries(catalogs)) {
      for (const k of Object.keys(en).filter((x) => /^lola\.(chip\d|mic\.)/.test(x))) expect(c[k], `${loc} ${k}`).toBeTruthy();
    }
  });

  it('openingChips keeps Track my order and Delivery, and rotates the third hint by day', () => {
    const t = tEn;
    const thirds = new Set<string>();
    for (let d = 0; d < 4; d += 1) {
      const chips = openingChips(t, new Date(Date.UTC(2026, 0, 1 + d)));
      expect(chips.slice(0, 2)).toEqual(['Track my order', 'Delivery to my ZIP code']);
      thirds.add(chips[2]!);
      for (const c of chips) expect(c).not.toMatch(FESTIVALS);
    }
    expect(thirds).toEqual(new Set(['Help me find a product', 'Jollof for 20', 'Book knotless braids', 'Compare prices']));
  });
});

class FakeRecognition {
  static last: FakeRecognition | null = null;
  lang = '';
  continuous = false;
  interimResults = false;
  maxAlternatives = 1;
  onresult: ((e: { resultIndex: number; results: Array<{ isFinal: boolean; length: number; 0: { transcript: string } }> }) => void) | null = null;
  onerror: ((e: { error: string }) => void) | null = null;
  onend: (() => void) | null = null;
  constructor() {
    FakeRecognition.last = this;
  }
  start() {}
  stop() {
    this.onend?.();
  }
  abort() {}
}

const openPanel = async (user: ReturnType<typeof userEvent.setup>) => {
  renderWithProviders(
    <LolaProvider>
      <LolaFab />
    </LolaProvider>
  );
  await user.click(screen.getByTestId('lola-launcher'));
  return screen.findByRole('dialog', { name: 'Lola' });
};

describe('Lola voice input', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    delete (window as unknown as Record<string, unknown>).SpeechRecognition;
    FakeRecognition.last = null;
  });

  it('unsupported browser: disabled mic with the reason, typing still works', async () => {
    const user = userEvent.setup();
    const panel = await openPanel(user);
    const mic = await within(panel).findByRole('button', { name: 'Dictate a message' });
    await waitFor(() => expect(mic).toHaveAttribute('aria-disabled', 'true'));
    expect(within(panel).getByTestId('mic-status')).toHaveTextContent('Voice input isn’t available in this browser. You can still type.');
    await user.type(within(panel).getByRole('textbox', { name: 'Message Lola' }), 'hello');
    expect(within(panel).getByRole('button', { name: 'Send' })).toBeEnabled();
  });

  it('dictation fills the box, shows the privacy line once, Esc stops it before closing, and nothing is sent', async () => {
    (window as unknown as Record<string, unknown>).SpeechRecognition = FakeRecognition;
    const user = userEvent.setup();
    const panel = await openPanel(user);
    const input = within(panel).getByRole('textbox', { name: 'Message Lola' });
    await user.type(input, 'Please');
    await user.click(within(panel).getByRole('button', { name: 'Dictate a message' }));
    expect(within(panel).getByTestId('mic-notice')).toHaveTextContent('AfroTransact keeps no audio.');
    expect(within(panel).getByTestId('mic-status')).toHaveTextContent('Listening…');
    act(() => FakeRecognition.last!.onresult?.({ resultIndex: 0, results: [{ isFinal: false, length: 1, 0: { transcript: 'track my order' } }] }));
    expect(input).toHaveValue('Please track my order');
    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog', { name: 'Lola' })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Dictate a message' })).toHaveAttribute('aria-pressed', 'false');
    await waitFor(() => expect(input).toHaveFocus());
    expect(input).toHaveValue('Please track my order');
    expect(within(panel).queryByTestId('lola-user')).toBeNull();
    await user.click(within(panel).getByRole('button', { name: 'Got it' }));
    expect(localStorage.getItem('at_lola_mic_notice')).toBe('1');
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('permission denied disables the mic with the settings hint', async () => {
    (window as unknown as Record<string, unknown>).SpeechRecognition = FakeRecognition;
    const user = userEvent.setup();
    const panel = await openPanel(user);
    await user.click(within(panel).getByRole('button', { name: 'Dictate a message' }));
    act(() => FakeRecognition.last!.onerror?.({ error: 'not-allowed' }));
    expect(within(panel).getByRole('button', { name: 'Dictate a message' })).toHaveAttribute('aria-disabled', 'true');
    expect(within(panel).getByTestId('mic-status')).toHaveTextContent('Microphone access is blocked');
  });
});
