'use client';

import { type RunLogEntry, runOutlook, type StaffRunDetail, staffRunKeys, staffRunQueries } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, cx, Dialog, Sheet, TextField, useToast } from '@afrotransact/ui';
import { CheckCircle, Circle, XCircle } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ReactNode, useId, useState } from 'react';

import { controlClass, Pill } from '@/components/preorders/primitives';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';

import { toZonedLocal, zonedInstant, zoneOf } from './model';
import { useAreaText } from './RunArea';
import { OrdersMeter, useOutcomeText } from './RunList';
import { RunPnlPanel } from './RunPnlPanel';
import { CARD, REASON_MAX, reasonText, RUN_STATUS_PILL, runStatusLabel, useNow, useRunsApi } from './shared';

const STEPS = ['DRAFT', 'ANNOUNCED', 'OPEN', 'CONFIRMED', 'COLLECTING', 'OUT_FOR_DELIVERY', 'COMPLETED'] as const;
const STEP_OF: Record<string, number> = { DRAFT: 0, ANNOUNCED: 1, OPEN: 2, EXTENDED: 2, CUT_OFF: 2, CONFIRMED: 3, COLLECTING: 4, OUT_FOR_DELIVERY: 5, COMPLETED: 6 };

/** The run's life as steps: what happened and when, where it is now, and (when cancelled) where it ended. */
function Timeline({ run, tz }: { run: StaffRunDetail; tz: string }) {
  const t = useT();
  const fmt = useFormatters();
  const m = run.milestones ?? {};
  const stamps: Record<(typeof STEPS)[number], string | null | undefined> = {
    DRAFT: null,
    ANNOUNCED: m.announcedAt,
    OPEN: m.openedAt,
    CONFIRMED: m.confirmedAt,
    COLLECTING: m.collectingAt,
    OUT_FOR_DELIVERY: m.outForDeliveryAt,
    COMPLETED: m.completedAt
  };
  const cancelled = run.status === 'CANCELLED';
  const reached = STEPS.map((s, i) => (cancelled ? i === 0 || !!stamps[s] : i <= (STEP_OF[run.status] ?? 0)));
  const current = cancelled ? -1 : (STEP_OF[run.status] ?? 0);
  return (
    <ol aria-label={t('st.runs.lc.timeline')} className="m-0 flex list-none flex-wrap gap-x-5 gap-y-3 p-0" data-testid="run-timeline">
      {STEPS.map((s, i) => {
        const done = reached[i] ?? false;
        const stamp = stamps[s];
        return (
          <li key={s} aria-current={i === current ? 'step' : undefined} data-step={s} data-done={done} className={cx('flex min-w-[96px] items-start gap-2', done ? 'text-text' : 'text-subtle')}>
            {done ? <CheckCircle size={20} weight={i === current ? 'fill' : 'regular'} aria-hidden className="mt-0.5 text-success" /> : <Circle size={20} aria-hidden className="mt-0.5" />}
            <span className="flex flex-col text-sm">
              <span className={cx('font-semibold', i === current && 'text-text')}>{runStatusLabel(t, s)}</span>
              {stamp ? <span className="text-xs text-muted">{fmt.dateTime(stamp, tz)}</span> : null}
              {s === 'OPEN' && run.extensions && run.extensions > 0 ? <span className="text-xs text-muted">{t('st.runs.lc.timeline.extended', { n: run.extensions })}</span> : null}
            </span>
          </li>
        );
      })}
      {cancelled ? (
        <li data-step="CANCELLED" data-done="true" aria-current="step" className="flex min-w-[96px] items-start gap-2 text-danger" data-testid="timeline-cancelled">
          <XCircle size={20} weight="fill" aria-hidden className="mt-0.5" />
          <span className="flex flex-col text-sm">
            <span className="font-semibold">{runStatusLabel(t, 'CANCELLED')}</span>
            {m.cancelledAt ? <span className="text-xs text-muted">{fmt.dateTime(m.cancelledAt, tz)}</span> : null}
          </span>
        </li>
      ) : null}
    </ol>
  );
}

/** The banner staff read first: what is going to happen to the run at its cutoff, or why it was cancelled. */
function Outlook({ run, now, tz }: { run: StaffRunDetail; now: number; tz: string }) {
  const t = useT();
  const fmt = useFormatters();
  const outcomeText = useOutcomeText();
  if (run.status === 'CANCELLED') {
    return (
      <div role="status" data-testid="run-outlook" data-kind="CANCELLED" className="flex flex-col gap-1 rounded-md border border-border bg-red-50 p-4">
        <strong className="text-md text-red-700">{t('st.runs.lc.outlook.CANCELLED.title')}</strong>
        <p className="m-0 text-sm text-ink-800">{t('st.runs.lc.outlook.CANCELLED.help', { orders: run.reservedOrders })}</p>
        {run.cancelReason ? <p className="m-0 text-sm text-ink-800">{t('st.runs.runs.pnl.reason', { reason: reasonText(t, run.cancelReason) })}</p> : null}
      </div>
    );
  }
  const o = runOutlook(run, now);
  if (!o) return null;
  const when = fmt.dateTime(run.cutoffAt, tz);
  const vars = { short: o.short, orders: run.reservedOrders, min: run.minOrders, when };
  const tone = o.kind === 'AT_RISK' || o.kind === 'DECIDING' ? 'border-gold-500 bg-gold-50' : o.kind === 'ON_TRACK' ? 'border-green-600 bg-green-50' : 'border-border bg-surface-muted';
  return (
    <div role="status" data-testid="run-outlook" data-kind={o.kind} className={cx('flex flex-col gap-1 rounded-md border p-4', tone)}>
      <strong className="text-md">{t(`st.runs.lc.outlook.${o.kind}.title`, vars)}</strong>
      <p className="m-0 text-sm text-ink-800">{t(`st.runs.lc.outlook.${o.kind}.help`, vars)}</p>
      {o.kind !== 'ON_TRACK' ? <p className="m-0 text-sm font-semibold text-ink-900">{outcomeText(run, o.outcome)}</p> : null}
    </div>
  );
}

/** Orders against the minimum and the capacity, with the break-even. */
function Counts({ run }: { run: StaffRunDetail }) {
  const t = useT();
  const fmt = useFormatters();
  const label = t('st.runs.lc.ordersOf', { orders: run.reservedOrders, min: run.minOrders });
  return (
    <section aria-labelledby="run-counts-title" className={CARD} data-testid="run-counts">
      <h3 id="run-counts-title" className="m-0 text-md font-bold">
        {t('st.runs.lc.counts.title')}
      </h3>
      <div className="flex flex-wrap items-baseline gap-x-6 gap-y-1">
        <span className="text-xl font-black" data-testid="run-orders-count">
          {label}
        </span>
        <span className={cx('text-sm font-semibold', run.minimumMet ? 'text-success' : 'text-danger')}>{t(run.minimumMet ? 'st.runs.lc.counts.met' : 'st.runs.lc.counts.notMet')}</span>
      </div>
      <OrdersMeter orders={run.reservedOrders} min={run.minOrders} capacity={run.capacityOrders} label={label} />
      <dl className="m-0 flex flex-wrap gap-x-6 gap-y-1 text-sm">
        <div className="flex gap-1">
          <dt className="text-muted">{t('st.runs.lc.counts.minimum')}</dt>
          <dd className="m-0 font-semibold">{fmt.number(run.minOrders)}</dd>
        </div>
        <div className="flex gap-1">
          <dt className="text-muted">{t('st.runs.lc.counts.capacity')}</dt>
          <dd className="m-0 font-semibold" data-testid="run-capacity">
            {run.capacityOrders ? `${fmt.number(run.capacityOrders)}${run.ordersLeft !== null && run.ordersLeft !== undefined ? ` · ${t('st.runs.lc.counts.left', { left: run.ordersLeft })}` : ''}` : t('st.runs.lc.counts.noCapacity')}
          </dd>
        </div>
        <div className="flex gap-1">
          <dt className="text-muted">{t('st.runs.lc.counts.breakEven')}</dt>
          <dd className="m-0 font-semibold" data-testid="run-break-even-orders">
            {run.breakEvenOrders ? fmt.number(run.breakEvenOrders) : t('st.runs.lc.counts.breakEvenNone')}
          </dd>
        </div>
      </dl>
    </section>
  );
}

function Details({ run, tz }: { run: StaffRunDetail; tz: string }) {
  const t = useT();
  const fmt = useFormatters();
  const areaText = useAreaText();
  const rows: [string, ReactNode][] = [
    [t('st.runs.lc.detail.location'), run.locationName ?? ''],
    [t('st.runs.lc.detail.area'), areaText(run.location)],
    [t('st.runs.lc.detail.window'), `${fmt.dateTime(run.windowStart, tz)} - ${fmt.time(run.windowEnd, tz)}`],
    [
      t('st.runs.lc.detail.cutoff'),
      <>
        {fmt.dateTime(run.cutoffAt, tz)}
        {run.originalCutoffAt && run.originalCutoffAt !== run.cutoffAt ? <span className="block text-xs text-muted">{t('st.runs.lc.detail.cutoffWas', { when: fmt.dateTime(run.originalCutoffAt, tz) })}</span> : null}
      </>
    ],
    [t('st.runs.lc.detail.sellerPickup'), run.sellerPickupAt ? fmt.dateTime(run.sellerPickupAt, tz) : t('st.runs.lc.detail.notSet')],
    [t('st.runs.lc.detail.opens'), run.opensAt ? fmt.dateTime(run.opensAt, tz) : t('st.runs.lc.detail.byHand')],
    [t('st.runs.lc.detail.fee'), t('st.runs.lc.detail.feeValue', { fee: fmt.money(run.feeMinor, run.currency) })],
    [t('st.runs.lc.detail.drop'), t(`st.runs.drop.${run.dropMode === 'PICKUP_POINT' ? 'PICKUP_POINT' : 'DOORSTEP'}`)],
    [t('st.runs.lc.detail.policy'), t(`st.runs.lc.policy.${run.cutoffPolicy ?? 'EXTEND'}`, { days: run.extendDays ?? 7 })],
    [t('st.runs.lc.detail.notes'), run.notes ? run.notes : t('st.runs.lc.detail.noNotes')]
  ];
  return (
    <section aria-labelledby="run-details-title" className={CARD}>
      <h3 id="run-details-title" className="m-0 text-md font-bold">
        {t('st.runs.lc.detail.title')}
      </h3>
      <dl className="m-0 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-[minmax(0,180px)_1fr]" data-testid="run-details">
        {rows.map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="text-muted">{k}</dt>
            <dd className="m-0 break-words">{v}</dd>
          </div>
        ))}
      </dl>
      <p className="m-0 text-xs text-muted">{t('st.runs.lc.form.tz', { tz })}</p>
    </section>
  );
}

/** Who extended, confirmed or cancelled the run, when and why (the cutoff job is "the system"). */
function Decisions({ runId, tz }: { runId: string; tz: string }) {
  const t = useT();
  const fmt = useFormatters();
  const api = useRunsApi();
  const q = useQuery(staffRunQueries.runLog(api, runId));
  const why = (e: RunLogEntry): string | null => (e.reason ? reasonText(t, e.reason) : null);
  return (
    <section aria-labelledby="run-log-title" className={CARD} data-testid="run-log">
      <h3 id="run-log-title" className="m-0 text-md font-bold">
        {t('st.runs.lc.log.title')}
      </h3>
      {q.isPending ? <Spinner /> : q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : q.data.length === 0 ? <p className="m-0 text-sm text-muted">{t('st.runs.lc.log.empty')}</p> : (
        <ol aria-label={t('st.runs.lc.log.title')} className="m-0 flex list-none flex-col gap-3 p-0">
          {q.data.map((e) => (
            <li key={e.id} className="flex flex-col gap-0.5 text-sm" data-testid={`run-log-${e.kind}`}>
              <span className="font-semibold">{t(`st.runs.lc.log.kind.${e.kind}`)}</span>
              <span className="text-muted">{t('st.runs.lc.log.line', { who: e.bySystem ? t('st.runs.lc.log.system') : e.actor, when: fmt.dateTime(e.at, tz) })}</span>
              {e.kind === 'EXTEND' && e.fromCutoff && e.toCutoff ? <span>{t('st.runs.lc.log.cutoffMoved', { from: fmt.dateTime(e.fromCutoff, tz), to: fmt.dateTime(e.toCutoff, tz) })}</span> : null}
              <span className="text-muted">{t('st.runs.lc.log.orders', { orders: e.orders, min: e.minimum })}</span>
              {why(e) ? <span>{t('st.runs.runs.pnl.reason', { reason: why(e) ?? '' })}</span> : null}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

const ORDERS_SHOWN = 12;

/** The orders on the run, by number and customer name. */
function OrdersList({ runId }: { runId: string }) {
  const t = useT();
  const api = useRunsApi();
  const q = useQuery(staffRunQueries.runOrders(api, runId));
  return (
    <section aria-labelledby="run-orders-title" className={CARD} data-testid="run-orders">
      <h3 id="run-orders-title" className="m-0 text-md font-bold">
        {t('st.runs.lc.orders.title')}
      </h3>
      {q.isPending ? <Spinner /> : q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : q.data.length === 0 ? <p className="m-0 text-sm text-muted">{t('st.runs.lc.orders.empty')}</p> : (
        <>
          <div className="overflow-auto rounded-md border border-border">
            <table className="w-full min-w-[420px] border-collapse text-sm" aria-label={t('st.runs.lc.orders.table')}>
              <thead className="bg-surface-muted text-left">
                <tr className="text-2xs font-bold tracking-[0.06em] text-muted uppercase">
                  <th scope="col" className="px-3 py-2">
                    {t('st.runs.lc.orders.order')}
                  </th>
                  <th scope="col" className="px-3 py-2">
                    {t('st.runs.lc.orders.customer')}
                  </th>
                  <th scope="col" className="px-3 py-2">
                    {t('st.runs.lc.orders.status')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {q.data.slice(0, ORDERS_SHOWN).map((o) => (
                  <tr key={o.id} className="border-t border-border">
                    <td className="px-3 py-2 font-semibold">{o.orderNumber ?? t('st.runs.lc.orders.unknown')}</td>
                    <td className="px-3 py-2">{o.customerName ?? t('st.runs.lc.orders.unknownCustomer')}</td>
                    <td className="px-3 py-2">{t(`st.runs.lc.orders.status.${o.status}`)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {q.data.length > ORDERS_SHOWN ? <p className="m-0 text-xs text-muted">{t('st.runs.lc.orders.more', { n: q.data.length - ORDERS_SHOWN })}</p> : null}
        </>
      )}
    </section>
  );
}

/** A modal that asks for what an action needs (a reason and sometimes more), then does it. The reason is logged with who and when. */
function ActionDialog({
  open,
  title,
  help,
  reasonLabel,
  reasonRequired = true,
  confirmLabel,
  danger = false,
  extra,
  blocked = null,
  onClose,
  onSubmit
}: {
  open: boolean;
  title: string;
  help: string;
  reasonLabel?: string;
  reasonRequired?: boolean;
  confirmLabel: string;
  danger?: boolean;
  extra?: ReactNode;
  /** Why the action cannot be sent yet (shown under the fields). */
  blocked?: string | null;
  onClose: () => void;
  onSubmit: (reason: string) => Promise<unknown>;
}) {
  const t = useT();
  const id = useId();
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const trimmed = reason.trim();
  const problem = reasonLabel ? (trimmed.length > REASON_MAX ? t('st.runs.reason.tooLong', { max: REASON_MAX }) : reasonRequired && !trimmed ? t('st.runs.reason.required') : null) : null;
  const close = () => {
    setReason('');
    setTouched(false);
    setFailure(null);
    onClose();
  };
  const submit = async () => {
    setTouched(true);
    if (problem || blocked) return;
    setSaving(true);
    setFailure(null);
    try {
      await onSubmit(trimmed);
      close();
    } catch (e) {
      setFailure(problemMessage(e));
    } finally {
      setSaving(false);
    }
  };
  return (
    <Dialog open={open} onOpenChange={(o) => !o && close()} title={title} description={help} width={520} closeLabel={t('st.runs.reason.close')}>
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        {extra}
        {reasonLabel ? (
          <div className="flex flex-col gap-1">
            <label htmlFor={`${id}-reason`} className="text-sm font-semibold">
              {reasonLabel}
            </label>
            <textarea
              id={`${id}-reason`}
              value={reason}
              rows={3}
              maxLength={REASON_MAX + 50}
              aria-invalid={touched && problem !== null ? true : undefined}
              aria-describedby={touched && problem ? `${id}-reason-problem` : undefined}
              onChange={(e) => setReason(e.currentTarget.value)}
              className={`${controlClass} h-auto py-2`}
            />
            {touched && problem ? (
              <span id={`${id}-reason-problem`} className="text-xs font-normal text-danger">
                {problem}
              </span>
            ) : null}
          </div>
        ) : null}
        {touched && blocked ? (
          <p role="alert" className="m-0 text-sm text-danger">
            {blocked}
          </p>
        ) : null}
        {failure ? (
          <p role="alert" className="m-0 text-sm text-danger" data-testid="action-failure">
            {failure}
          </p>
        ) : null}
        <div className="-mx-5 -mb-5 flex justify-end gap-2 border-t border-border px-5 py-4">
          <Button variant="neutral" onClick={close}>
            {t('st.runs.lc.dialog.back')}
          </Button>
          <button
            type="submit"
            disabled={saving}
            className={cx('h-10 cursor-pointer rounded-md border-0 px-5 font-bold disabled:cursor-not-allowed disabled:opacity-45', danger ? 'bg-red-600 text-ink-0' : 'bg-green-600 text-ink-0')}
          >
            {confirmLabel}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

type Pending = 'extend' | 'confirm' | 'cancel' | 'delete' | null;

/** The actions the run's status allows, for a staff member who can manage pre-orders; read-only text for everyone else. */
function Actions({ run, tz, onEdit, onGone }: { run: StaffRunDetail; tz: string; onEdit: (run: StaffRunDetail) => void; onGone: () => void }) {
  const t = useT();
  const fmt = useFormatters();
  const api = useRunsApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [dialog, setDialog] = useState<Pending>(null);
  const [newCutoff, setNewCutoff] = useState('');
  const now = useNow();
  const a = run.allowed;
  const any = a.edit || a.announce || a.open || a.extend || a.confirm || a.cancel || a.delete;
  const apply = useMutation({
    mutationFn: ({ op }: { op: () => Promise<StaffRunDetail | void>; done: MessageKey }) => op(),
    onSuccess: (next, { done }) => {
      if (next) qc.setQueryData(staffRunKeys.run(run.id), next);
      void qc.invalidateQueries({ queryKey: staffRunKeys.all });
      toast.show(t(done));
    },
    onError: (e) => {
      void qc.invalidateQueries({ queryKey: staffRunKeys.run(run.id) });
      toast.show(problemMessage(e), 'error');
    }
  });
  const onDone = (next: StaffRunDetail, done: MessageKey) => {
    qc.setQueryData(staffRunKeys.run(run.id), next);
    void qc.invalidateQueries({ queryKey: staffRunKeys.all });
    toast.show(t(done));
  };
  if (!run.can.manage) {
    return (
      <p className="m-0 text-sm text-muted" data-testid="run-read-only">
        {t('st.runs.lc.act.readOnly')}
      </p>
    );
  }
  if (!any) {
    return (
      <p className="m-0 text-sm text-muted" data-testid="run-no-actions">
        {t('st.runs.lc.act.none')}
      </p>
    );
  }
  const cutoffMs = Date.parse(run.cutoffAt);
  const typedInstant = newCutoff ? zonedInstant(newCutoff, tz) : null;
  const extendBlocked = !typedInstant ? t('st.runs.lc.problem.required') : Date.parse(typedInstant) <= cutoffMs ? t('st.runs.lc.extend.problem.later') : Date.parse(typedInstant) <= now ? t('st.runs.lc.problem.past') : null;
  const below = !run.minimumMet;
  return (
    <div className="flex flex-col gap-2" data-testid="run-actions">
      <div className="flex flex-wrap gap-2">
        {a.edit ? (
          <Button variant="neutral" onClick={() => onEdit(run)}>
            {t('st.runs.lc.act.edit')}
          </Button>
        ) : null}
        {a.announce ? (
          <Button variant="neutral" loading={apply.isPending} onClick={() => apply.mutate({ op: () => api.announceRun(run.id), done: 'st.runs.lc.toast.announced' })}>
            {t('st.runs.lc.act.announce')}
          </Button>
        ) : null}
        {a.open ? (
          <Button variant="secondary" loading={apply.isPending} onClick={() => apply.mutate({ op: () => api.openRun(run.id), done: 'st.runs.lc.toast.opened' })}>
            {t('st.runs.lc.act.open')}
          </Button>
        ) : null}
        {a.extend ? (
          <Button
            variant="neutral"
            onClick={() => {
              setNewCutoff(toZonedLocal(new Date(cutoffMs + (run.extendDays ?? 7) * 86_400_000).toISOString(), tz));
              setDialog('extend');
            }}
          >
            {t('st.runs.lc.act.extend')}
          </Button>
        ) : null}
        {a.confirm ? (
          <Button variant="secondary" onClick={() => setDialog('confirm')}>
            {t(below ? 'st.runs.lc.act.confirmBelow' : 'st.runs.lc.act.confirm')}
          </Button>
        ) : null}
        {a.cancel ? (
          <button type="button" onClick={() => setDialog('cancel')} className="h-10 cursor-pointer rounded-md border border-red-600 bg-ink-0 px-4 font-bold text-red-700">
            {t('st.runs.lc.act.cancel')}
          </button>
        ) : null}
        {a.delete ? (
          <Button variant="neutral" onClick={() => setDialog('delete')}>
            {t('st.runs.lc.act.delete')}
          </Button>
        ) : null}
      </div>

      <ActionDialog
        open={dialog === 'extend'}
        title={t('st.runs.lc.extend.title')}
        help={t('st.runs.lc.extend.help')}
        reasonLabel={t('st.runs.lc.extend.reason')}
        confirmLabel={t('st.runs.lc.extend.confirm')}
        blocked={extendBlocked}
        onClose={() => setDialog(null)}
        extra={
          <TextField
            type="datetime-local"
            label={t('st.runs.lc.extend.newCutoff')}
            help={t('st.runs.lc.extend.current', { when: fmt.dateTime(run.cutoffAt, tz) })}
            value={newCutoff}
            onChange={(e) => setNewCutoff(e.currentTarget.value)}
          />
        }
        onSubmit={(reason) => api.extendRun(run.id, { cutoffAt: typedInstant ?? run.cutoffAt, reason }).then((next) => onDone(next, 'st.runs.lc.toast.extended'))}
      />
      <ActionDialog
        open={dialog === 'confirm'}
        title={t('st.runs.lc.confirm.title')}
        help={below ? t('st.runs.lc.confirm.below', { orders: run.reservedOrders, min: run.minOrders }) : t('st.runs.lc.confirm.help')}
        reasonLabel={below ? t('st.runs.lc.confirm.reason') : undefined}
        confirmLabel={t('st.runs.lc.confirm.button')}
        onClose={() => setDialog(null)}
        onSubmit={(reason) => api.confirmRun(run.id, below ? reason : undefined).then((next) => onDone(next, 'st.runs.lc.toast.confirmed'))}
      />
      <ActionDialog
        open={dialog === 'cancel'}
        title={t('st.runs.lc.cancel.title')}
        help={t('st.runs.lc.cancel.help', { orders: run.reservedOrders })}
        reasonLabel={t('st.runs.lc.cancel.reason')}
        confirmLabel={t('st.runs.lc.cancel.button')}
        danger
        onClose={() => setDialog(null)}
        onSubmit={(reason) => api.cancelRun(run.id, reason).then((next) => onDone(next, 'st.runs.lc.toast.cancelled'))}
      />
      <ActionDialog
        open={dialog === 'delete'}
        title={t('st.runs.lc.delete.title')}
        help={t('st.runs.lc.delete.help')}
        confirmLabel={t('st.runs.lc.delete.button')}
        danger
        onClose={() => setDialog(null)}
        onSubmit={() =>
          api.deleteRun(run.id).then(() => {
            void qc.invalidateQueries({ queryKey: staffRunKeys.all });
            toast.show(t('st.runs.lc.toast.deleted'));
            onGone();
          })
        }
      />
    </div>
  );
}

function RunSheetBody({ runId, onClose, onEdit }: { runId: string; onClose: () => void; onEdit: (run: StaffRunDetail) => void }) {
  const t = useT();
  const fmt = useFormatters();
  const api = useRunsApi();
  const now = useNow();
  const q = useQuery(staffRunQueries.run(api, runId));
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const run = q.data;
  const tz = zoneOf(run.locationTimeZone ?? run.location?.timeZone);
  return (
    <div className="flex flex-col gap-4" data-testid="run-sheet">
      <div className="flex flex-wrap items-center gap-2">
        <Pill className={RUN_STATUS_PILL[run.status] ?? 'bg-ink-50 text-ink-600'} testId="run-sheet-status">
          {runStatusLabel(t, run.status)}
        </Pill>
        <span className="text-sm text-muted">{`${fmt.dateTime(run.windowStart, tz)} - ${fmt.time(run.windowEnd, tz)}`}</span>
      </div>
      <Actions run={run} tz={tz} onEdit={onEdit} onGone={onClose} />
      <Outlook run={run} now={now} tz={tz} />
      <Timeline run={run} tz={tz} />
      <Counts run={run} />
      <Details run={run} tz={tz} />
      <Decisions runId={run.id} tz={tz} />
      <OrdersList runId={run.id} />
      <RunPnlPanel key={`${run.id}-${run.status}`} runId={run.id} />
    </div>
  );
}

/** A delivery run on the right: its status timeline, orders against the minimum and capacity, what staff may do, who decided what. */
export function RunSheet({ runId, onClose, onEdit }: { runId: string | null; onClose: () => void; onEdit: (run: StaffRunDetail) => void }) {
  const t = useT();
  const fmt = useFormatters();
  const api = useRunsApi();
  const q = useQuery({ ...staffRunQueries.run(api, runId ?? ''), enabled: runId !== null });
  const title = q.data ? t('st.runs.lc.sheet.title', { town: q.data.locationName ?? '', date: fmt.date(q.data.windowStart, 'medium', zoneOf(q.data.locationTimeZone ?? q.data.location?.timeZone)) }) : t('st.runs.lc.sheet.loading');
  return (
    <Sheet open={runId !== null} onOpenChange={(o) => (o ? undefined : onClose())} title={title} closeLabel={t('st.runs.reason.close')}>
      {runId ? <RunSheetBody key={runId} runId={runId} onClose={onClose} onEdit={onEdit} /> : null}
    </Sheet>
  );
}
