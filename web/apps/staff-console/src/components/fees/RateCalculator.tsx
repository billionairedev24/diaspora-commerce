'use client';

import { type FeeKind, formatBps, staffFeeRateQueries, staffQueries } from '@afrotransact/api-client';
import { Button, Select, TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { Card } from '@/components/ui/Card';
import { ErrorState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { money } from '@/lib/format';

import { feeRatesApi } from './NegotiatedRatesPanel';
import { OrgSearch } from './OrgSearch';

/** What one organisation pays on one kind and category now, and why (payments' effective fees). */
export function RateCalculator() {
  const t = useT();
  const fees = useQuery(staffQueries.fees(staffApi()));
  const [org, setOrg] = useState<{ orgId: string; name: string } | null>(null);
  const [kind, setKind] = useState<FeeKind>('PRODUCT');
  const [category, setCategory] = useState('');
  const [asked, setAsked] = useState('');
  const quote = useQuery(staffFeeRateQueries.calculator(feeRatesApi(), org?.orgId ?? '', asked, kind));
  const categories = kind === 'SERVICE' ? (fees.data?.serviceCategories ?? []) : (fees.data?.categories ?? []);
  return (
    <Card title={t('st.rates.calculatorTitle')}>
      <p className="m-0 text-sm text-muted">{t('st.rates.calculatorHelp')}</p>
      {org ? (
        <p className="m-0 flex items-center gap-2 text-sm"><strong>{org.name}</strong><Button size="sm" variant="link" onClick={() => setOrg(null)}>{t('st.rates.change')}</Button></p>
      ) : fees.data ? (
        <OrgSearch overview={fees.data} label={t('st.rates.findOrg')} taken={() => false} onPick={(h) => setOrg({ orgId: h.orgId, name: h.name })} />
      ) : null}
      <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => { e.preventDefault(); setAsked(category.trim()); }}>
        <Select id="calc-kind" label={t('st.rates.kind')} value={kind} onChange={(e) => { setKind(e.currentTarget.value as FeeKind); setAsked(''); }}
          options={[{ value: 'PRODUCT', label: t('st.rates.kind.PRODUCT') }, { value: 'SERVICE', label: t('st.rates.kind.SERVICE') }]} />
        <TextField id="calc-category" label={t('st.rates.category')} list="calc-categories" value={category} onChange={(e) => setCategory(e.currentTarget.value)} />
        <datalist id="calc-categories">
          {categories.map((c) => <option key={c.key} value={c.key}>{c.label}</option>)}
        </datalist>
        <Button type="submit" className="mb-[calc(var(--at-space-1)+14px)]" disabled={!org || !category.trim()}>{t('st.rates.calculate')}</Button>
      </form>
      {quote.isFetching && !quote.data ? <Spinner /> : null}
      {quote.isError ? <ErrorState error={quote.error} onRetry={() => void quote.refetch()} /> : null}
      {quote.data ? (
        <dl className="m-0 grid grid-cols-[repeat(auto-fit,minmax(min(100%,180px),1fr))] gap-3 text-sm" aria-label={t('st.rates.result')} data-testid="rate-quote">
          <div><dt className="text-xs text-muted">{quote.data.kind === 'SERVICE' ? t('st.rates.connectionFee') : t('st.rates.commission')}</dt><dd className="m-0 text-lg font-semibold">{formatBps(quote.data.bps)}</dd></div>
          <div><dt className="text-xs text-muted">{t('st.rates.source')}</dt><dd className="m-0">{quote.data.explanation}</dd></div>
          <div><dt className="text-xs text-muted">{t('st.rates.example', { sale: money(quote.data.exampleSaleMinor) })}</dt><dd className="m-0">{money(quote.data.exampleFeeMinor)}</dd></div>
          <div><dt className="text-xs text-muted">{t('st.rates.version')}</dt><dd className="m-0">v{quote.data.version}</dd></div>
        </dl>
      ) : null}
    </Card>
  );
}
