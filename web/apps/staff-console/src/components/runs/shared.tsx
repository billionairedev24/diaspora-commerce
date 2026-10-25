'use client';

import { type RunEconomics, type RunSnapshot, type StaffRunsApi } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, cx, Dialog, TextField } from '@afrotransact/ui';
import { type FormEvent, useEffect, useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { useStaff } from '@/lib/session';

export const CARD = 'flex min-w-0 flex-col gap-3 rounded-md border border-border bg-surface p-4';
export const REASON_MAX = 500;

export function useRunsApi(): StaffRunsApi {
  return staffApi().runs;
}

/** Whether this staff member may change the cost model and advertorials (Pre-orders: Edit or Full with preorders:manage). */
export function useCanManageRuns(): boolean {
  const { level, hasPermission } = useStaff();
  const lv = level('preorders');
  return (lv === 'FULL' || lv === 'EDIT') && hasPermission('preorders:manage');
}

/** A value that follows `value` after it has stopped changing for `ms` (so a form does not call the BFF per keystroke). */
export function useDebounced<T>(value: T, ms = 400): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setV(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return v;
}

/** Margin colour: positive in the success token, negative in the danger token. */
export const marginClass = (minor: number): string => (minor < 0 ? 'text-danger' : minor > 0 ? 'text-success' : 'text-text');

/** A modal asking for the reason of a change; the reason is logged with who and when. */
export function ReasonDialog({
  open,
  title,
  help,
  confirm,
  onClose,
  onSubmit
}: {
  open: boolean;
  title: string;
  help: string;
  confirm: string;
  onClose: () => void;
  onSubmit: (reason: string) => Promise<void>;
}) {
  const t = useT();
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const close = () => {
    setReason('');
    setProblem(null);
    setFailure(null);
    onClose();
  };
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const r = reason.trim();
    if (!r) return setProblem(t('st.runs.reason.required'));
    if (r.length > REASON_MAX) return setProblem(t('st.runs.reason.tooLong', { max: REASON_MAX }));
    setProblem(null);
    setFailure(null);
    setSaving(true);
    try {
      await onSubmit(r);
      close();
    } catch (err) {
      setFailure(problemMessage(err));
    } finally {
      setSaving(false);
    }
  };
  return (
    <Dialog open={open} onOpenChange={(o) => !o && close()} title={title} description={help} width={480} closeLabel={t('st.runs.reason.close')}>
      <form noValidate className="flex flex-col gap-3" onSubmit={(e) => void submit(e)}>
        <TextField label={t('st.runs.reason.label')} value={reason} maxLength={REASON_MAX + 50} error={problem} onChange={(e) => setReason(e.currentTarget.value)} />
        {failure ? (
          <p role="alert" className="m-0 text-sm text-danger">
            {failure}
          </p>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={close}>
            {t('st.runs.reason.cancel')}
          </Button>
          <Button type="submit" loading={saving}>
            {confirm}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

/** Orders, revenue, estimated cost and contribution margin at one number of orders. */
function SnapshotRow({ label, s, currency, testId }: { label: string; s: RunSnapshot; currency: string; testId?: string }) {
  const fmt = useFormatters();
  return (
    <tr className="border-t border-border" data-testid={testId}>
      <th scope="row" className="px-3 py-2 text-left text-xs font-semibold">
        {label}
      </th>
      <td className="px-3 py-2 text-right">{fmt.number(s.orders)}</td>
      <td className="px-3 py-2 text-right">{fmt.money(s.revenueMinor, currency)}</td>
      <td className="px-3 py-2 text-right">{fmt.money(s.estimatedCostMinor, currency)}</td>
      <td className={cx('px-3 py-2 text-right font-bold', marginClass(s.contributionMarginMinor))}>{fmt.money(s.contributionMarginMinor, currency)}</td>
    </tr>
  );
}

/** The P&L of a run at its minimum, at its actual orders and at capacity, with the cost model behind it. */
export function EconomicsTable({ e, labelOrders }: { e: RunEconomics; labelOrders: string }) {
  const t = useT();
  const fmt = useFormatters();
  return (
    <div className="flex flex-col gap-2">
      <div className="overflow-auto rounded-md border border-border">
        <table className="w-full min-w-[520px] border-collapse text-sm" aria-label={t('st.runs.pnl.table')}>
          <thead className="bg-surface-muted text-left">
            <tr className="text-2xs font-bold tracking-[0.06em] text-muted uppercase">
              <th scope="col" className="px-3 py-2" />
              <th scope="col" className="px-3 py-2 text-right">
                {t('st.runs.pnl.orders')}
              </th>
              <th scope="col" className="px-3 py-2 text-right">
                {t('st.runs.pnl.revenue')}
              </th>
              <th scope="col" className="px-3 py-2 text-right">
                {t('st.runs.pnl.cost')}
              </th>
              <th scope="col" className="px-3 py-2 text-right">
                {t('st.runs.pnl.margin')}
              </th>
            </tr>
          </thead>
          <tbody>
            <SnapshotRow label={t('st.runs.pnl.atMinimum')} s={e.atMinimum} currency={e.currency} testId="pnl-minimum" />
            {e.atOrders.orders !== e.atMinimum.orders ? <SnapshotRow label={labelOrders} s={e.atOrders} currency={e.currency} testId="pnl-orders" /> : null}
            {e.atCapacity ? <SnapshotRow label={t('st.runs.pnl.atCapacity')} s={e.atCapacity} currency={e.currency} testId="pnl-capacity" /> : null}
          </tbody>
        </table>
      </div>
      <p className="m-0 text-xs text-muted" data-testid="pnl-model">
        {t('st.runs.pnl.model', {
          miles: fmt.number(e.route.roadMiles),
          minutes: fmt.number(e.route.driveMinutes),
          fixed: fmt.money(Math.round(e.cost.fixedCostMinor), e.currency),
          perOrder: fmt.money(Math.round(e.cost.perOrderCostMinor), e.currency),
          fee: fmt.money(e.feeMinor, e.currency)
        })}
      </p>
    </div>
  );
}

/** "Break-even at 38 orders" or why there is none. */
export function breakEvenText(t: ReturnType<typeof useT>, e: RunEconomics | null | undefined): string {
  if (!e) return t('st.runs.breakEven.unknown');
  return e.breakEvenOrders === null ? t('st.runs.breakEven.never') : t('st.runs.breakEven.at', { n: e.breakEvenOrders });
}
