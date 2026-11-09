import { type AnchorHTMLAttributes, type ComponentType, type ReactNode } from 'react';

import { buttonClassName } from '../button/Button';
import { cx } from '../cx';
import styles from './dashboard.module.css';

export type EmptyArt = 'listings' | 'chart' | 'calendar' | 'inbox' | 'payout' | 'star';

export type LinkLike = ComponentType<AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }> | 'a';

/** Quiet line illustration painted only with design tokens (no emoji, no photo). Decorative: hidden from assistive technology. */
export function EmptyArtwork({ art = 'chart' }: { art?: EmptyArt }) {
  return (
    <svg viewBox="0 0 96 64" className={styles.art} aria-hidden focusable="false" data-art={art}>
      <rect x="4" y="52" width="88" height="3" rx="1.5" className={styles.artGround} />
      {art === 'listings' ? (
        <>
          <rect x="22" y="22" width="40" height="30" rx="3" className={styles.artFill} />
          <path d="M22 32h40" className={styles.artLine} />
          <rect x="52" y="12" width="22" height="22" rx="3" className={styles.artAccent} />
          <path d="M57 23h12M63 17v12" className={styles.artLineOn} />
        </>
      ) : null}
      {art === 'chart' ? (
        <>
          <rect x="20" y="34" width="10" height="18" rx="2" className={styles.artFill} />
          <rect x="36" y="24" width="10" height="28" rx="2" className={styles.artFill} />
          <rect x="52" y="30" width="10" height="22" rx="2" className={styles.artFill} />
          <path d="M18 26l20-12 16 8 22-14" className={styles.artLineAccent} fill="none" />
          <circle cx="76" cy="8" r="3.5" className={styles.artAccent} />
        </>
      ) : null}
      {art === 'calendar' ? (
        <>
          <rect x="22" y="14" width="52" height="38" rx="4" className={styles.artFill} />
          <path d="M22 26h52M36 10v8M60 10v8" className={styles.artLine} />
          <rect x="32" y="33" width="9" height="7" rx="1.5" className={styles.artAccent} />
          <rect x="46" y="33" width="9" height="7" rx="1.5" className={styles.artLine} fill="none" />
          <rect x="60" y="33" width="9" height="7" rx="1.5" className={styles.artLine} fill="none" />
        </>
      ) : null}
      {art === 'inbox' ? (
        <>
          <path d="M20 34l8-18h40l8 18v16a2 2 0 0 1-2 2H22a2 2 0 0 1-2-2z" className={styles.artFill} />
          <path d="M20 34h18c0 5 4 8 10 8s10-3 10-8h18" className={styles.artLine} fill="none" />
          <circle cx="72" cy="14" r="5" className={styles.artAccent} />
        </>
      ) : null}
      {art === 'payout' ? (
        <>
          <rect x="16" y="20" width="64" height="32" rx="4" className={styles.artFill} />
          <circle cx="48" cy="36" r="9" className={styles.artAccent} />
          <path d="M48 31v10M45 34h5a2 2 0 0 1 0 4h-4" className={styles.artLineOn} fill="none" />
        </>
      ) : null}
      {art === 'star' ? (
        <>
          <path d="M48 10l6.2 13 14.2 1.8-10.4 9.8 2.7 14L48 41.6 35.3 48.6l2.7-14-10.4-9.8L41.8 23z" className={styles.artAccent} />
          <path d="M20 20l3 3M76 18l-3 3M18 42h6M72 44h6" className={styles.artLine} />
        </>
      ) : null}
    </svg>
  );
}

export interface EmptyPromptProps {
  art?: EmptyArt;
  /** One friendly line: what this module will show and what to do ("Add your first product to start selling."). */
  prompt: ReactNode;
  /** The one primary action. */
  action?: { label: string; href: string };
  linkAs?: LinkLike;
  className?: string;
}

/** Friendly empty state for a module that has nothing yet: an illustration, one line and one primary action. Never zeros. */
export function EmptyPrompt({ art, prompt, action, linkAs, className }: EmptyPromptProps) {
  const Link = linkAs ?? 'a';
  return (
    <div className={cx(styles.empty, className)} data-state="empty">
      <EmptyArtwork art={art} />
      <p className={styles.emptyText}>{prompt}</p>
      {action ? (
        <Link href={action.href} className={buttonClassName({ variant: 'primary', size: 'sm' })}>
          {action.label}
        </Link>
      ) : null}
    </div>
  );
}
