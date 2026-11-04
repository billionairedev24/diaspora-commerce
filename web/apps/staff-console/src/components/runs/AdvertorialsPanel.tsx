'use client';

import {
  ADVERTORIAL_LIMITS,
  type AdvertorialAction,
  advertorialActions,
  type AdvertorialInput,
  advertorialInputProblems,
  type AdvertorialSource,
  type AdvertorialStatus,
  type FeaturedProduct,
  type StaffAdvertorial,
  staffRunKeys,
  staffRunQueries
} from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, Dialog, Field, fieldStyles, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { fromLocalInput, toLocalInput } from '@/components/preorders/model';
import { Pill } from '@/components/preorders/primitives';
import { EmptyState, ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';

import { FeaturedProductsField } from './FeaturedProductsField';
import { CARD, useCanManageRuns, useRunsApi } from './shared';

const STATUS_PILL: Record<AdvertorialStatus, string> = {
  DRAFT: 'bg-ink-50 text-ink-600',
  LIVE: 'bg-green-50 text-green-800',
  PAUSED: 'bg-gold-100 text-gold-900',
  ENDED: 'bg-ink-100 text-ink-600'
};

interface FormState {
  variant: string;
  headline: string;
  body: string;
  ctaLabel: string;
  weight: string;
  priority: string;
  startsAt: string;
  endsAt: string;
  featuredProducts: FeaturedProduct[];
}

const emptyForm = (variant: string): FormState => ({ variant, headline: '', body: '', ctaLabel: '', weight: '1', priority: '0', startsAt: '', endsAt: '', featuredProducts: [] });

const formOf = (a: StaffAdvertorial): FormState => ({
  variant: a.variant,
  headline: a.headline,
  body: a.body,
  ctaLabel: a.ctaLabel,
  weight: String(a.weight),
  priority: String(a.priority),
  startsAt: a.startsAt ? toLocalInput(a.startsAt) : '',
  endsAt: a.endsAt ? toLocalInput(a.endsAt) : '',
  featuredProducts: a.featuredProducts
});

const wholeOrNaN = (text: string): number => (/^\d{1,4}$/.test(text.trim()) ? Number(text.trim()) : Number.NaN);

const inputOf = (f: FormState): AdvertorialInput => ({
  variant: f.variant.trim() || null,
  headline: f.headline,
  body: f.body,
  ctaLabel: f.ctaLabel,
  weight: wholeOrNaN(f.weight),
  priority: wholeOrNaN(f.priority),
  startsAt: f.startsAt ? fromLocalInput(f.startsAt) : null,
  endsAt: f.endsAt ? fromLocalInput(f.endsAt) : null,
  featuredProducts: f.featuredProducts
});

const PROBLEM_KEY: Record<string, MessageKey> = {
  required: 'st.runs.adv.problem.required',
  tooLong: 'st.runs.adv.problem.tooLong',
  range: 'st.runs.adv.problem.range',
  order: 'st.runs.adv.problem.order'
};

/** The words, weight, priority and schedule of one variant. */
function VariantDialog({
  open,
  title,
  initial,
  onClose,
  onSave
}: {
  open: boolean;
  title: string;
  initial: FormState;
  onClose: () => void;
  onSave: (input: AdvertorialInput) => Promise<void>;
}) {
  const t = useT();
  const [f, setF] = useState<FormState>(initial);
  const [shown, setShown] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const input = inputOf(f);
  const raw = advertorialInputProblems({ ...input, source: 'WINDOW', sourceId: 'x' }, false);
  if (Number.isNaN(input.weight ?? 0)) raw.weight = 'range';
  if (Number.isNaN(input.priority ?? 0)) raw.priority = 'range';
  const say = (field: string): string | null => {
    const k = shown ? raw[field] : undefined;
    if (!k) return null;
    if (field === 'featuredProducts') return t('st.runs.adv.featured.max', { max: ADVERTORIAL_LIMITS.featured });
    const limit = field === 'headline' ? ADVERTORIAL_LIMITS.headline : field === 'body' ? ADVERTORIAL_LIMITS.body : field === 'ctaLabel' ? ADVERTORIAL_LIMITS.ctaLabel : field === 'variant' ? ADVERTORIAL_LIMITS.variant : 0;
    return t(PROBLEM_KEY[k] ?? 'st.runs.adv.problem.required', { max: limit, weight: ADVERTORIAL_LIMITS.weightMax, priority: ADVERTORIAL_LIMITS.priorityMax });
  };
  const set = (patch: Partial<FormState>) => {
    setF((cur) => ({ ...cur, ...patch }));
    setShown(true);
  };
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setShown(true);
    if (Object.keys(raw).length > 0) return;
    setFailure(null);
    setSaving(true);
    try {
      await onSave(input);
      onClose();
    } catch (err) {
      setFailure(problemMessage(err));
    } finally {
      setSaving(false);
    }
  };
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} title={title} description={t('st.runs.adv.form.help')} width={560} closeLabel={t('st.runs.adv.form.close')}>
      <form noValidate className="flex flex-col gap-3" onSubmit={(e) => void submit(e)}>
        <TextField label={t('st.runs.adv.form.headline')} value={f.headline} error={say('headline')} help={t('st.runs.adv.form.count', { n: Array.from(f.headline).length, max: ADVERTORIAL_LIMITS.headline })} onChange={(e) => set({ headline: e.currentTarget.value })} />
        <Field label={t('st.runs.adv.form.body')} error={say('body')} help={t('st.runs.adv.form.count', { n: Array.from(f.body).length, max: ADVERTORIAL_LIMITS.body })}>
          {(a11y) => <textarea rows={3} className={fieldStyles.control} value={f.body} onChange={(e) => set({ body: e.currentTarget.value })} {...a11y} />}
        </Field>
        <TextField label={t('st.runs.adv.form.cta')} value={f.ctaLabel} error={say('ctaLabel')} onChange={(e) => set({ ctaLabel: e.currentTarget.value })} />
        <div className="grid gap-3 sm:grid-cols-3">
          <TextField label={t('st.runs.adv.form.variant')} value={f.variant} error={say('variant')} onChange={(e) => set({ variant: e.currentTarget.value })} />
          <TextField label={t('st.runs.adv.form.weight')} help={t('st.runs.adv.form.weightHelp')} inputMode="numeric" value={f.weight} error={say('weight')} onChange={(e) => set({ weight: e.currentTarget.value })} />
          <TextField label={t('st.runs.adv.form.priority')} help={t('st.runs.adv.form.priorityHelp')} inputMode="numeric" value={f.priority} error={say('priority')} onChange={(e) => set({ priority: e.currentTarget.value })} />
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          <TextField type="datetime-local" label={t('st.runs.adv.form.startsAt')} value={f.startsAt} onChange={(e) => set({ startsAt: e.currentTarget.value })} />
          <TextField type="datetime-local" label={t('st.runs.adv.form.endsAt')} value={f.endsAt} error={say('endsAt')} onChange={(e) => set({ endsAt: e.currentTarget.value })} />
        </div>
        <p className="m-0 text-xs text-muted">{t('st.runs.adv.form.scheduleHelp')}</p>
        <FeaturedProductsField value={f.featuredProducts} error={say('featuredProducts')} onChange={(next) => set({ featuredProducts: next })} />
        {failure ? (
          <p role="alert" className="m-0 text-sm text-danger">
            {failure}
          </p>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>
            {t('st.runs.adv.form.cancel')}
          </Button>
          <Button type="submit" loading={saving}>
            {t('st.runs.adv.form.save')}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function Results({ a }: { a: StaffAdvertorial }) {
  const t = useT();
  const fmt = useFormatters();
  const r = a.results;
  return (
    <dl className="m-0 flex flex-wrap gap-x-6 gap-y-1 text-xs" data-testid={`adv-results-${a.variant}`}>
      <div className="flex gap-1">
        <dt className="text-muted">{t('st.runs.adv.results.views')}</dt>
        <dd className="m-0 font-bold">{fmt.number(r.views)}</dd>
      </div>
      <div className="flex gap-1">
        <dt className="text-muted">{t('st.runs.adv.results.clicks')}</dt>
        <dd className="m-0 font-bold">{fmt.number(r.clicks)}</dd>
      </div>
      <div className="flex gap-1">
        <dt className="text-muted">{t('st.runs.adv.results.ctr')}</dt>
        <dd className="m-0 font-bold">{r.ctrPct === null ? '-' : `${fmt.number(r.ctrPct, { maximumFractionDigits: 1 })}%`}</dd>
      </div>
      <div className="flex gap-1">
        <dt className="text-muted">{t('st.runs.adv.results.orders')}</dt>
        <dd className="m-0 font-bold" data-testid={`adv-orders-${a.variant}`}>
          {r.orders === null ? t('st.runs.adv.results.notTracked') : fmt.number(r.orders)}
        </dd>
      </div>
      {r.cancelledOrders > 0 ? (
        <div className="flex gap-1">
          <dt className="text-muted">{t('st.runs.adv.results.cancelled')}</dt>
          <dd className="m-0 font-bold" data-testid={`adv-cancelled-${a.variant}`}>
            {fmt.number(r.cancelledOrders)}
          </dd>
        </div>
      ) : null}
    </dl>
  );
}

const ctrOf = (views: number, clicks: number): number | null => (views === 0 ? null : Math.round((clicks * 1000) / views) / 10);

/** The variants side by side: views, clicks, click rate, orders and each one's share of the views, with the total. */
function VariantResults({ items }: { items: StaffAdvertorial[] }) {
  const t = useT();
  const fmt = useFormatters();
  const sum = (pick: (a: StaffAdvertorial) => number) => items.reduce((n, a) => n + pick(a), 0);
  const totals = { views: sum((a) => a.results.views), clicks: sum((a) => a.results.clicks), orders: sum((a) => a.results.orders ?? 0), cancelled: sum((a) => a.results.cancelledOrders) };
  const rate = (value: number | null) => (value === null ? '-' : `${fmt.number(value, { maximumFractionDigits: 1 })}%`);
  const head = ['variant', 'views', 'clicks', 'ctr', 'orders', 'cancelled', 'share'] as const;
  const label = (c: (typeof head)[number]) => t(c === 'variant' ? 'st.runs.adv.compare.variant' : c === 'share' ? 'st.runs.adv.compare.share' : (`st.runs.adv.results.${c}` as MessageKey));
  return (
    <div className="flex flex-col gap-1" data-testid="adv-compare">
      <h4 className="m-0 text-sm font-bold">{t('st.runs.adv.compare.title')}</h4>
      <p className="m-0 max-w-[80ch] text-xs text-muted">{t('st.runs.adv.compare.caption')}</p>
      <div className="overflow-auto rounded-md border border-border">
        <table className="w-full min-w-[520px] border-collapse text-sm" aria-label={t('st.runs.adv.compare.title')}>
          <thead className="bg-surface-muted text-left">
            <tr className="text-2xs font-bold tracking-[0.06em] text-muted uppercase">
              {head.map((c, i) => (
                <th key={c} scope="col" className={i === 0 ? 'px-3 py-2' : 'px-3 py-2 text-right'}>
                  {label(c)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {items.map((a) => (
              <tr key={a.id} className="border-t border-border" data-testid={`adv-compare-${a.variant}`}>
                <th scope="row" className="px-3 py-2 text-left font-semibold">
                  {t('st.runs.adv.variant', { variant: a.variant })}
                </th>
                <td className="px-3 py-2 text-right">{fmt.number(a.results.views)}</td>
                <td className="px-3 py-2 text-right">{fmt.number(a.results.clicks)}</td>
                <td className="px-3 py-2 text-right">{rate(a.results.ctrPct)}</td>
                <td className="px-3 py-2 text-right">{a.results.orders === null ? '-' : fmt.number(a.results.orders)}</td>
                <td className="px-3 py-2 text-right">{fmt.number(a.results.cancelledOrders)}</td>
                <td className="px-3 py-2 text-right">{totals.views === 0 ? '-' : rate(Math.round((a.results.views * 1000) / totals.views) / 10)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="border-t border-border font-bold" data-testid="adv-compare-total">
              <th scope="row" className="px-3 py-2 text-left">
                {t('st.runs.adv.compare.total')}
              </th>
              <td className="px-3 py-2 text-right">{fmt.number(totals.views)}</td>
              <td className="px-3 py-2 text-right">{fmt.number(totals.clicks)}</td>
              <td className="px-3 py-2 text-right">{rate(ctrOf(totals.views, totals.clicks))}</td>
              <td className="px-3 py-2 text-right">{fmt.number(totals.orders)}</td>
              <td className="px-3 py-2 text-right">{fmt.number(totals.cancelled)}</td>
              <td className="px-3 py-2 text-right">{totals.views === 0 ? '-' : rate(100)}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </div>
  );
}

const ACTION_LABEL: Record<AdvertorialAction, MessageKey> = { publish: 'st.runs.adv.action.publish', pause: 'st.runs.adv.action.pause', end: 'st.runs.adv.action.end' };
const ACTION_DONE: Record<AdvertorialAction, MessageKey> = { publish: 'st.runs.adv.toast.published', pause: 'st.runs.adv.toast.paused', end: 'st.runs.adv.toast.ended' };

/**
 * Location-targeted advertorials of a pre-order window or a delivery run (S263): variants with their words, weight and
 * schedule, drafted from the window or run, published, paused or ended, with views, clicks and (for windows) orders.
 * A published advertorial is shown, with a live countdown, only while its window or run takes orders.
 */
export function AdvertorialsPanel({ source, sourceId }: { source: AdvertorialSource; sourceId: string }) {
  const t = useT();
  const fmt = useFormatters();
  const toast = useToast();
  const qc = useQueryClient();
  const api = useRunsApi();
  const canManage = useCanManageRuns();
  const list = useQuery(staffRunQueries.advertorials(api, { source, sourceId }));
  const [editing, setEditing] = useState<StaffAdvertorial | 'new' | null>(null);
  const refresh = () => void qc.invalidateQueries({ queryKey: staffRunKeys.all });
  const generate = useMutation({
    mutationFn: () => api.generateAdvertorial(source, sourceId),
    onSuccess: () => {
      refresh();
      toast.show(t('st.runs.adv.toast.drafted'));
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const act = useMutation({
    mutationFn: ({ a, action }: { a: StaffAdvertorial; action: AdvertorialAction }) => api.actAdvertorial(a.id, action, a.version),
    onSuccess: (_a, { action }) => {
      refresh();
      toast.show(t(ACTION_DONE[action]));
    },
    onError: (e) => {
      refresh();
      toast.show(problemMessage(e), 'error');
    }
  });
  if (list.isPending) return <Spinner />;
  if (list.isError) return <ErrorState error={list.error} onRetry={() => void list.refetch()} />;
  const items = list.data.items;
  const used = new Set(items.map((a) => a.variant));
  const nextVariant = 'ABCDEFGH'.split('').find((v) => !used.has(v)) ?? '';
  const save = async (input: AdvertorialInput) => {
    if (editing === 'new') await api.createAdvertorial({ ...input, source, sourceId });
    else if (editing) await api.updateAdvertorial(editing.id, input, editing.version);
    refresh();
    toast.show(t('st.runs.adv.toast.saved'));
  };
  return (
    <section aria-labelledby={`adv-title-${sourceId}`} className="flex flex-col gap-3" data-testid="advertorials">
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <h3 id={`adv-title-${sourceId}`} className="m-0 text-md font-bold">
            {t('st.runs.adv.title')}
          </h3>
          <p className="m-0 max-w-[80ch] text-xs text-muted">{t(source === 'WINDOW' ? 'st.runs.adv.help.window' : 'st.runs.adv.help.run')}</p>
        </div>
        {list.data.can.manage && canManage ? (
          <div className="flex flex-wrap gap-2">
            <Button variant="neutral" loading={generate.isPending} onClick={() => generate.mutate()}>
              {t('st.runs.adv.generate')}
            </Button>
            <Button onClick={() => setEditing('new')}>{t('st.runs.adv.write')}</Button>
          </div>
        ) : null}
      </div>
      {items.length === 0 ? <EmptyState title={t('st.runs.adv.empty.title')}>{t('st.runs.adv.empty.help')}</EmptyState> : null}
      {items.length > 1 ? <VariantResults items={items} /> : null}
      <ul aria-label={t('st.runs.adv.title')} className="m-0 grid list-none gap-3 p-0 md:grid-cols-2">
        {items.map((a) => (
          <li key={a.id} className={CARD} data-testid={`adv-${a.variant}`}>
            <div className="flex flex-wrap items-center gap-2">
              <strong className="text-md">{t('st.runs.adv.variant', { variant: a.variant })}</strong>
              <Pill className={STATUS_PILL[a.status]} testId={`adv-status-${a.variant}`}>
                {t(`st.runs.adv.status.${a.status}`)}
              </Pill>
              {a.serving ? <Pill className="bg-green-600 text-ink-0">{t('st.runs.adv.serving')}</Pill> : null}
              {a.generated ? <span className="text-xs text-muted">{t('st.runs.adv.generated')}</span> : null}
            </div>
            <div className="flex flex-col gap-1">
              <strong>{a.headline}</strong>
              {a.body ? <p className="m-0 text-sm">{a.body}</p> : null}
              <span className="text-xs text-muted">{t('st.runs.adv.cta', { label: a.ctaLabel })}</span>
              {a.featuredProducts.length > 0 ? (
                <span className="text-xs text-muted" data-testid={`adv-featured-${a.variant}`}>
                  {t('st.runs.adv.featured.on', { names: a.featuredProducts.map((p) => p.title).join(', ') })}
                </span>
              ) : null}
            </div>
            <span className="text-xs text-muted">
              {t('st.runs.adv.weighting', { weight: a.weight, priority: a.priority })}
              {a.startsAt ? ` · ${t('st.runs.adv.from', { at: fmt.dateTime(a.startsAt) })}` : ''}
              {a.endsAt ? ` · ${t('st.runs.adv.until', { at: fmt.dateTime(a.endsAt) })}` : ''}
              {a.ended ? ` · ${t(`st.runs.adv.ended.${a.ended}`)}` : ''}
            </span>
            <Results a={a} />
            {canManage ? (
              <div className="flex flex-wrap gap-2">
                {a.status !== 'ENDED' ? (
                  <Button variant="neutral" size="sm" onClick={() => setEditing(a)}>
                    {t('st.runs.adv.edit')}
                  </Button>
                ) : null}
                {advertorialActions(a.status).map((action) => (
                  <Button key={action} variant="neutral" size="sm" loading={act.isPending && act.variables?.a.id === a.id && act.variables.action === action} onClick={() => act.mutate({ a, action })}>
                    {t(ACTION_LABEL[action])}
                  </Button>
                ))}
              </div>
            ) : null}
          </li>
        ))}
      </ul>
      {editing ? (
        <VariantDialog
          key={editing === 'new' ? 'new' : editing.id}
          open
          title={editing === 'new' ? t('st.runs.adv.form.newTitle') : t('st.runs.adv.form.editTitle', { variant: editing.variant })}
          initial={editing === 'new' ? emptyForm(nextVariant) : formOf(editing)}
          onClose={() => setEditing(null)}
          onSave={save}
        />
      ) : null}
    </section>
  );
}
