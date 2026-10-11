'use client';

import { type ConsentSource } from '@afrotransact/api-client';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState } from 'react';

import { browserApi } from '@/lib/api/browser';

import { allows, browserGpc, buildConsent, type ConsentCategory, type ConsentChoice, consentCookieString, type ConsentState, effectiveConsent, readConsent } from './consent';

export interface ConsentContextValue {
  /** The effective choice (GPC applied); null until the visitor has chosen. */
  consent: ConsentState | null;
  /** Global Privacy Control is on (Sec-GPC header on the request or navigator.globalPrivacyControl). */
  gpc: boolean;
  /** True once the visitor has made a choice (banner hidden). */
  decided: boolean;
  /** May scripts of this category run? */
  allows: (category: ConsentCategory) => boolean;
  /** Saves a choice: first-party cookie immediately, then the auditable record at content-service. */
  save: (choice: ConsentChoice, source: ConsentSource, policyVersion?: string) => Promise<ConsentState>;
}

const ConsentContext = createContext<ConsentContextValue | null>(null);

const isSecure = () => typeof location !== 'undefined' && location.protocol === 'https:';

/**
 * Holds the visitor's cookie choice. The server passes what it read from the request (the `at_consent` cookie and
 * `Sec-GPC`) so the first render already knows whether to show the banner; the browser's GPC flag is added on mount.
 */
export function ConsentProvider({ initial, initialGpc = false, children }: { initial: ConsentState | null; initialGpc?: boolean; children: ReactNode }) {
  const [stored, setStored] = useState<ConsentState | null>(initial);
  const [gpc, setGpc] = useState(initialGpc);

  useEffect(() => {
    if (browserGpc()) setGpc(true);
    // Another tab may have changed the choice: re-read the cookie when this one becomes visible again.
    const sync = () => {
      if (document.visibilityState === 'visible') setStored(readConsent(document.cookie));
    };
    document.addEventListener('visibilitychange', sync);
    return () => document.removeEventListener('visibilitychange', sync);
  }, []);

  const save = useCallback(
    async (choice: ConsentChoice, source: ConsentSource, policyVersion?: string): Promise<ConsentState> => {
      const signal = gpc || browserGpc();
      const state = buildConsent(choice, { gpc: signal, version: policyVersion ?? stored?.v ?? '' });
      document.cookie = consentCookieString(state, isSecure());
      setStored(state);
      try {
        const rec = await browserApi().recordConsent({
          categories: { functional: state.functional, analytics: state.analytics, advertising: state.advertising },
          saleOptOut: state.dns,
          gpc: signal,
          source
        });
        const saved: ConsentState = { ...state, id: rec.id, v: rec.policyVersion || state.v, advertising: rec.categories.advertising === true && state.advertising };
        document.cookie = consentCookieString(saved, isSecure());
        setStored(saved);
        return saved;
      } catch {
        // The browser cookie is what the page honours; the audit record is retried on the next change.
        return state;
      }
    },
    [gpc, stored?.v]
  );

  const value = useMemo<ConsentContextValue>(() => {
    const consent = effectiveConsent(stored, gpc);
    return { consent, gpc, decided: stored !== null, allows: (c) => allows(consent, c), save };
  }, [stored, gpc, save]);

  return <ConsentContext.Provider value={value}>{children}</ConsentContext.Provider>;
}

/** The visitor's cookie choice. Analytics, advertising and functional storage must check `allows(...)` first. */
export function useConsent(): ConsentContextValue {
  const ctx = useContext(ConsentContext);
  if (!ctx) throw new Error('useConsent must be used inside <ConsentProvider>');
  return ctx;
}

/** Renders its children (e.g. an analytics or ad script) only when the visitor allowed that category. */
export function ConsentGate({ category, children }: { category: ConsentCategory; children: ReactNode }) {
  const { allows: ok } = useConsent();
  return ok(category) ? children : null;
}
