'use client';

import { cx } from '../cx';
import { Microphone, MicrophoneSlash, Stop } from '../icons';
import styles from './MicButton.module.css';

export interface MicButtonProps {
  listening: boolean;
  /** Unsupported browser or permission denied: the button stays visible but disabled; the reason is shown by the caller. */
  unavailable: boolean;
  onToggle: () => void;
  startLabel: string;
  stopLabel: string;
  /** Why it is unavailable (also the accessible description). */
  reason?: string | null;
  className?: string;
}

/**
 * Dictation toggle for a composer. 44px target, `aria-pressed` while listening, a visible recording state that does not
 * rely on animation alone (stop icon + colour), and `aria-disabled` (not `disabled`) when unavailable so screen readers
 * still reach the reason.
 */
export function MicButton({ listening, unavailable, onToggle, startLabel, stopLabel, reason, className }: MicButtonProps) {
  return (
    <button
      type="button"
      className={cx(styles.mic, listening && styles.listening, unavailable && styles.unavailable, className)}
      aria-pressed={listening}
      aria-disabled={unavailable || undefined}
      aria-label={listening ? stopLabel : startLabel}
      title={unavailable && reason ? reason : listening ? stopLabel : startLabel}
      data-testid="mic-button"
      onClick={() => {
        if (!unavailable) onToggle();
      }}
    >
      {unavailable ? <MicrophoneSlash aria-hidden /> : listening ? <Stop weight="fill" aria-hidden /> : <Microphone aria-hidden />}
    </button>
  );
}
