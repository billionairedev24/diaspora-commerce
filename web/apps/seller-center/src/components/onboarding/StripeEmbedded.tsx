'use client';

import { type OnboardingStripeSession } from '@afrotransact/api-client';
import { Button } from '@afrotransact/ui';
import { Flask, WarningCircle } from '@phosphor-icons/react/ssr';
import { type ReactNode, useEffect, useRef, useState } from 'react';

import { type ConnectComponent, hasStripeKey, initConnect } from '@/lib/stripe';

import { Spinner } from '../ui/States';

/* Stripe's embedded Connect components, mounted straight in the page (no Stripe-hosted page, no Stripe login, no
   wrapping card). Seller accounts are Stripe-collected: these components collect identity, tax ID and the payout bank
   account, and change them later; AfroTransact never sees the numbers. Without Stripe (local stack without a key, mock
   BFF) a clearly labelled local stand-in takes their place, in the same spot. */

const NAMES: Record<ConnectComponent, string> = {
  'account-onboarding': 'Stripe embedded onboarding',
  'account-management': 'Stripe account management',
  'notification-banner': 'Stripe notifications'
};

/** The local stand-in is used when the session says so, or when there's no real publishable key. */
export const isLocalSession = (s: OnboardingStripeSession): boolean => s.localTest || !hasStripeKey(s.publishableKey);

/** Loading the secure form: a soft placeholder where the form will appear (no layout jump). */
export function StripeLoading({ label = 'Loading the secure Stripe form…' }: { label?: string }) {
  return <Spinner label={label} center className="min-h-48" />;
}

/** Stripe couldn't load: why, and a way to try again, in the same spot as the form. */
export function StripeLoadFailed({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div role="alert" className="flex min-h-48 flex-col items-center justify-center gap-3 rounded-lg bg-danger-bg px-4 text-center text-sm">
      <WarningCircle size={28} weight="fill" className="text-danger" aria-hidden />
      <span className="max-w-[48ch]">{message}</span>
      <Button size="sm" variant="neutral" onClick={onRetry}>
        Try again
      </Button>
    </div>
  );
}

/**
 * Mounts one Connect.js component with the session's client secret (fetched again by Stripe when it expires), full
 * width and borderless; closing it calls {@link onExit} so the caller re-reads what Stripe holds.
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
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [attempt, setAttempt] = useState(0);
  const exit = useRef(onExit);
  exit.current = onExit;
  const fetchSecret = useRef(fetchClientSecret);
  fetchSecret.current = fetchClientSecret;
  useEffect(() => {
    let cancelled = false;
    let mounted: HTMLElement | null = null;
    setState('loading');
    initConnect(session.publishableKey, () => fetchSecret.current())
      .then((connect) => {
        if (cancelled || !box.current) return;
        const el = connect.create(component);
        if (component === 'account-onboarding') el.setCollectionOptions?.({ fields: 'eventually_due', futureRequirements: 'include' });
        el.setOnExit?.(() => exit.current?.());
        el.setOnLoaderStart?.(() => setState('ready'));
        el.setOnLoadError?.(() => setState('failed'));
        box.current.appendChild(el);
        mounted = el;
        // Older Connect.js builds don't report the loader: show the component once it's in the page.
        if (!el.setOnLoaderStart) setState('ready');
      })
      .catch(() => {
        if (!cancelled) setState('failed');
      });
    return () => {
      cancelled = true;
      mounted?.remove();
    };
  }, [session.publishableKey, session.accountId, component, attempt]);
  return (
    <div className="flex flex-col">
      {state === 'loading' ? <StripeLoading /> : null}
      {state === 'failed' ? (
        <StripeLoadFailed message="The secure Stripe form couldn’t load. Check your connection and try again." onRetry={() => setAttempt((n) => n + 1)} />
      ) : null}
      <div ref={box} className={state === 'ready' ? 'w-full' : 'hidden'} aria-label={NAMES[component]} />
    </div>
  );
}

/**
 * The labelled local stand-in for a Stripe component, in the component's place: a soft panel with one dominant action,
 * like Stripe's own first screen. Nothing leaves the machine.
 */
export function LocalStripeForm({ label, title, children, action }: { label: string; title: ReactNode; children: ReactNode; action: ReactNode }) {
  return (
    <div role="region" aria-label={label} className="flex flex-col items-start gap-4 rounded-lg bg-surface-sunken px-5 py-6 sm:px-8 sm:py-8">
      <span className="inline-flex items-center gap-1.5 rounded-pill bg-gold-100 px-2.5 py-0.5 text-xs font-semibold">
        <Flask size={14} aria-hidden /> Local test mode
      </span>
      <div className="flex flex-col gap-1">
        <strong className="text-lg">{title}</strong>
        <span className="max-w-[62ch] text-sm text-muted">{children}</span>
      </div>
      {action}
    </div>
  );
}

/** The local stand-in for account management. */
export function LocalAccountManagement({ onDone }: { onDone: () => void }) {
  return (
    <LocalStripeForm
      label="Local test account management"
      title="Change your payout bank account"
      action={
        <Button size="sm" variant="neutral" onClick={onDone}>
          Done
        </Button>
      }
    >
      Stripe isn’t configured on this machine. With a Stripe key, Stripe’s secure account settings open right here. Locally the payout account stays Stripe’s
      test bank, and nothing is sent to Stripe.
    </LocalStripeForm>
  );
}
