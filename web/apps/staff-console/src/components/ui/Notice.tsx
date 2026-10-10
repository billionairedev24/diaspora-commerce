import { cx } from '@afrotransact/ui';
import { Eye, Info, ShieldWarning, WarningCircle } from '@phosphor-icons/react/ssr';
import { type ReactNode } from 'react';

type NoticeTone = 'info' | 'warning' | 'danger' | 'success';

const STYLES: Record<NoticeTone, string> = {
  info: 'bg-info-bg border-border text-text',
  warning: 'bg-warning-bg border-gold-300 text-text',
  danger: 'bg-danger-bg border-red-100 text-text',
  success: 'bg-success-bg border-green-200 text-text'
};
const ICON_COLOR: Record<NoticeTone, string> = { info: 'text-info', warning: 'text-warning', danger: 'text-danger', success: 'text-success' };

/** Banner with an icon: compliance, read-only, locked and info notices. */
export function Notice({ tone, title, children, icon, action, role = 'status' }: { tone: NoticeTone; title: ReactNode; children?: ReactNode; icon?: 'eye' | 'shield'; action?: ReactNode; role?: 'status' | 'alert' }) {
  const I = icon === 'eye' ? Eye : icon === 'shield' ? ShieldWarning : tone === 'info' ? Info : WarningCircle;
  return (
    <div role={role} className={cx('flex items-start gap-3 rounded-md border px-4 py-3 text-md', STYLES[tone])}>
      <I size={22} weight="fill" aria-hidden className={cx('mt-px flex-none', ICON_COLOR[tone])} />
      <div className="min-w-0 flex-1">
        <strong>{title}</strong> {children}
      </div>
      {action}
    </div>
  );
}
