'use client';

import { chargeTermsOf, type StaffPlan, type StaffPlanEntitlements, staffPlanQueries } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';

import { Card } from '@/components/ui/Card';
import { ErrorState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';

import { plansApi } from './kit';

interface FeatureRow {
  key: string;
  label: string;
  /** What the plan gets: text for a limit or tier, true for included, false for not included. */
  cell: (e: StaffPlanEntitlements) => string | boolean;
}

const cap = (s: string) => `${s.charAt(0)}${s.slice(1).toLowerCase()}`;

const FEATURES: FeatureRow[] = [
  { key: 'listings', label: 'Listings', cell: (e) => (e.listingsUnlimited || e.listings === null ? 'Unlimited' : e.listings.toLocaleString('en-US')) },
  { key: 'bulkUpload', label: 'Bulk upload', cell: (e) => e.bulkUpload },
  { key: 'imageLibrary', label: 'Image library', cell: (e) => e.imageLibrary },
  { key: 'coupons', label: 'Coupons', cell: (e) => e.coupons },
  { key: 'aiListingWriter', label: 'AI listing writer', cell: (e) => e.aiListingWriter },
  { key: 'lola', label: 'Lola assistant', cell: (e) => (e.lolaTier === 'NONE' ? false : cap(e.lolaTier)) }
];

/** Which plans unlock which feature: one row per capability, one column per live plan (read from each plan's limits). */
export function FeatureMatrixPanel() {
  const t = useT();
  const plans = useQuery(staffPlanQueries.plans(plansApi()));
  if (plans.isPending) return <Spinner />;
  if (plans.isError) return <ErrorState error={plans.error} onRetry={() => void plans.refetch()} />;
  const live = (plans.data ?? []).filter((p: StaffPlan) => p.status !== 'RETIRED' && p.entitlements);
  const groups = [
    { kind: 'SELLER' as const, plans: live.filter((p) => p.kind === 'SELLER') },
    { kind: 'PROVIDER' as const, plans: live.filter((p) => p.kind === 'PROVIDER') }
  ].filter((g) => g.plans.length > 0);
  return (
    <div className="flex flex-col gap-4">
      {groups.map((g) => (
        <Card key={g.kind} title={g.kind === 'SELLER' ? t('st.plans.matrix.sellers') : t('st.plans.matrix.providers')}>
          <p className="m-0 text-sm text-muted">{t('st.plans.matrix.help')}</p>
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="at-table text-sm" aria-label={g.kind === 'SELLER' ? t('st.plans.matrix.sellers') : t('st.plans.matrix.providers')}>
              <thead className="bg-surface-muted text-left">
                <tr>
                  <th scope="col" className="px-3 py-2">{t('st.plans.matrix.feature')}</th>
                  {g.plans.map((p) => (
                    <th key={p.code} scope="col" className="px-3 py-2 text-center">{p.name}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {FEATURES.map((f) => (
                  <tr key={f.key} className="border-t border-border">
                    <th scope="row" className="px-3 py-2 text-left font-medium">{f.label}</th>
                    {g.plans.map((p) => {
                      const v = p.entitlements ? f.cell(p.entitlements) : false;
                      return (
                        <td key={p.code} className="px-3 py-2 text-center">
                          {v === true ? <span aria-label={t('st.plans.matrix.included')} className="font-semibold">✓</span> : v === false ? <span aria-label={t('st.plans.matrix.notIncluded')} className="text-muted">—</span> : <span className="font-semibold">{v}</span>}
                        </td>
                      );
                    })}
                  </tr>
                ))}
                <tr className="border-t border-border">
                  <th scope="row" className="px-3 py-2 text-left font-medium">{t('st.plans.charges.preview')}</th>
                  {g.plans.map((p) => (
                    <td key={p.code} className="px-3 py-2 text-center text-xs">{chargeTermsOf(p.limits, p.monthlyCents)}</td>
                  ))}
                </tr>
              </tbody>
            </table>
          </div>
        </Card>
      ))}
    </div>
  );
}
