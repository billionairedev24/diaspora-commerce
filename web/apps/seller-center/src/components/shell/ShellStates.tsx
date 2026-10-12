'use client';

import { Button } from '@afrotransact/ui';
import { HourglassMedium, SignOut, WarningCircle } from '@phosphor-icons/react/ssr';
import { useEffect, useState } from 'react';

import { ErrorState, Spinner } from '@/components/ui/States';
import { loginHref, signOutEverywhere } from '@/lib/auth';
import { type ApplyRole } from '@/lib/onboarding-entry';
import { useStorefrontUrl } from '@/lib/runtime-config';

/* The shell's waiting and failure states. Loading always ends: after a while the page offers Retry and Sign out, an
   error offers the same, and a sign-in that comes back still signed out is not retried in a loop. */

/** How long the shell waits quietly before it says so and offers a way out. */
export const SLOW_AFTER_MS = 10_000;

function SignOutButton() {
  return (
    <Button variant="neutral" size="sm" onClick={() => void signOutEverywhere()}>
      <SignOut size={16} aria-hidden /> Sign out
    </Button>
  );
}

/** A spinner that, after {@link SLOW_AFTER_MS}, explains itself and offers Retry and Sign out. */
export function ShellLoading({ label, onRetry, slowAfterMs = SLOW_AFTER_MS }: { label: string; onRetry: () => void; slowAfterMs?: number }) {
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    const t = setTimeout(() => setSlow(true), slowAfterMs);
    return () => clearTimeout(t);
  }, [slowAfterMs]);
  if (!slow) return <Spinner label={label} />;
  return (
    <div role="alert" className="flex flex-col items-center gap-3 rounded-md border border-border bg-surface px-6 py-10 text-center">
      <HourglassMedium size={36} aria-hidden className="text-muted" />
      <strong className="text-lg">This is taking longer than usual</strong>
      <p className="m-0 max-w-[60ch] text-sm text-muted">AfroTransact isn’t answering right now. Try again, or sign out and come back later.</p>
      <div className="flex flex-wrap justify-center gap-2">
        <Button size="sm" onClick={onRetry}>
          Try again
        </Button>
        <SignOutButton />
      </div>
    </div>
  );
}

/** The session could not be read: what went wrong, with Retry and Sign out. */
export function ShellFailed({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center gap-3">
      <ErrorState error={error} onRetry={onRetry} />
      <SignOutButton />
    </div>
  );
}

const SIGN_IN_KEY = 'at.sc.signInStartedAt';
/** A sign-in that comes back signed out within this window is a loop, not a fresh visit. */
export const SIGN_IN_LOOP_MS = 2 * 60_000;

function readAttempt(): number {
  try {
    return Number(window.sessionStorage.getItem(SIGN_IN_KEY) ?? 0) || 0;
  } catch {
    return 0;
  }
}

function writeAttempt(at: number | null) {
  try {
    if (at === null) window.sessionStorage.removeItem(SIGN_IN_KEY);
    else window.sessionStorage.setItem(SIGN_IN_KEY, String(at));
  } catch {
    /* private mode: the guard is best effort */
  }
}

/** The session is back: a later 401 (a session that expired) may sign in again straight away. */
export function clearSignInAttempt() {
  writeAttempt(null);
}

/**
 * Signed out: there is no Seller Center sign-in page of its own — credentials are entered on identity's hosted pages,
 * so the browser goes straight there and comes back to the page it asked for (with the seller or provider copy when it
 * came from "Start selling" / "Offer your services"). If it comes back still signed out, it stops and says so instead
 * of sending the browser round again.
 */
export function AutoSignIn({ role, label }: { role: ApplyRole | null; label: string }) {
  const storefrontUrl = useStorefrontUrl();
  const [looped, setLooped] = useState(false);
  useEffect(() => {
    const last = readAttempt();
    if (last && Date.now() - last < SIGN_IN_LOOP_MS) {
      setLooped(true);
      return;
    }
    writeAttempt(Date.now());
    window.location.assign(loginHref(window.location.href, role ? { intent: role === 'provider' ? 'provide' : 'sell' } : {}));
  }, [role]);
  if (!looped) {
    return (
      <div className="p-8">
        <Spinner label={label} />
      </div>
    );
  }
  return (
    <div className="mx-auto flex w-[min(560px,100%)] flex-col items-center gap-4 px-4 py-16 text-center" role="alert">
      <WarningCircle size={40} aria-hidden className="text-warning" />
      <h1 className="m-0 text-2xl font-bold">We couldn’t sign you in to Seller Center</h1>
      <p className="m-0 text-muted">
        You signed in, but this browser didn’t keep the Seller Center session. Check that cookies are allowed for this site, then try again.
      </p>
      <div className="flex flex-wrap justify-center gap-2">
        <Button
          onClick={() => {
            writeAttempt(Date.now());
            window.location.assign(loginHref(window.location.href, role ? { intent: role === 'provider' ? 'provide' : 'sell' } : {}));
          }}
        >
          Try again
        </Button>
        <a className="inline-flex items-center rounded-pill border border-border px-5 py-2 font-semibold text-text no-underline hover:no-underline" href={storefrontUrl}>
          Back to the storefront
        </a>
      </div>
    </div>
  );
}
