'use client';

import { recordLoad, SEEN_COOKIE } from '@afrotransact/api-client/home-visit';
import { useEffect } from 'react';

import { useConsentOptional } from '@/lib/consent/ConsentProvider';

/**
 * Keeps the guest novelty memory (cookie `at_hs`): the short keys of what this visit's home showed, so the NEXT visit
 * down-weights them. What the current visit showed is kept apart (recordLoad), so a refresh never reshuffles the page.
 * Writes nothing for a crawler's deterministic page, or when the shopper switched Functional cookies off.
 */
export function HomeVisitMemory({ window: visitWindow, seen, deterministic }: { window: string; seen: string[]; deterministic: boolean }) {
  const consent = useConsentOptional();
  const allowed = !!consent && (consent.consent === null || consent.consent.functional);
  const keys = seen.join('-');
  useEffect(() => {
    if (!allowed || deterministic || !visitWindow || !keys) return;
    try {
      const jar = Object.fromEntries(document.cookie.split('; ').map((c) => [c.slice(0, c.indexOf('=')), c.slice(c.indexOf('=') + 1)]));
      const next = recordLoad(jar[SEEN_COOKIE], visitWindow, keys.split('-'));
      if (next !== jar[SEEN_COOKIE]) document.cookie = `${SEEN_COOKIE}=${next}; path=/; samesite=lax${location.protocol === 'https:' ? '; secure' : ''}`;
    } catch {
      /* cookies blocked: the next visit simply has no memory */
    }
  }, [allowed, deterministic, visitWindow, keys]);
  return null;
}
