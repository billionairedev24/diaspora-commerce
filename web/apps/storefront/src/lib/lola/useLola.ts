'use client';

import { isApiError, type LolaEvent, type LolaProductCard, type LolaProposal } from '@afrotransact/api-client';
import { useCallback, useEffect, useRef, useState } from 'react';

import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

export interface LolaTool {
  id: string;
  label: string;
  done: boolean;
}

export interface LolaMessage {
  id: string;
  role: 'lola' | 'user';
  text: string;
  tools: LolaTool[];
  products: LolaProductCard[];
  proposals: Array<LolaProposal & { state: 'open' | 'done' | 'dismissed' }>;
  handoff: string | null;
  streaming: boolean;
  error: string | null;
}

const SESSION_KEY = 'at_lola_session';

const store = {
  get(): string | undefined {
    try {
      return sessionStorage.getItem(SESSION_KEY) ?? undefined;
    } catch {
      return undefined;
    }
  },
  set(v: string) {
    try {
      sessionStorage.setItem(SESSION_KEY, v);
    } catch {
      /* private mode: the conversation simply starts over next time */
    }
  }
};

let seq = 0;
const nextId = () => `m${(seq += 1)}`;

const ROTATING_CHIP_KEYS = ['lola.chip3', 'lola.chip4', 'lola.chip5', 'lola.chip6'] as const;

/**
 * Opening suggestion chips: capability hints. "Track my order" and "Delivery to my ZIP code" always show; the third
 * rotates daily through find a product, scale a recipe ("Jollof for 20"), book a service and compare prices. The page is
 * stable within a day (no hydration mismatch) but the hints change (pure; exported for tests).
 */
export function openingChips(t: Translator<MessageKey>, now: Date = new Date()): string[] {
  const day = Math.floor(now.getTime() / 86_400_000);
  return [t('lola.chip1'), t('lola.chip2'), t(ROTATING_CHIP_KEYS[day % ROTATING_CHIP_KEYS.length] as MessageKey)];
}

/** Template message; the greeting's text is set in the shopper's language by useLola. */
export const GREETING: LolaMessage = {
  id: 'greeting', role: 'lola', text: '', tools: [], products: [], proposals: [], handoff: null, streaming: false, error: null
};

/** Applies one SSE event to the streaming answer (pure; exported for tests). */
export function applyLolaEvent(m: LolaMessage, e: LolaEvent, t: Translator<MessageKey>): LolaMessage {
  switch (e.type) {
    case 'tool_call':
      return { ...m, tools: [...m.tools, { id: e.data.id ?? e.data.tool, label: e.data.label, done: false }] };
    case 'tool_result': {
      const id = e.data.id ?? e.data.tool;
      const data = e.data.data as { products?: LolaProductCard[] } | null;
      const products = e.data.kind === 'products' && Array.isArray(data?.products) ? data.products : m.products;
      return { ...m, tools: m.tools.map((x) => (x.id === id ? { ...x, done: true } : x)), products };
    }
    case 'action_proposal':
      return m.proposals.some((p) => p.id === e.data.proposal.id) ? m : { ...m, proposals: [...m.proposals, { ...e.data.proposal, state: 'open' }] };
    case 'token':
      return { ...m, text: m.text + e.data.text };
    case 'handoff':
      return { ...m, handoff: e.data.summary ?? e.data.reason ?? '' };
    case 'done': {
      const known = new Set(m.proposals.map((p) => p.id));
      return {
        ...m,
        text: e.data.message || m.text,
        products: e.data.products.length ? e.data.products : m.products,
        proposals: [...m.proposals, ...e.data.proposals.filter((p) => !known.has(p.id)).map((p) => ({ ...p, state: 'open' as const }))],
        tools: m.tools.map((x) => ({ ...x, done: true })),
        streaming: false
      };
    }
    case 'error':
      // A stream that fails (AI budget reached, gateway down) is said plainly; there is never a made-up answer.
      return { ...m, error: t('lola.unavailable'), streaming: false };
    default:
      return m;
  }
}

/**
 * Lola conversation (S135): the opening greeting stays at the top, each turn streams token by token from the BFF's
 * SSE relay of ai-gateway, tool calls show as chips, proposals wait for the shopper's confirmation and the last
 * turn's suggestion chips are offered next.
 */
export function useLola() {
  const t = useT();
  const [messages, setMessages] = useState<LolaMessage[]>(() => [{ ...GREETING, text: t('lola.greeting') }]);
  const [suggestions, setSuggestions] = useState<string[]>(() => openingChips(t));
  const [busy, setBusy] = useState(false);
  const [needsSignIn, setNeedsSignIn] = useState(false);
  const abort = useRef<AbortController | null>(null);

  useEffect(() => () => abort.current?.abort(), []);

  const update = (id: string, fn: (m: LolaMessage) => LolaMessage) => setMessages((all) => all.map((m) => (m.id === id ? fn(m) : m)));

  const send = useCallback(async (text: string) => {
    const message = text.trim();
    if (!message || busy) return;
    const answerId = nextId();
    setMessages((all) => [
      ...all,
      { ...GREETING, id: nextId(), role: 'user', text: message },
      { ...GREETING, id: answerId, text: '', streaming: true }
    ]);
    setBusy(true);
    setSuggestions([]);
    abort.current = new AbortController();
    try {
      await browserApi().chatWithLola(
        { message, sessionId: store.get() },
        (e) => {
          if (e.type === 'done') {
            store.set(e.data.sessionId);
            setSuggestions(e.data.suggestions);
          }
          update(answerId, (m) => applyLolaEvent(m, e, t));
        },
        abort.current.signal
      );
    } catch (e) {
      if (isApiError(e) && e.status === 401) setNeedsSignIn(true);
      // 429 (the AI cap is reached), 5xx and a network failure (the gateway is down) all read the same.
      const down = !isApiError(e) || e.status === 429 || e.status >= 500;
      const msg = isApiError(e) && e.status === 401 ? t('lola.signIn') : down ? t('lola.unavailable') : isApiError(e) ? e.message : t('lola.failed');
      if (!(e instanceof DOMException && e.name === 'AbortError')) {
        update(answerId, (m) => ({ ...m, error: msg }));
        setSuggestions(openingChips(t));
      }
    } finally {
      update(answerId, (m) => ({ ...m, streaming: false }));
      setBusy(false);
    }
  }, [busy, t]);

  const settle = (messageId: string, proposalId: string, state: 'done' | 'dismissed') =>
    update(messageId, (m) => ({ ...m, proposals: m.proposals.map((p) => (p.id === proposalId ? { ...p, state } : p)) }));

  return { messages, suggestions, busy, needsSignIn, send, settle };
}
