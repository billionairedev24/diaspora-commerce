'use client';

import { isApiError } from '@afrotransact/api-client';
import { Button } from '@afrotransact/ui';
import { CloudSlash, LockSimple, WarningCircle } from '@phosphor-icons/react/ssr';
import { type ReactNode } from 'react';

import { useT } from '../../i18n/t';

export function Spinner({ label = 'Loading…' }: { label?: string }) {
  return (
    <div role="status" aria-live="polite" className="flex min-h-40 items-center justify-center rounded-md bg-surface-muted text-sm text-muted">
      {label}
    </div>
  );
}

export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-2 rounded-md border border-dashed border-border-strong px-6 py-10 text-center">
      <strong className="text-lg">{title}</strong>
      {children ? <p className="m-0 max-w-[60ch] text-sm text-muted">{children}</p> : null}
      {action}
    </div>
  );
}

/** Error panel: 503 UPSTREAM_UNAVAILABLE (a service still being built or down), 403 and generic failures. */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const t = useT();
  const unavailable = isApiError(error) && (error.status === 503 || error.code === 'UPSTREAM_UNAVAILABLE');
  const forbidden = isApiError(error) && error.status === 403;
  const Icon = unavailable ? CloudSlash : forbidden ? LockSimple : WarningCircle;
  const title = unavailable ? t('sc.states.unavailable') : forbidden ? t('sc.states.forbidden') : t('sc.states.failed');
  const detail = isApiError(error) ? error.problem.detail : t('sc.states.retryHint');
  return (
    <div role="alert" className="flex flex-col items-center gap-3 rounded-md border border-border bg-surface px-6 py-10 text-center">
      <Icon size={36} aria-hidden className="text-muted" />
      <strong className="text-lg">{title}</strong>
      {detail ? <p className="m-0 max-w-[60ch] text-sm text-muted">{detail}</p> : null}
      {onRetry && !forbidden ? (
        <Button variant="neutral" size="sm" onClick={onRetry}>
          {t('sc.states.tryAgain')}
        </Button>
      ) : null}
    </div>
  );
}

/** Message for a failed mutation (problem detail or field errors). */
export function problemMessage(error: unknown): string {
  if (!isApiError(error)) return 'Something went wrong. Please try again.';
  const fields = Object.values(error.fieldErrors);
  return fields.length ? fields.join(' ') : (error.problem.detail ?? error.problem.title);
}

export function NoAccess({ role }: { role: string }) {
  const t = useT();
  return (
    <div role="alert" className="flex flex-col items-center gap-3 rounded-md border border-border bg-surface px-6 py-10 text-center">
      <LockSimple size={36} aria-hidden className="text-muted" />
      <strong className="text-lg">{t('sc.states.youDonTHaveAccess')}</strong>
      <p className="m-0 max-w-[60ch] text-sm text-muted">{t('sc.states.noAccessRole', { role })}</p>
    </div>
  );
}
