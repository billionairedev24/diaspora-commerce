import { Alert, type AlertVariant } from '@afrotransact/ui';
import { Eye, ShieldWarning } from '@phosphor-icons/react/ssr';
import { type ReactNode } from 'react';

type NoticeTone = Extract<AlertVariant, 'info' | 'warning' | 'danger' | 'success'>;

/**
 * Seller Center notice: a thin adapter over the shared `Alert` (@afrotransact/ui), which owns the look (flat tint,
 * accent bar, compact width) and the roles (danger announces as an alert; the rest are polite status messages).
 * `icon` swaps the glyph (eye: read-only, shield: compliance); `action` is shown as a link-style action.
 */
export function Notice({ tone, title, children, icon, action, role }: { tone: NoticeTone; title: ReactNode; children?: ReactNode; icon?: 'eye' | 'shield'; action?: ReactNode; role?: 'status' | 'alert' }) {
  return (
    <Alert variant={tone} title={title} icon={icon === 'eye' ? Eye : icon === 'shield' ? ShieldWarning : undefined} action={action} role={role}>
      {children}
    </Alert>
  );
}
