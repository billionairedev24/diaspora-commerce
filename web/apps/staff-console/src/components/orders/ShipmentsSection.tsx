'use client';

import { definitiveFailure, isApiError, type RecordDetail, type RecordShipment, type ShipmentBlocked, type ShipmentChange, type ShipmentStep } from '@afrotransact/api-client';
import { Alert, Button, Dialog, DialogActions, Menu, MenuItem, TextField, useToast } from '@afrotransact/ui';
import { CaretDown } from '@phosphor-icons/react/ssr';
import { useMutation } from '@tanstack/react-query';
import { type FormEvent, useEffect, useRef, useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { EN_MESSAGES, type MessageKey, type Translator, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime, humanize, idempotencyKey } from '@/lib/format';

/** The shipment status in the staff member's language (the status code itself when the catalogue has no word for it). */
export function shipmentStatusLabel(t: Translator<MessageKey>, status: string): string {
  const key = `st.order.status.${status}`;
  return key in EN_MESSAGES ? t(key as MessageKey) : humanize(status);
}

const BLOCKED_KEYS: Record<ShipmentBlocked, MessageKey> = {
  PERMISSION: 'st.order.blocked.PERMISSION',
  SELLER: 'st.order.blocked.SELLER',
  SEQUENCE: 'st.order.blocked.SEQUENCE',
  STATE: 'st.order.blocked.STATE'
};

const FULFILMENT_KEYS: Record<string, MessageKey> = { AfroTransact: 'st.order.packedBy.afrotransact', Seller: 'st.order.packedBy.seller' };
const DELIVERY_KEYS: Record<string, MessageKey> = { Carrier: 'st.order.delivery.carrier', 'AfroTransact Delivery': 'st.order.delivery.afrotransact', Pickup: 'st.order.delivery.pickup' };

/** What the dialog is open on: one shipment, one status, one Idempotency-Key until the change is done. */
interface Pending {
  shipment: RecordShipment;
  step: ShipmentStep;
  key: string;
}

/**
 * The order's packages as flat rows: who sends it, what is in it, how it travels, where it is, and one compact control to
 * set its status by hand (valid steps on offer, unavailable ones listed with why). Choosing a status opens one small
 * confirmation that asks for the note the step needs. The orders service applies the state machine; nothing here
 * decides what is allowed.
 */
export function ShipmentsSection({ order, shipments, onUpdated, onStale }: { order: string; shipments: RecordShipment[]; onUpdated: (r: RecordDetail) => void; onStale: () => void }) {
  const t = useT();
  const toast = useToast();
  const [pending, setPending] = useState<Pending | null>(null);
  const [note, setNote] = useState('');
  const [code, setCode] = useState('');
  const [errors, setErrors] = useState<{ note?: string; code?: string; form?: string }>({});
  const noteRef = useRef<HTMLInputElement>(null);
  const codeRef = useRef<HTMLInputElement>(null);
  const dialogOpen = pending !== null;
  // Focus the first field once the dialog has taken focus (Radix focuses the dialog itself first).
  useEffect(() => {
    if (!dialogOpen) return;
    const timer = setTimeout(() => (codeRef.current ?? noteRef.current)?.focus(), 30);
    return () => clearTimeout(timer);
  }, [dialogOpen]);

  const set = useMutation({
    mutationFn: ({ p, reason, handoverCode }: { p: Pending; reason: string; handoverCode: string }) =>
      staffApi().runAction('orders', order, 'shipment-status', { step: `${p.shipment.id}|${p.step.to}`, ...(reason ? { reason } : {}), ...(handoverCode ? { handoverCode } : {}) }, p.key),
    // A change is never retried by the client: a repeat after a timeout could set it twice (the same key replays it).
    retry: false,
    onSuccess: (r, v) => {
      setPending(null);
      onUpdated(r);
      toast.show(t('st.order.done', { seller: v.p.shipment.sellerName, status: shipmentStatusLabel(t, v.p.step.to) }));
    },
    onError: (e, v) => {
      if (isApiError(e) && e.status === 409) {
        // Someone moved it first (or it is no longer possible): say so in the API's words and show the current state.
        setErrors({ form: problemMessage(e) });
        onStale();
        return;
      }
      if (definitiveFailure(e)) setPending((p) => (p && p.key === v.p.key ? { ...p, key: idempotencyKey() } : p));
      if (isApiError(e) && e.status === 422) {
        setErrors({ note: e.fieldErrors.reason, code: e.fieldErrors.handoverCode, form: e.fieldErrors.step ?? (Object.keys(e.fieldErrors).length ? undefined : problemMessage(e)) });
      } else setErrors({ form: problemMessage(e) });
    }
  });

  const open = (shipment: RecordShipment, step: ShipmentStep) => {
    setNote('');
    setCode('');
    setErrors({});
    set.reset();
    setPending({ shipment, step, key: idempotencyKey() });
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!pending || set.isPending) return;
    const reason = note.trim();
    const handoverCode = code.trim();
    const next: typeof errors = {};
    if (handoverCode && !/^\d{4}$/.test(handoverCode)) next.code = t('st.order.confirm.codeBad');
    if (pending.step.noteRequired && !reason && !(pending.step.codeAccepted && handoverCode)) next.note = t('st.order.confirm.noteMissing');
    setErrors(next);
    if (Object.keys(next).length) return;
    set.mutate({ p: pending, reason, handoverCode });
  };

  return (
    <section aria-label={t('st.order.shipments')} className="flex flex-col gap-1">
      <h3 className="m-0 text-sm font-bold">{t('st.order.shipments')}</h3>
      {shipments.length === 0 ? (
        <p className="m-0 text-sm text-muted">{t('st.order.noShipments')}</p>
      ) : (
        <ul className="m-0 flex list-none flex-col p-0">
          {shipments.map((s, i) => (
            <ShipmentRow key={s.id} shipment={s} n={i + 1} total={shipments.length} busy={set.isPending} onPick={open} />
          ))}
        </ul>
      )}
      <Dialog
        open={pending !== null}
        onOpenChange={(o) => !o && !set.isPending && setPending(null)}
        title={pending ? t('st.order.confirm.title', { status: shipmentStatusLabel(t, pending.step.to) }) : ''}
        description={pending ? t('st.order.confirm.summary', { order, seller: pending.shipment.sellerName, from: shipmentStatusLabel(t, pending.shipment.status), to: shipmentStatusLabel(t, pending.step.to) }) : undefined}
        closeLabel={t('st.order.confirm.close')}
        width={420}
      >
        {pending ? (
          <form onSubmit={submit} noValidate className="flex flex-col gap-3">
            {pending.step.driverStep ? <p className="m-0 text-xs text-muted">{t('st.order.driverHint')}</p> : null}
            {pending.step.codeAccepted ? (
              <TextField
                ref={codeRef}
                label={t('st.order.confirm.code')}
                help={t('st.order.confirm.codeHelp')}
                value={code}
                inputMode="numeric"
                autoComplete="off"
                maxLength={4}
                error={errors.code}
                onChange={(e) => setCode(e.currentTarget.value.replace(/\D/g, ''))}
              />
            ) : null}
            <TextField
              label={pending.step.noteRequired ? (pending.step.codeAccepted ? t('st.order.confirm.noteOrCode') : t('st.order.confirm.noteRequired')) : t('st.order.confirm.noteOptional')}
              value={note}
              maxLength={500}
              required={pending.step.noteRequired && !pending.step.codeAccepted}
              error={errors.note}
              ref={noteRef}
              onChange={(e) => setNote(e.currentTarget.value)}
            />
            {errors.form ? (
              <Alert variant="danger" title={t('st.order.confirm.failed')} fill>
                {errors.form}
              </Alert>
            ) : null}
            <DialogActions>
              <Button variant="neutral" disabled={set.isPending} onClick={() => setPending(null)}>
                {t('st.order.confirm.cancel')}
              </Button>
              <Button type="submit" loading={set.isPending}>
                {t('st.order.confirm.submit', { status: shipmentStatusLabel(t, pending.step.to) })}
              </Button>
            </DialogActions>
          </form>
        ) : null}
      </Dialog>
    </section>
  );
}

function ShipmentRow({ shipment: s, n, total, busy, onPick }: { shipment: RecordShipment; n: number; total: number; busy: boolean; onPick: (s: RecordShipment, step: ShipmentStep) => void }) {
  const t = useT();
  const items = [t('st.order.items', { count: s.itemCount }), s.itemTitles.join(', ')].filter(Boolean).join(' · ');
  const travel = [FULFILMENT_KEYS[s.fulfilmentLabel] ? t(FULFILMENT_KEYS[s.fulfilmentLabel] as MessageKey) : s.fulfilmentLabel, DELIVERY_KEYS[s.deliveryLabel] ? t(DELIVERY_KEYS[s.deliveryLabel] as MessageKey) : s.deliveryLabel].join(' · ');
  const tracking = s.trackingNumber ? (s.carrier ? t('st.order.trackingCarrier', { carrier: s.carrier, number: s.trackingNumber }) : t('st.order.tracking', { number: s.trackingNumber })) : (s.carrier ?? '');
  return (
    <li className="flex flex-col gap-1 border-t border-border py-3 first:border-t-0 first:pt-1" data-testid="shipment-row">
      <div className="flex flex-wrap items-start gap-x-3 gap-y-2">
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <div className="flex flex-wrap items-center gap-2">
            <strong className="text-sm">{s.sellerName}</strong>
            <StatusPill tone={s.tone}>{shipmentStatusLabel(t, s.status)}</StatusPill>
          </div>
          {total > 1 ? <span className="text-xs text-muted">{t('st.order.shipmentOf', { n, total })}</span> : null}
          <span className="text-sm [overflow-wrap:anywhere]">{items}</span>
          <span className="text-xs text-muted">{travel}</span>
          {tracking ? <span className="text-xs text-muted [overflow-wrap:anywhere]">{tracking}</span> : null}
        </div>
        <StatusControl shipment={s} busy={busy} onPick={onPick} />
      </div>
      <History history={s.history} />
    </li>
  );
}

/** "Set status": every step the staff member may take now, and the ones they may not with the reason beside them. */
function StatusControl({ shipment: s, busy, onPick }: { shipment: RecordShipment; busy: boolean; onPick: (s: RecordShipment, step: ShipmentStep) => void }) {
  const t = useT();
  if (s.transitions.length === 0) return <span className="text-xs text-muted">{t('st.order.noFurther')}</span>;
  return (
    <Menu
      label={t('st.order.setStatusFor', { seller: s.sellerName })}
      trigger={
        <Button variant="neutral" size="sm" disabled={busy} aria-label={t('st.order.setStatusFor', { seller: s.sellerName })} icon={<CaretDown size={12} aria-hidden />} className="flex-row-reverse">
          {t('st.order.setStatus')}
        </Button>
      }
      width={300}
    >
      {s.transitions.map((step) => (
        <MenuItem
          key={step.to}
          disabled={!step.allowed}
          description={step.allowed ? undefined : step.blocked ? t(BLOCKED_KEYS[step.blocked]) : (step.reason ?? undefined)}
          onSelect={() => onPick(s, step)}
        >
          {shipmentStatusLabel(t, step.to)}
        </MenuItem>
      ))}
    </Menu>
  );
}

/** Who moved the shipment, when and why: collapsed, newest first. */
function History({ history }: { history: ShipmentChange[] }) {
  const t = useT();
  if (history.length === 0) return null;
  return (
    <details className="text-xs">
      <summary className="cursor-pointer text-muted">{t('st.order.history', { count: history.length })}</summary>
      <ol className="m-0 mt-1.5 flex list-none flex-col gap-1.5 p-0">
        {history.map((h, i) => (
          <li key={`${h.at}-${i}`} className="flex gap-3">
            <span className="w-24 flex-none text-muted">{dateTime(h.at)}</span>
            <span className="min-w-0 [overflow-wrap:anywhere]">
              {t('st.order.historyLine', { status: shipmentStatusLabel(t, h.to), actor: h.actor })}
              {h.note ? <span className="block text-muted">{h.note}</span> : null}
            </span>
          </li>
        ))}
      </ol>
    </details>
  );
}
