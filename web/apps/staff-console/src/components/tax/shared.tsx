'use client';

import { type RecordTone, type TaxGate } from '@afrotransact/api-client';
import { Button, Dialog } from '@afrotransact/ui';
import { type ReactNode } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ReasonField } from '@/components/ui/ReasonField';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { useDocsUrl } from '@/lib/runtime-config';

/** The tone of each profile gate: the label always says it in words, the colour only helps. */
const GATE_TONE: Record<TaxGate, RecordTone> = { ACTIVE: 'good', NEEDS_APPROVAL: 'warn', REFINE_CODE: 'info', BLOCKED_BUNDLE: 'bad', CONFLICT_OPEN: 'bad' };

export function GatePill({ status, label }: { status: TaxGate; label: string }) {
  return <StatusPill tone={GATE_TONE[status]}>{label}</StatusPill>;
}

const RECORD_TONE: Record<string, RecordTone> = { PENDING_REVIEW: 'warn', APPROVED: 'good', REJECTED: 'bad', STALE: 'warn', MISSING_FACTS: 'info', DRAFT: 'info' };

export function RecordPill({ status, label }: { status: string; label: string }) {
  return <StatusPill tone={RECORD_TONE[status] ?? 'info'}>{label}</StatusPill>;
}

const CODE_TONE: Record<string, RecordTone> = { VERIFIED: 'good', UNVERIFIED: 'warn', UNMAPPED: 'bad', NOT_NEEDED: 'info' };

export function CodeStatePill({ state, label }: { state: string; label: string }) {
  return <StatusPill tone={CODE_TONE[state] ?? 'info'}>{label}</StatusPill>;
}

export function Section({ title, children, aside }: { title: string; children: ReactNode; aside?: ReactNode }) {
  return (
    <section aria-label={title} className="flex flex-col gap-2">
      <div className="flex items-baseline justify-between gap-3">
        <h3 className="m-0 text-sm font-bold">{title}</h3>
        {aside}
      </div>
      {children}
    </section>
  );
}

/** Label and value pairs in the flat definition-list style the sheets use. */
export function Facts({ rows }: { rows: readonly (readonly [string, ReactNode])[] }) {
  return (
    <dl className="m-0 grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-x-3 gap-y-1.5 text-sm">
      {rows.map(([k, v]) => (
        <div key={k} className="contents">
          <dt className="text-muted">{k}</dt>
          <dd className="m-0 [overflow-wrap:anywhere]">{v}</dd>
        </div>
      ))}
    </dl>
  );
}

export const mono = 'font-mono text-xs [overflow-wrap:anywhere]';

/**
 * Every tax change in one dialog: what is about to happen, the fields it needs, and the reason (10+ characters, the rule
 * shown as the person types). The Save button says why it is disabled by the field under it, never silently.
 */
export function ActionDialog({
  open,
  title,
  intro,
  submitLabel,
  reasonLabel,
  reasonHelp,
  reason,
  onReason,
  extraValid = true,
  pending,
  error,
  onSubmit,
  onClose,
  children,
  hideReason = false,
  minReason = 10
}: {
  open: boolean;
  title: string;
  intro?: ReactNode;
  submitLabel: string;
  reasonLabel?: string;
  reasonHelp?: string;
  reason: string;
  onReason: (v: string) => void;
  extraValid?: boolean;
  pending: boolean;
  error: string | null;
  onSubmit: () => void;
  onClose: () => void;
  children?: ReactNode;
  hideReason?: boolean;
  minReason?: number;
}) {
  const t = useT();
  const long = [...reason.trim()].length >= minReason;
  const canSubmit = (hideReason || long) && extraValid && !pending;
  return (
    <Dialog open={open} onOpenChange={(o) => !o && !pending && onClose()} title={title} width={520} closeLabel={t('st.depts.close')}>
      <form
        className="flex flex-col gap-4"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          if (canSubmit) onSubmit();
        }}
      >
        {intro ? <div className="text-sm">{intro}</div> : null}
        {children}
        {hideReason ? null : <ReasonField id="tax-reason" label={reasonLabel ?? 'Why are you doing this?'} help={reasonHelp} min={minReason} value={reason} onChange={onReason} />}
        {error ? (
          <Notice tone="danger" title="That didn’t go through" role="alert">
            {error}
          </Notice>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="neutral" disabled={pending} onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={pending} disabled={!canSubmit}>
            {submitLabel}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

/** A runbook from the repository (docs/runbooks/<name>.md) on the developer portal, named by its path. */
export function RunbookLink({ path, children }: { path: string; children?: ReactNode }) {
  const docs = useDocsUrl();
  const page = path.replace(/^docs\//, '').replace(/\.md$/, '');
  return (
    <a href={`${docs}/docs/${page}`} target="_blank" rel="noreferrer noopener" className="text-link">
      {children ?? <span className={mono}>{path}</span>}
      <span className="at-visually-hidden"> (opens in a new tab)</span>
    </a>
  );
}
