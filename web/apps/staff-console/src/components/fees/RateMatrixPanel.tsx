'use client';

import { type FeeKind, type FeesOverview, staffKeys, staffQueries } from '@afrotransact/api-client';
import { Button, Checkbox, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useMemo, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';

import { localInput, suggestedStart } from './model';
import { addRow, cellKey, columnRange, draftFromMatrix, matrixFrom, OTHER, type RateMatrix, setCell } from './rates-model';

type T = Translator<MessageKey>;

/** Category × (category or default rate, plan columns) for one section; editing saves a new fee-schedule version. */
function Matrix({ overview, kind, t }: { overview: FeesOverview; kind: FeeKind; t: T }) {
  const qc = useQueryClient();
  const toast = useToast();
  const base = overview.base ?? overview.current;
  const [matrix, setMatrix] = useState<RateMatrix>(() => matrixFrom(base, overview.plans, kind));
  const [editing, setEditing] = useState(false);
  const [newCategory, setNewCategory] = useState('');
  const [immediate, setImmediate] = useState(false);
  const [effectiveAt, setEffectiveAt] = useState(() => localInput(suggestedStart(overview.minEffectiveFrom, new Date())));
  const [note, setNote] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: () => {
      if (!base) throw new Error(t('st.rates.noBase'));
      const out = draftFromMatrix(matrix, base, overview, { immediate, effectiveFrom: immediate ? null : new Date(effectiveAt).toISOString() }, note, new Date());
      if (!out.draft) {
        setErrors(out.errors);
        throw new Error(t('st.rates.fixErrors'));
      }
      return staffApi().createFeeSchedule(out.draft);
    },
    onSuccess: (v) => {
      toast.show(t('st.rates.saved', { version: String(v.version) }));
      void qc.invalidateQueries({ queryKey: staffKeys.fees() });
      setEditing(false);
      setErrors({});
      setNote('');
    },
    onError: (e) => setErrors((prev) => (Object.keys(prev).length ? prev : { _: problemMessage(e) }))
  });
  const label = (key: string) =>
    key === OTHER ? (kind === 'SERVICE' ? t('st.rates.otherServices') : t('st.rates.otherCategories')) : key.charAt(0).toUpperCase() + key.slice(1);
  const baseHeader = kind === 'SERVICE' ? t('st.rates.connectionRate') : t('st.rates.categoryRate');
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setErrors({});
    save.mutate();
  };
  const cell = (key: string, column: string | null, value: string) =>
    editing ? (
      <input
        aria-label={`${label(key)} · ${column ? (matrix.plans.find((p) => p.code === column)?.name ?? column) : baseHeader}`}
        aria-invalid={Boolean(errors[cellKey(key, column)])}
        className="w-20 rounded-sm border border-border-strong bg-surface px-2 py-1 text-right text-sm aria-[invalid=true]:border-danger"
        inputMode="decimal"
        value={value}
        placeholder={column ? t('st.rates.inherit') : ''}
        onChange={(e) => {
          const text = e.currentTarget.value;
          setMatrix((m) => setCell(m, key, column, text));
        }}
      />
    ) : (
      <span className={value ? 'font-semibold' : 'text-muted'}>{value ? `${value}%` : '—'}</span>
    );
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-3">
      <div className="overflow-x-auto rounded-md border border-border">
        <table className="at-table text-sm" aria-label={kind === 'SERVICE' ? t('st.rates.connectionMatrix') : t('st.rates.commissionMatrix')}>
          <thead className="bg-surface-muted text-left">
            <tr>
              <th scope="col" className="px-3 py-2">{t('st.rates.category')}</th>
              <th scope="col" className="px-3 py-2 text-right">{baseHeader}</th>
              {matrix.plans.map((p) => (
                <th key={p.code} scope="col" className="px-3 py-2 text-right">
                  <span className="block">{p.name}</span>
                  <span className="block text-xs font-normal text-muted">{columnRange(matrix, p.code) ?? t('st.rates.noPlanRates')}</span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {matrix.rows.map((r) => (
              <tr key={r.key || 'other'} className="border-t border-border">
                <th scope="row" className="px-3 py-2 text-left font-medium">{label(r.key)}</th>
                <td className="px-3 py-2 text-right">{cell(r.key, null, r.base)}</td>
                {matrix.plans.map((p) => (
                  <td key={p.code} className="px-3 py-2 text-right">{cell(r.key, p.code, r.plans[p.code] ?? '')}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="m-0 text-xs text-muted">{t('st.rates.precedence')}</p>
      {Object.entries(errors).filter(([k]) => k.includes(':')).length ? (
        <p role="alert" className="m-0 text-sm text-danger">{t('st.rates.cellErrors', { count: String(Object.keys(errors).filter((k) => k.includes(':')).length) })}</p>
      ) : null}
      {overview.canConfigure && base ? (
        editing ? (
          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap items-end gap-2">
              <TextField id={`rates-${kind}-new`} label={t('st.rates.addCategory')} value={newCategory} onChange={(e) => setNewCategory(e.currentTarget.value)} />
              <Button variant="neutral" className="mb-[calc(var(--at-space-1)+14px)]" onClick={() => { setMatrix((m) => addRow(m, newCategory)); setNewCategory(''); }}>{t('st.rates.add')}</Button>
            </div>
            <Checkbox id={`rates-${kind}-now`} label={t('st.rates.immediate')} checked={immediate} disabled={!overview.immediateAllowed} onChange={(e) => setImmediate(e.currentTarget.checked)} />
            {!immediate ? (
              <TextField id={`rates-${kind}-at`} type="datetime-local" label={t('st.rates.effectiveFrom')} value={effectiveAt} error={errors.effectiveFrom} onChange={(e) => setEffectiveAt(e.currentTarget.value)} />
            ) : null}
            <TextField id={`rates-${kind}-note`} label={t('st.rates.note')} value={note} error={errors.note} onChange={(e) => setNote(e.currentTarget.value)} />
            {errors._ ? <p role="alert" className="m-0 text-sm text-danger">{errors._}</p> : null}
            <div className="flex flex-wrap gap-2">
              <Button type="submit" loading={save.isPending}>{t('st.rates.saveVersion')}</Button>
              <Button variant="neutral" onClick={() => { setEditing(false); setErrors({}); setMatrix(matrixFrom(base, overview.plans, kind)); }}>{t('st.rates.cancel')}</Button>
            </div>
          </div>
        ) : (
          <div>
            <Button variant="neutral" onClick={() => setEditing(true)}>{t('st.rates.edit')}</Button>
          </div>
        )
      ) : null}
    </form>
  );
}

/** Product commission or service connection fees: the matrix of the base version, with the plan feature check. */
export function RateMatrixPanel({ kind }: { kind: FeeKind }) {
  const t = useT();
  const fees = useQuery(staffQueries.fees(staffApi()));
  const o = fees.data;
  const warnings = useMemo(() => (o ? o.plans.filter((p) => (kind === 'SERVICE' ? p.kind === 'PROVIDER' : p.kind !== 'PROVIDER') && p.warning) : []), [o, kind]);
  if (fees.isPending) return <Spinner />;
  if (fees.isError) return <ErrorState error={fees.error} onRetry={() => void fees.refetch()} />;
  if (!o) return null;
  const version = o.base ?? o.current;
  return (
    <Card title={kind === 'SERVICE' ? t('st.rates.connectionTitle') : t('st.rates.commissionTitle')}>
      <p className="m-0 text-sm text-muted">
        {kind === 'SERVICE' ? t('st.rates.connectionHelp') : t('st.rates.commissionHelp')} {version ? t('st.rates.ofVersion', { version: String(version.version), status: version.status.toLowerCase() }) : null}
      </p>
      {!o.plansAvailable ? <Notice tone="warning" title={t('st.rates.plansUnavailable')} /> : null}
      {warnings.map((p) => (
        <Notice key={p.code} tone="warning" role="alert" title={t('st.rates.planMismatch')}>{p.warning}</Notice>
      ))}
      <Matrix key={`${kind}-${version?.id ?? 'none'}`} overview={o} kind={kind} t={t} />
    </Card>
  );
}
