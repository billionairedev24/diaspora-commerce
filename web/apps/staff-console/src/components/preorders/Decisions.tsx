'use client';

import { staffPreorderKeys, staffPreorderQueries } from '@afrotransact/api-client';
import {
  DECISION_REASON_MAX,
  decisionReasonProblem,
  type PreorderChoice,
  type PreorderDecision,
  type PreorderWindow,
  type RefundStatus
} from '@afrotransact/api-client/staff-preorders';
import { Dialog, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';

import { fDT } from './model';
import { controlClass, labelClass, Pill, usePreordersApi } from './primitives';

/** What a reasoned dialog is for: each one needs a reason, which is logged with who and when (ADR 0005 §4). */
export type DecisionAction = { kind: 'cancel' } | { kind: 'proceed' } | { kind: 'closeEarly' };

/** A window staff can still cancel: published, not delivered and not cancelled already. */
export const cancellable = (w: Pick<PreorderWindow, 'draft' | 'status'>): boolean => !w.draft && w.status !== 'DELIVERED' && w.status !== 'CANCELLED' && w.status !== 'DRAFT';

/** Cancel, proceed and close-early for one window: refreshes the family of queries and tells the staff member. */
export function useDecisionActions(w: PreorderWindow) {
  const api = usePreordersApi();
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ action, reason }: { action: DecisionAction; reason: string }) => {
      switch (action.kind) {
        case 'cancel':
          return w.decisionNeeded ? api.decide(w.id, 'CANCEL' satisfies PreorderChoice, reason, w.version) : api.cancel(w.id, reason, w.version);
        case 'proceed':
          return api.decide(w.id, 'PROCEED' satisfies PreorderChoice, reason, w.version);
        case 'closeEarly':
          return api.closeEarly(w.id, reason, w.version);
      }
    },
    onSuccess: (next, { action }) => {
      qc.setQueryData(staffPreorderKeys.window(next.id), next);
      void qc.invalidateQueries({ queryKey: staffPreorderKeys.all });
      toast.show(t(action.kind === 'cancel' ? 'st.preorders.toast.cancelled' : action.kind === 'proceed' ? 'st.preorders.toast.proceeded' : 'st.preorders.toast.closedEarly', { name: next.name }));
    },
    onError: (e) => {
      void qc.invalidateQueries({ queryKey: staffPreorderKeys.window(w.id) });
      toast.show(problemMessage(e), 'error');
    }
  });
}

const DIALOG_KEYS: Record<DecisionAction['kind'], { title: MessageKey; body: MessageKey; confirm: MessageKey }> = {
  cancel: { title: 'st.preorders.dialog.cancelTitle', body: 'st.preorders.dialog.cancelBody', confirm: 'st.preorders.dialog.confirmCancel' },
  proceed: { title: 'st.preorders.dialog.proceedTitle', body: 'st.preorders.dialog.proceedBody', confirm: 'st.preorders.dialog.confirmProceed' },
  closeEarly: { title: 'st.preorders.dialog.closeEarlyTitle', body: 'st.preorders.dialog.closeEarlyBody', confirm: 'st.preorders.dialog.confirmCloseEarly' }
};

/** Asks for the reason of a decision, then sends it. The reason is required and kept with who decided and when. */
export function DecisionDialog({ w, action, onClose }: { w: PreorderWindow; action: DecisionAction | null; onClose: () => void }) {
  const t = useT();
  const decide = useDecisionActions(w);
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const problem = decisionReasonProblem(reason);
  const keys = action ? DIALOG_KEYS[action.kind] : null;
  const close = () => {
    setReason('');
    setTouched(false);
    onClose();
  };
  const submit = () => {
    setTouched(true);
    if (!action || problem) return;
    decide.mutate({ action, reason: reason.trim() }, { onSuccess: close });
  };
  const vars = { name: w.name, count: w.orderCount, min: w.minOrders };
  return (
    <Dialog open={action !== null} onOpenChange={(open) => (open ? undefined : close())} width={480} title={keys ? t(keys.title, vars) : ''} description={keys ? t(keys.body, vars) : undefined}>
      <div className="flex flex-col gap-4">
        <label className={labelClass}>
          {t('st.preorders.dialog.reason')}
          <textarea
            value={reason}
            onChange={(e) => setReason(e.currentTarget.value)}
            onBlur={() => setTouched(true)}
            rows={3}
            maxLength={DECISION_REASON_MAX + 50}
            aria-invalid={touched && problem !== null}
            aria-describedby="decision-reason-help"
            className={`${controlClass} h-auto py-2`}
          />
          <span id="decision-reason-help" className={`text-xs font-normal ${touched && problem ? 'text-danger' : 'text-muted'}`}>
            {touched && problem === 'required'
              ? t('st.preorders.dialog.reasonRequired')
              : touched && problem === 'tooLong'
                ? t('st.preorders.dialog.reasonTooLong', { max: DECISION_REASON_MAX })
                : t('st.preorders.dialog.reasonHelp')}
          </span>
        </label>
        <div className="-mx-5 -mb-5 flex justify-end gap-2 border-t border-border px-5 py-4">
          <button type="button" onClick={close} className="h-10 cursor-pointer border-0 bg-transparent px-4 font-semibold">
            {t('st.preorders.dialog.back')}
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={decide.isPending}
            className={`h-10 cursor-pointer rounded-md border-0 px-5 font-bold disabled:cursor-not-allowed disabled:opacity-45 ${action?.kind === 'cancel' ? 'bg-red-600 text-ink-0' : 'bg-green-600 text-ink-0'}`}
          >
            {keys ? t(keys.confirm) : ''}
          </button>
        </div>
      </div>
    </Dialog>
  );
}

/** A window that closed below its minimum: nothing was cancelled or handed to a run; staff choose what happens. */
export function DecisionBanner({ w, canDecide, onChoose }: { w: PreorderWindow; canDecide: boolean; onChoose: (a: DecisionAction) => void }) {
  const t = useT();
  if (!w.decisionNeeded) return null;
  return (
    <div role="region" aria-label={t('st.preorders.decision.title')} data-testid="decision-banner" className="mx-5 mt-5 flex flex-col gap-3 rounded-md border border-gold-500 bg-gold-50 p-4">
      <strong className="text-md">{t('st.preorders.decision.title')}</strong>
      <p className="m-0 text-sm text-ink-800">{t('st.preorders.decision.body', { count: w.orderCount, min: w.minOrders })}</p>
      {canDecide ? (
        <div className="flex flex-wrap gap-2">
          <button type="button" onClick={() => onChoose({ kind: 'cancel' })} className="h-10 cursor-pointer rounded-md border-0 bg-red-600 px-4 font-bold text-ink-0">
            {t('st.preorders.decision.cancel')}
          </button>
          <button type="button" onClick={() => onChoose({ kind: 'proceed' })} className="h-10 cursor-pointer rounded-md border border-border-strong bg-ink-0 px-4 font-bold">
            {t('st.preorders.decision.proceed')}
          </button>
        </div>
      ) : (
        <p className="m-0 text-xs text-muted">{t('st.preorders.decision.noAccess')}</p>
      )}
    </div>
  );
}

/** "42 of 100 orders needed" / "Minimum of 100 orders met" on the overview. */
export function MinimumLine({ w }: { w: PreorderWindow }) {
  const t = useT();
  const text =
    w.minOrders < 1
      ? t('st.preorders.minimum.none')
      : w.minimumMet
        ? t('st.preorders.minimum.met', { min: w.minOrders, count: w.orderCount })
        : t('st.preorders.minimum.progress', { min: w.minOrders, count: w.orderCount });
  return (
    <div className="flex flex-col gap-1" data-testid="minimum-line">
      <span className={`text-sm font-semibold ${w.minOrders > 0 && !w.minimumMet && w.status !== 'OPEN' && w.status !== 'UPCOMING' ? 'text-red-700' : 'text-text'}`}>{text}</span>
      {w.canCloseEarly ? <span className="text-xs text-green-700">{t('st.preorders.action.closeEarlyHint')}</span> : null}
    </div>
  );
}

const KIND_KEY: Record<PreorderDecision['kind'], MessageKey> = {
  AWAITING: 'st.preorders.log.kind.AWAITING',
  PROCEED: 'st.preorders.log.kind.PROCEED',
  CANCEL: 'st.preorders.log.kind.CANCEL',
  EARLY_CLOSE: 'st.preorders.log.kind.EARLY_CLOSE'
};

/** Who decided what, when and why, oldest first. */
export function DecisionLog({ id }: { id: string }) {
  const api = usePreordersApi();
  const t = useT();
  const log = useQuery(staffPreorderQueries.decisions(api, id));
  if (!log.data) return null;
  return (
    <section aria-label={t('st.preorders.log.title')} className="flex flex-col gap-2" data-testid="decision-log">
      <strong className="text-sm">{t('st.preorders.log.title')}</strong>
      {log.data.length === 0 ? (
        <span className="text-xs text-muted">{t('st.preorders.log.empty')}</span>
      ) : (
        <ol className="m-0 flex list-none flex-col gap-2 p-0">
          {log.data.map((d) => (
            <li key={d.id} className="flex flex-col gap-0.5 rounded-md border border-border px-3 py-2 text-sm">
              <span className="flex flex-wrap items-center gap-2">
                <strong>{t(KIND_KEY[d.kind])}</strong>
                <span className="text-xs text-muted">{t('st.preorders.log.counts', { count: d.orders, min: d.minimum })}</span>
                <span className="text-xs text-subtle">{t('st.preorders.log.by', { actor: d.actor === 'system' ? t('st.preorders.log.system') : d.actor, when: fDT(d.at) })}</span>
              </span>
              <span className="text-xs text-ink-800">{d.reason}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

export const REFUND_PILL: Record<RefundStatus, string> = {
  PENDING: 'bg-gold-100 text-gold-900',
  REFUNDED: 'bg-green-50 text-green-800',
  VOIDED: 'bg-ink-100 text-ink-800',
  FAILED: 'bg-red-50 text-red-700'
};

const REFUND_KEY: Record<RefundStatus, MessageKey> = {
  PENDING: 'st.preorders.refunds.status.PENDING',
  REFUNDED: 'st.preorders.refunds.status.REFUNDED',
  VOIDED: 'st.preorders.refunds.status.VOIDED',
  FAILED: 'st.preorders.refunds.status.FAILED'
};

/** One order's refund status as a pill. */
export function RefundPill({ status }: { status: RefundStatus | null }) {
  const t = useT();
  if (!status) return <span className="text-xs text-subtle">–</span>;
  return (
    <Pill className={`px-2 py-px text-2xs ${REFUND_PILL[status]}`} testId={`refund-${status}`}>
      {t(REFUND_KEY[status])}
    </Pill>
  );
}

/** Refund progress of a cancelled window; refreshes every few seconds until every order has settled. */
export function RefundProgress({ w }: { w: PreorderWindow }) {
  const api = usePreordersApi();
  const t = useT();
  const refunds = useQuery({
    ...staffPreorderQueries.refunds(api, w.id),
    enabled: w.status === 'CANCELLED',
    refetchInterval: (q) => (q.state.data && !q.state.data.done ? 3000 : false)
  });
  const r = refunds.data;
  if (w.status !== 'CANCELLED' || !r) return null;
  const done = r.refunded + r.voided;
  return (
    <section aria-label={t('st.preorders.refunds.title')} className="flex flex-col gap-2 rounded-md border border-border p-4" data-testid="refund-progress">
      <span className="flex flex-wrap items-center gap-2">
        <strong className="text-sm">{t('st.preorders.refunds.title')}</strong>
        <span className="text-xs text-muted" data-testid="refund-summary">{t('st.preorders.refunds.summary', { done, total: r.total })}</span>
      </span>
      <span role="progressbar" aria-label={t('st.preorders.refunds.title')} aria-valuenow={done} aria-valuemin={0} aria-valuemax={r.total} className="block h-2.5 overflow-hidden rounded-pill bg-ink-100">
        <span className="block h-full bg-green-600" style={{ width: `${r.total ? (done / r.total) * 100 : 100}%` }} />
      </span>
      <span className="flex flex-wrap gap-2">
        {(['PENDING', 'REFUNDED', 'VOIDED', 'FAILED'] as const).map((s) => {
          const n = { PENDING: r.pending, REFUNDED: r.refunded, VOIDED: r.voided, FAILED: r.failed }[s];
          return n ? (
            <Pill key={s} className={REFUND_PILL[s]}>
              {`${t(REFUND_KEY[s])} · ${n}`}
            </Pill>
          ) : null;
        })}
      </span>
      {r.failed ? <span role="alert" className="text-xs text-red-700">{t('st.preorders.refunds.failedNote', { n: r.failed })}</span> : null}
      {r.done ? <span className="text-xs text-green-700">{t('st.preorders.refunds.allDone')}</span> : null}
      {r.voided ? <span className="text-xs text-muted">{t('st.preorders.refunds.explainVoided')}</span> : null}
    </section>
  );
}

/** The decision buttons of the detail header: close early (minimum met) and cancel (any live window). */
export function DecisionButtons({ w, onChoose, className }: { w: PreorderWindow; onChoose: (a: DecisionAction) => void; className: string }) {
  const t = useT();
  return (
    <>
      {w.canCloseEarly ? (
        <button type="button" onClick={() => onChoose({ kind: 'closeEarly' })} title={t('st.preorders.action.closeEarlyHint')} className={className}>
          {t('st.preorders.action.closeEarly')}
        </button>
      ) : null}
      {cancellable(w) && !w.decisionNeeded ? (
        <button type="button" onClick={() => onChoose({ kind: 'cancel' })} className={`${className} text-red-700`}>
          {t('st.preorders.action.cancel')}
        </button>
      ) : null}
    </>
  );
}

