'use client';

import { sellerQueries } from '@afrotransact/api-client';
import { CheckCircle } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { StripeUpdatePanel } from '@/components/onboarding/StripeUpdatePanel';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { SettingsPage, useSectionAccess } from './SettingsShell';

const BUCKET: Record<string, string> = { past_due: 'Past due', currently_due: 'Due now', eventually_due: 'Due later', pending_verification: 'Verifying' };

function Row({ label, children }: { label: string; children: string | null | undefined }) {
  return (
    <div className="flex flex-wrap gap-x-4 gap-y-0.5 border-b border-border py-3 first:border-t">
      <dt className="w-56 max-w-full text-sm text-muted">{label}</dt>
      <dd className="m-0 min-w-0 flex-1 text-sm font-semibold">{children || '—'}</dd>
    </div>
  );
}

/** Settings > Payouts: the bank account payouts go to (held by Stripe, changed in Stripe's forms). Balances stay under Finance > Payouts. */
export function PayoutsSettingsView() {
  const t = useT();
  const { orgId } = useSeller();
  const { canEdit } = useSectionAccess('payouts');
  const q = useQuery(sellerQueries.compliance(sellerApi(), orgId));
  return (
    <SettingsPage section="payouts">
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {q.data ? (
        <>
          {q.data.payoutsPaused ? (
            <Notice tone="warning" title={t('sc.settings.payouts.paused')}>
              {q.data.message}
            </Notice>
          ) : null}
          <dl className="m-0" data-testid="payout-settings">
            <Row label={t('sc.settings.payouts.bank')}>{q.data.payoutBank?.last4 ? `${q.data.payoutBank.bankName ?? t('sc.settings.payouts.bankFallback')} ••${q.data.payoutBank.last4}` : t('sc.settings.payouts.noBank')}</Row>
            <Row label={t('sc.settings.payouts.status')}>{q.data.payoutsPaused ? t('sc.settings.payouts.statusPaused') : q.data.payoutsEnabled ? t('sc.settings.payouts.statusOn') : t('sc.settings.payouts.statusOff')}</Row>
          </dl>
          <StripeUpdatePanel actionLabel={t('sc.settings.payouts.change')} tour="settings-payouts-stripe" disabled={!canEdit} />
          <p className="m-0 text-sm">
            <Link href="/payouts" className="text-link underline">
              {t('sc.settings.payouts.balances')}
            </Link>
          </p>
        </>
      ) : null}
    </SettingsPage>
  );
}

/** Settings > Tax information: read-only. AfroTransact keeps only the masked tax ID; Stripe holds the rest. */
export function TaxSettingsView() {
  const t = useT();
  const { orgId } = useSeller();
  const q = useQuery(sellerQueries.onboarding(sellerApi(), orgId));
  const d = q.data?.form.details;
  const address = d?.address ? [d.address.line1, d.address.city, [d.address.state, d.address.postalCode].filter(Boolean).join(' ')].filter(Boolean).join(', ') : null;
  return (
    <SettingsPage section="tax">
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {d ? (
        <>
          <dl className="m-0" data-testid="tax-info">
            <Row label={t('sc.settings.tax.legalName')}>{d.legalName ?? ([d.legalFirstName, d.legalLastName].filter(Boolean).join(' ') || null)}</Row>
            <Row label={t('sc.settings.tax.taxId')}>{d.taxIdMasked ? `${d.taxIdType ?? ''} ${d.taxIdMasked}`.trim() : t('sc.settings.tax.notOnFile')}</Row>
            <Row label={t('sc.settings.tax.address')}>{address}</Row>
            <Row label={t('sc.settings.tax.formation')}>{[d.stateOfFormation, d.dateOfFormation ? shortDate(d.dateOfFormation) : null].filter(Boolean).join(' · ') || null}</Row>
          </dl>
          <p className="m-0 max-w-[80ch] text-sm text-muted">{t('sc.settings.tax.note')}</p>
          <p className="m-0 flex flex-wrap gap-x-4 gap-y-1 text-sm">
            <Link href="/settings/documents" className="text-link underline">
              {t('sc.settings.tax.changeLink')}
            </Link>
            <Link href="/payouts/tax" className="text-link underline">
              {t('sc.settings.tax.statementLink')}
            </Link>
          </p>
        </>
      ) : null}
    </SettingsPage>
  );
}

/** Settings > Documents & compliance: what Stripe still needs from the business, and where to answer it. */
export function DocumentsSettingsView() {
  const t = useT();
  const { orgId } = useSeller();
  const { canEdit } = useSectionAccess('documents');
  const q = useQuery(sellerQueries.compliance(sellerApi(), orgId));
  return (
    <SettingsPage section="documents">
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {q.data ? (
        <>
          {q.data.items.length ? (
            <ul className="m-0 flex list-none flex-col p-0" data-testid="documents-items">
              {q.data.items.map((i) => (
                <li key={i.requirement} className="flex flex-wrap items-center gap-3 border-b border-border py-3 first:border-t">
                  <strong className="min-w-0 flex-1 text-sm">{i.label}</strong>
                  <span className="text-xs font-semibold text-warning">{BUCKET[i.bucket] ?? i.bucket}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="m-0 flex items-center gap-2 text-sm text-success">
              <CheckCircle size={18} weight="fill" aria-hidden /> {t('sc.settings.documents.none')}
            </p>
          )}
          {q.data.deadline ? <p className="m-0 text-sm">{t('sc.settings.documents.deadline', { date: shortDate(q.data.deadline) })}</p> : null}
          <StripeUpdatePanel actionLabel={q.data.items.length ? t('sc.settings.documents.resolve') : t('sc.settings.documents.review')} tour="settings-documents-stripe" disabled={!canEdit} />
          <p className="m-0 text-sm">
            <Link href="/account-health" className="text-link underline">
              {t('sc.settings.documents.health')}
            </Link>
          </p>
        </>
      ) : null}
    </SettingsPage>
  );
}
