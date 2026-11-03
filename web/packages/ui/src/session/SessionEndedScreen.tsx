'use client';

import { Content, Description, Portal, Root, Title } from '@radix-ui/react-dialog';

import { Button } from '../button/Button';
import { type SessionTimeoutLabels } from './labels';
import styles from './session.module.css';
import { type SessionOverReason } from './timeout';

export interface SessionEndedScreenProps {
  reason: SessionOverReason;
  labels: SessionTimeoutLabels;
  /** Go to sign-in. */
  onSignIn: () => void;
  /** The sign-out is still being finished (the button waits). */
  busy?: boolean;
}

/** What the person is told for each way a session ends. */
export function endedMessage(reason: SessionOverReason, labels: SessionTimeoutLabels): string {
  if (reason === 'SESSION_IDLE_TIMEOUT') return labels.endedIdle;
  if (reason === 'SESSION_ABSOLUTE_TIMEOUT') return labels.endedAbsolute;
  return labels.endedSignedOut;
}

/**
 * Replaces the whole signed-in page once the session is over: opaque, so nothing of the account shows through, and a
 * modal alert dialog so keyboard focus and screen readers cannot reach what is behind it. It says why and offers the
 * one way forward, signing in again.
 */
export function SessionEndedScreen({ reason, labels, onSignIn, busy = false }: SessionEndedScreenProps) {
  return (
    <Root open modal>
      <Portal>
        <Content
          role="alertdialog"
          className={styles.ended}
          data-testid="session-ended"
          onEscapeKeyDown={(e) => e.preventDefault()}
          onInteractOutside={(e) => e.preventDefault()}
          onPointerDownOutside={(e) => e.preventDefault()}
        >
          <div className={styles.endedBody}>
            <Title className={styles.endedTitle}>{labels.endedTitle}</Title>
            <Description className={styles.endedText}>{endedMessage(reason, labels)}</Description>
            <Button onClick={onSignIn} loading={busy}>
              {labels.signInAgain}
            </Button>
            {busy ? (
              <p role="status" className={styles.endedText}>
                {labels.signingOut}
              </p>
            ) : null}
          </div>
        </Content>
      </Portal>
    </Root>
  );
}
