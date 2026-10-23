'use client';

import { sellerQueries } from '@afrotransact/api-client';
import { buttonClassName, Select } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { BFF_BASE_PATH } from '@/lib/config';
import { money } from '@/lib/format';
import { useSeller } from '@/lib/session';

const monthLabel = (month: string): string => {
  const [y, m] = month.split('-').map(Number);
  return new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(Date.UTC(y ?? 1970, (m ?? 1) - 1, 1)));
};

/** The sales tax AfroTransact collected on the seller's behalf, by state, for one month (tax-returns), with a CSV. */
export function TaxStatementView() {
  const t = useT();
  const api = sellerApi();
  const { orgId } = useSeller();
  const [month, setMonth] = useState('');
  const q = useQuery({ ...sellerQueries.taxStatement(api, orgId, month), placeholderData: (prev) => prev });
  const header = (
    <PageHeader
      title={t('sc.tax.title')}
      description={t('sc.tax.description')}
      actions={
        q.data ? (
          <>
            <Select
              label={t('sc.tax.month')}
              value={q.data.month}
              onChange={(e) => setMonth(e.target.value)}
              options={q.data.months.map((m) => ({ value: m, label: monthLabel(m) }))}
            />
            <a className={buttonClassName({ variant: 'neutral' })} href={`${BFF_BASE_PATH}/v1/seller/tax/statement/export?month=${encodeURIComponent(q.data.month)}`}>
              {t('sc.tax.download')}
            </a>
          </>
        ) : null
      }
    />
  );
  if (q.isPending) return <div className="flex flex-col gap-6">{header}<Spinner /></div>;
  if (q.isError) return <div className="flex flex-col gap-6">{header}<ErrorState error={q.error} onRetry={() => void q.refetch()} /></div>;
  const s = q.data;
  return (
    <div className="flex flex-col gap-6">
      {header}
      <Notice tone="info" title={t('sc.tax.notice')} />
      <Card title={monthLabel(s.month)} actions={<Link href="/payouts" className="text-sm">{t('sc.tax.backToPayouts')}</Link>}>
        {s.rows.length === 0 ? (
          <p className="m-0 text-sm text-muted">{t('sc.tax.empty')}</p>
        ) : (
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="at-table text-sm" aria-label={t('sc.tax.title')}>
              <thead className="bg-surface-muted text-left">
                <tr>
                  <th scope="col" className="px-3 py-2">{t('sc.tax.col.state')}</th>
                  <th scope="col" className="px-3 py-2 text-right">{t('sc.tax.col.orders')}</th>
                  <th scope="col" className="px-3 py-2 text-right">{t('sc.tax.col.sales')}</th>
                  <th scope="col" className="px-3 py-2 text-right">{t('sc.tax.col.collected')}</th>
                  <th scope="col" className="px-3 py-2 text-right">{t('sc.tax.col.refunded')}</th>
                  <th scope="col" className="px-3 py-2 text-right">{t('sc.tax.col.net')}</th>
                </tr>
              </thead>
              <tbody>
                {s.rows.map((r) => (
                  <tr key={r.jurisdiction} className="border-t border-border">
                    <th scope="row" className="px-3 py-2 text-left font-medium">{r.state}</th>
                    <td className="px-3 py-2 text-right">{r.orders}</td>
                    <td className="px-3 py-2 text-right">{money(r.grossSalesMinor, s.currency)}</td>
                    <td className="px-3 py-2 text-right">{money(r.taxCollectedMinor, s.currency)}</td>
                    <td className="px-3 py-2 text-right">{money(-r.taxRefundedMinor, s.currency)}</td>
                    <td className="px-3 py-2 text-right font-semibold">{money(r.netTaxMinor, s.currency)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="border-t border-border bg-surface-muted font-semibold">
                  <th scope="row" className="px-3 py-2 text-left">{t('sc.tax.total')}</th>
                  <td className="px-3 py-2 text-right">{s.totals.orders}</td>
                  <td className="px-3 py-2 text-right">{money(s.totals.grossSalesMinor, s.currency)}</td>
                  <td className="px-3 py-2 text-right">{money(s.totals.taxCollectedMinor, s.currency)}</td>
                  <td className="px-3 py-2 text-right">{money(-s.totals.taxRefundedMinor, s.currency)}</td>
                  <td className="px-3 py-2 text-right">{money(s.totals.netTaxMinor, s.currency)}</td>
                </tr>
              </tfoot>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
