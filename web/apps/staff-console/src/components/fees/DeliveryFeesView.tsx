'use client';

import { staffDeliveryFeeKeys, staffDeliveryFeeQueries } from '@afrotransact/api-client';
import {
  type BandDraft,
  bandDraftsOf,
  type BandProblem,
  bandsFromDrafts,
  type DeliveryFeeInput,
  type DeliveryFeeRule,
  FEE_MAX_BANDS,
  FEE_MAX_MILES,
  FEE_MAX_MINOR,
  FEE_METHODS,
  FEE_REASON_MAX,
  feeAmountText,
  type FeeMethod,
  type FeePreview,
  feeReasonProblem,
  feeRuleOf,
  milesText,
  parseFeeAmount,
  parseMiles
} from '@afrotransact/api-client/staff-delivery-fees';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, Dialog, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, type ReactNode, useMemo, useState } from 'react';

import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';

type T = Translator<MessageKey>;
type Money = (minor: number) => string;

const CURRENCIES = ['USD', 'CAD', 'GBP', 'EUR', 'NGN', 'GHS', 'KES', 'ZAR', 'JMD'];
const DEFAULT_CURRENCY = 'USD';
const CARD = 'flex min-w-0 flex-col gap-3 rounded-md border border-border bg-surface p-4';

/** The amount a person typed in a fee field: blank is `blank`, a bad amount is NaN. */
function amountOf(text: string, blank: number | null): number | null {
  const v = parseFeeAmount(text);
  return v === null ? blank : v;
}

/** A one-line description of a rule, for the change history. */
function summaryOf(rule: DeliveryFeeRule | null, t: T, money: Money): string {
  if (!rule) return t('st.deliveryFees.summary.none');
  if (rule.method === 'PICKUP') return t('st.deliveryFees.summary.pickup', { fee: money(rule.feeMinor) });
  if (rule.method === 'CARRIER') {
    return t('st.deliveryFees.summary.carrier', {
      handling: money(rule.handlingMinor),
      fallback: money(rule.fallbackMinor),
      free: rule.freeAboveMinor === null ? t('st.deliveryFees.summary.never') : money(rule.freeAboveMinor)
    });
  }
  const fees = rule.bands.map((b) => b.feeMinor);
  if (fees.length === 0) return t('st.deliveryFees.summary.none');
  return t('st.deliveryFees.summary.bands', { n: fees.length, miles: milesText(rule.coverageMiles), low: money(Math.min(...fees)), high: money(Math.max(...fees)) });
}

/** One section: its title and help, who last changed it, and a Save that asks for a reason before it saves. */
function Section({
  id,
  method,
  currency,
  rule,
  dirty,
  valid,
  canManage,
  onDiscard,
  onSave,
  children
}: {
  id: string;
  method: FeeMethod;
  currency: string;
  rule: DeliveryFeeRule | undefined;
  dirty: boolean;
  valid: boolean;
  canManage: boolean;
  onDiscard: () => void;
  onSave: (reason: string) => Promise<void>;
  children: ReactNode;
}) {
  const t = useT();
  const fmt = useFormatters();
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const title = t(`st.deliveryFees.method.${method}`);
  const close = () => {
    setAsking(false);
    setReason('');
    setProblem(null);
    setFailure(null);
  };
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const bad = feeReasonProblem(reason);
    if (bad) return setProblem(bad === 'required' ? t('st.deliveryFees.problem.required') : t('st.deliveryFees.problem.tooLong', { max: FEE_REASON_MAX }));
    setProblem(null);
    setFailure(null);
    setSaving(true);
    try {
      await onSave(reason.trim());
      close();
    } catch (err) {
      setFailure(problemMessage(err));
    } finally {
      setSaving(false);
    }
  };
  return (
    <section aria-labelledby={`${id}-title`} className={CARD} data-testid={`fees-${method}`}>
      <h3 id={`${id}-title`} className="m-0 text-md font-bold">
        {title}
      </h3>
      {children}
      {rule ? (
        <div className="flex flex-col gap-0.5 text-xs text-muted" data-testid={`fees-${method}-meta`}>
          <span>{t('st.deliveryFees.meta', { version: rule.version, at: fmt.dateTime(rule.updatedAt), who: rule.updatedBy ?? '—' })}</span>
          {rule.reason ? <span>{t('st.deliveryFees.meta.reason', { reason: rule.reason })}</span> : null}
        </div>
      ) : (
        <span className="text-xs text-muted">{t('st.deliveryFees.notSet', { currency })}</span>
      )}
      {canManage ? (
        <div className="flex flex-wrap gap-2">
          <Button disabled={!dirty || !valid} onClick={() => setAsking(true)}>
            {t('st.deliveryFees.save', { section: title })}
          </Button>
          <Button variant="neutral" disabled={!dirty} onClick={onDiscard}>
            {t('st.deliveryFees.discard')}
          </Button>
        </div>
      ) : null}
      <Dialog open={asking} onOpenChange={(o) => !o && close()} title={t('st.deliveryFees.dialog.title', { section: title, currency })} description={t('st.deliveryFees.dialog.help')} width={480} closeLabel={t('st.deliveryFees.dialog.close')}>
        <form noValidate className="flex flex-col gap-3" onSubmit={(e) => void submit(e)}>
          <TextField label={t('st.deliveryFees.dialog.reason')} value={reason} maxLength={FEE_REASON_MAX + 50} error={problem} onChange={(e) => setReason(e.currentTarget.value)} />
          {failure ? (
            <p role="alert" className="m-0 text-sm text-danger">
              {failure}
            </p>
          ) : null}
          <div className="flex justify-end gap-2">
            <Button variant="neutral" onClick={close}>
              {t('st.deliveryFees.dialog.cancel')}
            </Button>
            <Button type="submit" loading={saving}>
              {t('st.deliveryFees.dialog.confirm')}
            </Button>
          </div>
        </form>
      </Dialog>
    </section>
  );
}

type SaveFn = (method: FeeMethod, body: Omit<DeliveryFeeInput, 'reason'>, reason: string) => Promise<void>;

interface SectionProps {
  currency: string;
  rule: DeliveryFeeRule | undefined;
  canManage: boolean;
  save: SaveFn;
}

function bandProblemText(t: T, money: Money, p: BandProblem, after?: number): string {
  switch (p) {
    case 'notDistance':
      return t('st.deliveryFees.problem.notDistance');
    case 'tooFar':
      return t('st.deliveryFees.problem.tooFar', { max: FEE_MAX_MILES });
    case 'notAbove':
      return t('st.deliveryFees.problem.notAbove', { after: milesText(after ?? 0) });
    case 'notAmount':
      return t('st.deliveryFees.problem.notAmount');
    case 'tooHigh':
      return t('st.deliveryFees.problem.tooHigh', { max: money(FEE_MAX_MINOR) });
  }
}

/** AfroTransact's own delivery: a flat fee by distance band, inside the places we cover. */
function AfroSection({ currency, rule, canManage, save }: SectionProps) {
  const t = useT();
  const fmt = useFormatters();
  const money: Money = (minor) => fmt.money(minor, currency);
  const initial = useMemo(() => bandDraftsOf(rule), [rule]);
  const [rows, setRows] = useState<BandDraft[]>(initial);
  const dirty = JSON.stringify(rows) !== JSON.stringify(initial);
  const built = bandsFromDrafts(rows);
  const [shown, setShown] = useState(false);
  const problems = shown ? built.problems : null;
  const edit = (i: number, patch: Partial<BandDraft>) => {
    setRows((cur) => cur.map((r, j) => (j === i ? { ...r, ...patch } : r)));
    setShown(true);
  };
  const lastEnd = rows.length ? parseMiles(rows[rows.length - 1]?.upTo ?? '') : 0;
  return (
    <Section
      id="fees-afro"
      method="AFROTRANSACT_DELIVERY"
      currency={currency}
      rule={rule}
      dirty={dirty}
      valid={built.problems === null}
      canManage={canManage}
      onDiscard={() => {
        setRows(initial);
        setShown(false);
      }}
      onSave={(reason) => save('AFROTRANSACT_DELIVERY', { bands: built.bands }, reason)}
    >
      <p className="m-0 max-w-[80ch] text-xs text-muted">{t('st.deliveryFees.afro.help')}</p>
      <fieldset disabled={!canManage} className="m-0 flex flex-col gap-2 border-0 p-0">
        {rows.map((r, i) => {
          const prev = i === 0 ? 0 : parseMiles(rows[i - 1]?.upTo ?? '');
          const p = problems?.rows[i];
          return (
            <div key={i} className="grid grid-cols-[minmax(0,7rem)_minmax(0,1fr)_minmax(0,1fr)_auto] items-start gap-2">
              <span className="pt-7 text-xs text-muted">{t('st.deliveryFees.afro.from', { from: milesText(Number.isNaN(prev) ? 0 : prev) })}</span>
              <TextField
                label={t('st.deliveryFees.afro.upToLabel', { n: i + 1 })}
                inputMode="decimal"
                value={r.upTo}
                error={p?.upTo ? bandProblemText(t, money, p.upTo, p.after) : null}
                onChange={(e) => edit(i, { upTo: e.currentTarget.value })}
              />
              <TextField
                label={t('st.deliveryFees.afro.feeLabel', { n: i + 1 })}
                inputMode="decimal"
                value={r.fee}
                error={p?.fee ? bandProblemText(t, money, p.fee) : null}
                onChange={(e) => edit(i, { fee: e.currentTarget.value })}
              />
              {canManage && rows.length > 1 ? (
                <Button
                  variant="neutral"
                  size="sm"
                  className="mt-6"
                  aria-label={t('st.deliveryFees.afro.removeBand', { n: i + 1 })}
                  onClick={() => {
                    setRows((cur) => cur.filter((_, j) => j !== i));
                    setShown(true);
                  }}
                >
                  ×
                </Button>
              ) : (
                <span />
              )}
            </div>
          );
        })}
        {problems?.table ? (
          <p role="alert" className="m-0 text-xs text-danger">
            {problems.table === 'none' ? t('st.deliveryFees.problem.none') : t('st.deliveryFees.problem.tooMany', { max: FEE_MAX_BANDS })}
          </p>
        ) : null}
        {canManage ? (
          <div>
            <Button
              variant="neutral"
              size="sm"
              disabled={rows.length >= FEE_MAX_BANDS}
              onClick={() => setRows((cur) => [...cur, { upTo: Number.isNaN(lastEnd) ? '' : milesText(lastEnd + 25), fee: '' }])}
            >
              {t('st.deliveryFees.afro.addBand')}
            </Button>
          </div>
        ) : null}
      </fieldset>
      {rule && rule.coverageMiles > 0 ? <p className="m-0 text-xs font-semibold">{t('st.deliveryFees.afro.coverage', { miles: milesText(rule.coverageMiles) })}</p> : null}
    </Section>
  );
}

/** Carrier rate for orders shipped beyond coverage: the quote plus handling, a fallback, and free shipping over a subtotal. */
function CarrierSection({ currency, rule, canManage, save }: SectionProps) {
  const t = useT();
  const fmt = useFormatters();
  const money: Money = (minor) => fmt.money(minor, currency);
  const initial = useMemo(() => ({ handling: feeAmountText(rule?.handlingMinor ?? 0), fallback: feeAmountText(rule?.fallbackMinor ?? 0), free: feeAmountText(rule?.freeAboveMinor ?? null) }), [rule]);
  const [v, setV] = useState(initial);
  const dirty = JSON.stringify(v) !== JSON.stringify(initial);
  const handling = amountOf(v.handling, 0);
  const fallback = amountOf(v.fallback, 0);
  const free = amountOf(v.free, null);
  const check = (n: number | null, blankOk: boolean): BandProblem | null => (n === null ? (blankOk ? null : 'notAmount') : Number.isNaN(n) ? 'notAmount' : n > FEE_MAX_MINOR ? 'tooHigh' : null);
  const errs = { handling: check(handling, false), fallback: check(fallback, false), free: check(free, true) };
  const valid = !errs.handling && !errs.fallback && !errs.free;
  const text = (p: BandProblem | null) => (p ? bandProblemText(t, money, p) : null);
  return (
    <Section
      id="fees-carrier"
      method="CARRIER"
      currency={currency}
      rule={rule}
      dirty={dirty}
      valid={valid}
      canManage={canManage}
      onDiscard={() => setV(initial)}
      onSave={(reason) => save('CARRIER', { handlingMinor: handling ?? 0, fallbackMinor: fallback ?? 0, freeAboveMinor: free }, reason)}
    >
      <p className="m-0 max-w-[80ch] text-xs text-muted">{t('st.deliveryFees.carrier.help')}</p>
      <fieldset disabled={!canManage} className="m-0 grid gap-2 border-0 p-0 sm:grid-cols-3">
        <TextField label={t('st.deliveryFees.carrier.handling')} inputMode="decimal" value={v.handling} error={text(errs.handling)} onChange={(e) => setV({ ...v, handling: e.currentTarget.value })} />
        <TextField label={t('st.deliveryFees.carrier.fallback')} inputMode="decimal" value={v.fallback} error={text(errs.fallback)} onChange={(e) => setV({ ...v, fallback: e.currentTarget.value })} />
        <TextField label={t('st.deliveryFees.carrier.freeAbove')} inputMode="decimal" value={v.free} help={t('st.deliveryFees.carrier.freeAboveHelp')} error={text(errs.free)} onChange={(e) => setV({ ...v, free: e.currentTarget.value })} />
      </fieldset>
    </Section>
  );
}

/** Pickup: the fee to collect an order at a pickup point (often 0). */
function PickupSection({ currency, rule, canManage, save }: SectionProps) {
  const t = useT();
  const fmt = useFormatters();
  const money: Money = (minor) => fmt.money(minor, currency);
  const initial = useMemo(() => feeAmountText(rule?.feeMinor ?? 0), [rule]);
  const [fee, setFee] = useState(initial);
  const amount = amountOf(fee, 0);
  const problem: BandProblem | null = amount === null || Number.isNaN(amount) ? 'notAmount' : amount > FEE_MAX_MINOR ? 'tooHigh' : null;
  return (
    <Section
      id="fees-pickup"
      method="PICKUP"
      currency={currency}
      rule={rule}
      dirty={fee !== initial}
      valid={!problem}
      canManage={canManage}
      onDiscard={() => setFee(initial)}
      onSave={(reason) => save('PICKUP', { feeMinor: amount ?? 0 }, reason)}
    >
      <p className="m-0 max-w-[80ch] text-xs text-muted">{t('st.deliveryFees.pickup.help')}</p>
      <fieldset disabled={!canManage} className="m-0 max-w-xs border-0 p-0">
        <TextField label={t('st.deliveryFees.pickup.fee')} inputMode="decimal" value={fee} error={problem ? bandProblemText(t, money, problem) : null} onChange={(e) => setFee(e.currentTarget.value)} />
      </fieldset>
    </Section>
  );
}

/** What would a customer pay: the same resolution checkout runs, against the saved rules. */
function TryIt({ currency }: { currency: string }) {
  const t = useT();
  const fmt = useFormatters();
  const [method, setMethod] = useState<FeeMethod>('AFROTRANSACT_DELIVERY');
  const [distance, setDistance] = useState('');
  const [quote, setQuote] = useState('');
  const [items, setItems] = useState('');
  const [result, setResult] = useState<FeePreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const run = useMutation({
    mutationFn: () => {
      const miles = distance.trim() === '' ? null : parseMiles(distance);
      const q = parseFeeAmount(quote);
      const i = parseFeeAmount(items);
      if (miles !== null && Number.isNaN(miles)) throw new Error(t('st.deliveryFees.try.distanceBad'));
      if ((q !== null && Number.isNaN(q)) || (i !== null && Number.isNaN(i))) throw new Error(t('st.deliveryFees.problem.notAmount'));
      return staffApi().deliveryFees.preview({ method, currency, distanceMiles: miles, carrierQuoteMinor: q, itemsMinor: i });
    },
    onSuccess: (r) => {
      setError(null);
      setResult(r);
    },
    onError: (e) => {
      setResult(null);
      setError(e instanceof Error && !('status' in e) ? e.message : problemMessage(e));
    }
  });
  return (
    <section aria-labelledby="fees-try-title" className={CARD}>
      <div className="flex flex-col gap-1">
        <h3 id="fees-try-title" className="m-0 text-md font-bold">
          {t('st.deliveryFees.try.title')}
        </h3>
        <p className="m-0 text-xs text-muted">{t('st.deliveryFees.try.help')}</p>
      </div>
      <form
        noValidate
        className="grid gap-2 sm:grid-cols-4"
        onSubmit={(e) => {
          e.preventDefault();
          run.mutate();
        }}
      >
        <Select label={t('st.deliveryFees.try.method')} value={method} onChange={(e) => setMethod(e.currentTarget.value as FeeMethod)} options={FEE_METHODS.map((m) => ({ value: m, label: t(`st.deliveryFees.method.${m}`) }))} />
        {method === 'AFROTRANSACT_DELIVERY' ? <TextField label={t('st.deliveryFees.try.distance')} inputMode="decimal" value={distance} onChange={(e) => setDistance(e.currentTarget.value)} /> : null}
        {method === 'CARRIER' ? (
          <>
            <TextField label={t('st.deliveryFees.try.quote')} inputMode="decimal" value={quote} onChange={(e) => setQuote(e.currentTarget.value)} />
            <TextField label={t('st.deliveryFees.try.items')} inputMode="decimal" value={items} onChange={(e) => setItems(e.currentTarget.value)} />
          </>
        ) : null}
        <div className="flex items-end">
          <Button type="submit" variant="neutral" loading={run.isPending}>
            {t('st.deliveryFees.try.run')}
          </Button>
        </div>
      </form>
      <div aria-live="polite" data-testid="fees-try-result" className="text-sm">
        {error ? <span className="text-danger">{error}</span> : null}
        {result && !result.fee ? <span>{t('st.deliveryFees.try.notOffered')}</span> : null}
        {result?.fee ? (
          <div className="flex flex-col gap-0.5">
            <strong>{t('st.deliveryFees.try.result', { amount: fmt.money(result.fee.amountMinor, currency) })}</strong>
            <span className="text-xs text-muted">
              {t('st.deliveryFees.try.detail', { method: t(`st.deliveryFees.method.${result.fee.method}`), version: result.fee.ruleVersion, source: t(`st.deliveryFees.source.${result.fee.source}`) })}
            </span>
          </div>
        ) : null}
      </div>
    </section>
  );
}

/** Every change, newest first: what became what, who did it and why. */
function History({ currency }: { currency: string }) {
  const t = useT();
  const fmt = useFormatters();
  const money: Money = (minor) => fmt.money(minor, currency);
  const q = useQuery(staffDeliveryFeeQueries.changes(staffApi().deliveryFees, { currency, limit: 50 }));
  return (
    <section aria-labelledby="fees-history-title" className={CARD}>
      <h3 id="fees-history-title" className="m-0 text-md font-bold">
        {t('st.deliveryFees.history.title')}
      </h3>
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {q.data && q.data.length === 0 ? <p className="m-0 text-sm text-muted">{t('st.deliveryFees.history.empty')}</p> : null}
      {q.data && q.data.length > 0 ? (
        <ul aria-label={t('st.deliveryFees.history.title')} className="m-0 flex list-none flex-col gap-2 p-0">
          {q.data.map((c) => (
            <li key={c.id ?? `${c.method}-${c.currency}-${c.version}`} className="flex flex-col gap-0.5 border-b border-border pb-2 text-xs">
              <strong>
                {t('st.deliveryFees.history.line', { method: t(`st.deliveryFees.method.${c.method}`), currency: c.currency, version: c.version, who: c.actor, at: fmt.dateTime(c.at) })}
              </strong>
              <span>
                {c.before
                  ? t('st.deliveryFees.history.change', { before: summaryOf(c.before, t, money), after: summaryOf(c.after, t, money) })
                  : t('st.deliveryFees.history.first', { after: summaryOf(c.after, t, money) })}
              </span>
              <span className="text-muted">{t('st.deliveryFees.meta.reason', { reason: c.reason })}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}

/**
 * Delivery fees (ADR 0005 §3): one screen, three sections (AfroTransact delivery by distance band, carrier shipping beyond
 * coverage, pickup), per currency. Checkout, pre-order windows and runs all resolve a fee from these rules; each change
 * needs a reason and lands in the history. Those who may only view see the same screen without Save.
 */
export function DeliveryFeesView() {
  const t = useT();
  const toast = useToast();
  const qc = useQueryClient();
  const api = staffApi().deliveryFees;
  const rules = useQuery(staffDeliveryFeeQueries.rules(api));
  const [added, setAdded] = useState<string[]>([]);
  const [pick, setPick] = useState('');
  const [chosen, setChosen] = useState<string | null>(null);
  const items = rules.data?.items;
  const currencies = useMemo(() => [...new Set([...(items ?? []).map((r) => r.currency), ...added])].sort(), [items, added]);
  const currency = chosen && currencies.includes(chosen) ? chosen : currencies.includes(DEFAULT_CURRENCY) ? DEFAULT_CURRENCY : (currencies[0] ?? DEFAULT_CURRENCY);
  const update = useMutation({
    mutationFn: ({ method, body, reason }: { method: FeeMethod; body: Omit<DeliveryFeeInput, 'reason'>; reason: string }) =>
      api.update(method, currency, { ...body, reason }, feeRuleOf(items ?? [], method, currency)?.version ?? 0),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: staffDeliveryFeeKeys.all });
      toast.show(t('st.deliveryFees.toast.saved'));
    },
    onError: () => void qc.invalidateQueries({ queryKey: staffDeliveryFeeKeys.all })
  });
  if (rules.isPending) return <Spinner />;
  if (rules.isError) return <ErrorState error={rules.error} onRetry={() => void rules.refetch()} />;
  const canManage = rules.data.can.manage;
  const save: SaveFn = async (method, body, reason) => {
    await update.mutateAsync({ method, body, reason });
  };
  const section = (m: FeeMethod) => ({ currency, rule: feeRuleOf(rules.data.items, m, currency), canManage, save });
  return (
    <div className="flex flex-col gap-4" data-testid="delivery-fees">
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.deliveryFees.intro')}</p>
      {!canManage ? (
        <p role="note" className="m-0 rounded-md bg-ink-50 px-3 py-2 text-xs text-muted" data-testid="fees-read-only">
          {t('st.deliveryFees.readOnly')}
        </p>
      ) : null}
      <div className="flex flex-wrap items-end gap-3">
        <Select
          label={t('st.deliveryFees.currency.label')}
          value={currency}
          onChange={(e) => setChosen(e.currentTarget.value)}
          options={(currencies.length ? currencies : [DEFAULT_CURRENCY]).map((c) => ({ value: c, label: c }))}
        />
        {canManage ? (
          <>
            <Select
              label={t('st.deliveryFees.currency.add')}
              value={pick}
              placeholder={t('st.deliveryFees.currency.choose')}
              onChange={(e) => setPick(e.currentTarget.value)}
              options={CURRENCIES.filter((c) => !currencies.includes(c)).map((c) => ({ value: c, label: c }))}
            />
            <Button
              variant="neutral"
              disabled={!pick}
              onClick={() => {
                setAdded((cur) => [...cur, pick]);
                setChosen(pick);
                setPick('');
              }}
            >
              {t('st.deliveryFees.currency.addButton')}
            </Button>
          </>
        ) : null}
      </div>
      <AfroSection key={`afro-${currency}-${section('AFROTRANSACT_DELIVERY').rule?.version ?? 0}`} {...section('AFROTRANSACT_DELIVERY')} />
      <CarrierSection key={`carrier-${currency}-${section('CARRIER').rule?.version ?? 0}`} {...section('CARRIER')} />
      <PickupSection key={`pickup-${currency}-${section('PICKUP').rule?.version ?? 0}`} {...section('PICKUP')} />
      <TryIt key={`try-${currency}`} currency={currency} />
      <History currency={currency} />
    </div>
  );
}
