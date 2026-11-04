'use client';

import { useEffect, useRef, useState } from 'react';

import { Button } from '../button/Button';
import { Dialog } from '../overlay/Dialog';
import { clockText, fill, localTimeText, spokenDuration } from './format';
import { type SessionTimeoutLabels } from './labels';
import styles from './session.module.css';
import { type WarningKind } from './timeout';

export interface SessionTimeoutDialogProps {
  open: boolean;
  kind: WarningKind;
  secondsLeft: number;
  /** When the session ends (epoch ms, device clock). */
  endsAt: number;
  busy: boolean;
  failed: boolean;
  labels: SessionTimeoutLabels;
  locale: string;
  /** "Stay signed in" (idle) and also Esc / the close button. */
  onStay: () => void;
  onSignOut: () => void;
  /** "Got it" (absolute limit) and also Esc / the close button there. */
  onDismiss: () => void;
}

/** Polite announcements at the start and every 15 s; assertive every second for the last 10 s. */
const POLITE_EVERY = 15;
const ASSERTIVE_FROM = 10;

interface Spoken {
  text: string;
  assertive: boolean;
}

/**
 * The "you'll be signed out" warning, in the shared Dialog (focus trap, Esc, focus back). Idle: a live countdown with
 * "Stay signed in" and "Sign out now". Absolute limit: the same countdown and the clock time, no way to extend it, so
 * "Got it" and "Sign out now". The visible countdown is not a live region (it would chatter); a hidden status region
 * speaks it politely at the start and every 15 seconds, and an alert region every second for the last 10.
 */
export function SessionTimeoutDialog({ open, kind, secondsLeft, endsAt, busy, failed, labels, locale, onStay, onSignOut, onDismiss }: SessionTimeoutDialogProps) {
  const primary = useRef<HTMLButtonElement>(null);
  const absolute = kind === 'absolute';
  const [spoken, setSpoken] = useState<Spoken | null>(null);
  const lastSpoken = useRef<number | null>(null);

  useEffect(() => {
    if (!open) {
      lastSpoken.current = null;
      setSpoken(null);
      return;
    }
    const assertive = secondsLeft <= ASSERTIVE_FROM;
    const last = lastSpoken.current;
    if (last !== null && !assertive && last - secondsLeft < POLITE_EVERY) return;
    if (last === secondsLeft) return;
    lastSpoken.current = secondsLeft;
    setSpoken({ text: fill(absolute ? labels.announceAbsolute : labels.announceIdle, { time: spokenDuration(secondsLeft, locale) }), assertive });
  }, [open, secondsLeft, absolute, labels.announceAbsolute, labels.announceIdle, locale]);

  const body = absolute
    ? `${fill(labels.absoluteBody, { clock: localTimeText(endsAt, locale) })} ${labels.absoluteNote}`
    : fill(labels.idleBody, { time: clockText(secondsLeft) });

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (next) return;
        if (absolute) onDismiss();
        else onStay();
      }}
      title={labels.warnTitle}
      description={body}
      closeLabel={labels.close}
      width={440}
      initialFocus={primary}
    >
      <div className={styles.countdown}>
        <span className={styles.countdownLabel}>{labels.timeLeft}</span>
        <span className={styles.countdownValue} data-testid="session-countdown" dir="ltr">
          {clockText(secondsLeft)}
        </span>
      </div>
      <div role="status" aria-live="polite" aria-atomic="true" className="at-visually-hidden" data-testid="session-live-polite">
        {spoken && !spoken.assertive ? spoken.text : ''}
      </div>
      <div role="alert" aria-live="assertive" aria-atomic="true" className="at-visually-hidden" data-testid="session-live-assertive">
        {spoken?.assertive ? spoken.text : ''}
      </div>
      {failed ? (
        <p role="alert" className={styles.error}>
          {labels.failed}
        </p>
      ) : null}
      <div className={styles.actions}>
        <Button variant="neutral" onClick={onSignOut} disabled={busy}>
          {labels.signOutNow}
        </Button>
        {absolute ? (
          <Button ref={primary} onClick={onDismiss}>
            {labels.dismiss}
          </Button>
        ) : (
          <Button ref={primary} onClick={onStay} loading={busy}>
            {labels.stay}
          </Button>
        )}
      </div>
    </Dialog>
  );
}
