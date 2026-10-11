'use client';

import { type OnboardingStripeSession } from '@afrotransact/api-client';
import { Button } from '@afrotransact/ui';
import { Flask } from '@phosphor-icons/react/ssr';
import { useEffect, useRef, useState } from 'react';

import { type ConnectComponent, hasStripeKey, initConnect } from '@/lib/stripe';

/* Stripe's embedded Connect components, mounted in the page (no Stripe-hosted page, no Stripe login). Seller
   accounts are Stripe-collected: these components collect identity, tax ID and the payout bank account, and change
   them later; AfroTransact never sees the numbers. Without Stripe (local stack without a key, mock BFF) a clearly
   labelled local stand-in takes their place. */

const NAMES: Record<ConnectComponent, string> = {
  'account-onboarding': 'Stripe embedded onboarding',
  'account-management': 'Stripe account management',
  'notification-banner': 'Stripe notifications'
};

/** The local stand-in is used when the session says so, or when there's no real publishable key. */
export const isLocalSession = (s: OnboardingStripeSession): boolean => s.localTest || !hasStripeKey(s.publishableKey);

/**
 * Mounts one Connect.js component with the session's client secret (fetched again by Stripe when it expires);
 * closing it calls {@link onExit} so the caller re-reads what Stripe holds.
 */
export function StripeEmbedded({
  session,
  component,
  fetchClientSecret,
  onExit
}: {
  session: OnboardingStripeSession;
  component: ConnectComponent;
  fetchClientSecret: () => Promise<string>;
  onExit?: () => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const exit = useRef(onExit);
  exit.current = onExit;
  const fetchSecret = useRef(fetchClientSecret);
  fetchSecret.current = fetchClientSecret;
  useEffect(() => {
    let cancelled = false;
    let mounted: HTMLElement | null = null;
    initConnect(session.publishableKey, () => fetchSecret.current())
      .then((connect) => {
        if (cancelled || !box.current) return;
        const el = connect.create(component);
        if (component === 'account-onboarding') el.setCollectionOptions?.({ fields: 'eventually_due', futureRequirements: 'include' });
        el.setOnExit?.(() => exit.current?.());
        box.current.appendChild(el);
        mounted = el;
      })
      .catch(() => setFailed('Stripe couldn’t load. Check your connection and try again.'));
    return () => {
      cancelled = true;
      mounted?.remove();
    };
  }, [session.publishableKey, session.accountId, component]);
  return (
    <div className="flex flex-col gap-2">
      {failed ? (
        <p role="alert" className="m-0 text-sm text-danger">
          {failed}
        </p>
      ) : null}
      <div ref={box} className="min-h-24 rounded-md border border-border p-2" aria-label={NAMES[component]} />
    </div>
  );
}

/** The labelled local stand-in for account management: nothing leaves the machine. */
export function LocalAccountManagement({ onDone }: { onDone: () => void }) {
  return (
    <div
      role="region"
      aria-label="Local test account management"
      className="flex flex-col gap-3 rounded-md border border-dashed border-gold-500 bg-gold-50 p-4"
    >
      <div className="flex items-start gap-3">
        <Flask size={22} aria-hidden className="mt-0.5 flex-none" />
        <div className="flex flex-col gap-1 text-sm">
          <strong>Local test mode — Stripe isn’t configured on this machine</strong>
          <span className="text-muted">
            With a Stripe key, Stripe’s secure account settings open right here to change the payout bank account. Locally the payout account stays Stripe’s
            test bank. Nothing is sent to Stripe.
          </span>
        </div>
      </div>
      <div>
        <Button size="sm" variant="neutral" onClick={onDone}>
          Done
        </Button>
      </div>
    </div>
  );
}
