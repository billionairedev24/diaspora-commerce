import { siWhatsapp } from 'simple-icons';

import { cx } from '../cx';
import styles from './brand.module.css';

export interface WhatsAppChatButtonProps {
  /** The number to chat with, E.164 (+18335550199). Nothing is rendered without a usable one. */
  phone: string;
  /** What the chat starts with (the person can edit it before sending). */
  message: string;
  /** Visible on hover and read by screen readers, e.g. "Chat with us on WhatsApp". */
  label: string;
  newTabLabel?: string;
  className?: string;
}

/** wa.me link for a phone in E.164 (digits only) and a first message; null when the phone is not a plausible number. */
export function whatsAppHref(phone: string, message: string): string | null {
  const digits = phone.replace(/\D/g, '');
  if (!/^[1-9]\d{7,14}$/.test(digits)) return null;
  return `https://wa.me/${digits}?text=${encodeURIComponent(message)}`;
}

/**
 * Floating "chat with us on WhatsApp" button: the official mark on its brand colour, fixed to the bottom corner, opening a
 * chat with support in a new tab. For customers in the storefront and sellers in Seller Center.
 */
export function WhatsAppChatButton({ phone, message, label, newTabLabel = '(opens in a new tab)', className }: WhatsAppChatButtonProps) {
  const href = whatsAppHref(phone, message);
  if (!href) return null;
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" aria-label={`${label} ${newTabLabel}`} title={label} className={cx(styles.chatFab, className)}>
      <svg viewBox="0 0 24 24" aria-hidden focusable="false">
        <path d={siWhatsapp.path} />
      </svg>
    </a>
  );
}
