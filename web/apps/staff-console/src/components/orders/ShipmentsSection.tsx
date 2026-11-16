'use client';

import { definitiveFailure, isApiError, type RecordDetail, type RecordShipment, type ShipmentBlocked, type ShipmentChange, type ShipmentMode, type ShipmentStep } from '@afrotransact/api-client';
import { Alert, Button, Dialog, DialogActions, Field, fieldStyles, Menu, MenuItem, TextField, useToast } from '@afrotransact/ui';
import { CaretDown } from '@phosphor-icons/react/ssr';
import { useMutation } from '@tanstack/react-query';
import { type FormEvent, useEffect, useRef, useState } from 'react';

import { ProofDialog, type ProofSubmit } from '@/components/delivery/ProofDialog';
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
  DELIVERY_PERMISSION: 'st.order.blocked.DELIVERY_PERMISSION',
  SELLER: 'st.order.blocked.SELLER',
  SELLER_UPDATES: 'st.order.blocked.SELLER_UPDATES',
  PICKUP_ORDER: 'st.order.blocked.PICKUP_ORDER',
  SEQUENCE: 'st.order.blocked.SEQUENCE',
  STATE: 'st.order.blocked.STATE'
};

const isFulfilledByAfroTransact = (mode: ShipmentMode): boolean => mode === 'afrotransact_delivery' || mode === 'fulfilled_by_afrotransact';

/** A status in the words of the shipment's mode: a pickup order is Ready for pickup / Collected / Not collected, a parcel AfroTransact collects is Picked up from the seller / Returned to the seller. */
export function stepLabel(t: Translator<MessageKey>, mode: ShipmentMode, status: string): string {
  const group = mode === 'pickup' ? 'pickup' : isFulfilledByAfroTransact(mode) ? 'afrotransact' : null;
  const key = `st.order.step.${group}.${status}`;
  return group && key in EN_MESSAGES ? t(key as MessageKey) : shipmentStatusLabel(t, status);
}

const MODE_KEYS: Partial<Record<ShipmentMode, MessageKey>> = {
  afrotransact_delivery: 'st.order.mode.afrotransactStore',
  fulfilled_by_afrotransact: 'st.order.mode.afrotransactCentre',
  pickup: 'st.order.mode.pickup',
  legacy_seller_shipped: 'st.order.mode.legacySellerShipped'
};

const FULFILMENT_KEYS: Record<string, MessageKey> = { AfroTransact: 'st.order.packedBy.afrotransact', Seller: 'st.order.packedBy.seller' };
const DELIVERY_KEYS: Record<string, MessageKey> = { Carrier: 'st.order.delivery.carrier', 'AfroTransact Delivery': 'st.order.delivery.afrotransact', Pickup: 'st.order.delivery.pickup' };

/** What the dialog is open on: one shipment, one status, one Idempotency-Key until the change is done. */
interface Pending {
  shipment: RecordShipment;
  step: ShipmentStep;
  key: string;
}

/** The override of a seller-shipped shipment: a status and a required reason. */
interface Overriding {
  shipment: RecordShipment;
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
  const api = staffApi();
  const [pending, setPending] = useState<Pending | null>(null);
  const [proofFor, setProofFor] = useState<RecordShipment | null>(null);
  const [overriding, setOverriding] = useState<Overriding | null>(null);
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

  /** The order as the sheet should now show it, after a step taken through the Delivery module (the queue's own steps). */
  const reloaded = (): Promise<RecordDetail> => api.getRecord('orders', order);

  const set = useMutation({
    mutationFn: async ({ p, reason, handoverCode }: { p: Pending; reason: string; handoverCode: string }): Promise<RecordDetail> => {
      const { shipment, step, key } = p;
      if (step.via === 'delivery') {
        // The same steps, proof and audit as the Delivery queue; the screen says which status it saw so a stale one gets a 409.
        const expectedStatus = shipment.status.toLowerCase();
        const body = { ...(reason ? { note: reason } : {}), expectedStatus };
        if (step.to === 'PICKED_UP') await api.markDeliveryPickedUp(shipment.id, body, key);
        else if (step.to === 'IN_TRANSIT') await api.markDeliveryInTransit(shipment.id, body, key);
        else if (step.to === 'OUT_FOR_DELIVERY') await api.markDeliveryOutForDelivery(shipment.id, body, key);
        else if (step.to === 'DELIVERY_FAILED') await api.markDeliveryFailed(shipment.id, reason, undefined, key, expectedStatus);
        else if (step.to === 'RETURNED') await api.markDeliveryReturned(shipment.id, reason, expectedStatus, key);
        return reloaded();
      }
      return api.runAction('orders', order, 'shipment-status', { step: `${shipment.id}|${step.to}`, ...(reason ? { reason } : {}), ...(handoverCode ? { handoverCode } : {}) }, key);
    },
    // A change is never retried by the client: a repeat after a timeout could set it twice (the same key replays it).
    retry: false,
    onSuccess: (r, v) => {
      setPending(null);
      onUpdated(r);
      toast.show(t('st.order.done', { seller: v.p.shipment.sellerName, status: stepLabel(t, v.p.shipment.mode, v.p.step.to) }));
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
    // Delivered by AfroTransact needs the proof photo: the proof capture opens right here, in the sheet.
    if (step.via === 'delivery' && step.to === 'DELIVERED') {
      setProofFor(shipment);
      return;
    }
    setPending({ shipment, step, key: idempotencyKey() });
  };

  const deliver = async (shipment: RecordShipment, v: ProofSubmit) => {
    try {
      await api.markDeliveryDelivered(
        shipment.id,
        {
          photoAssetIds: v.photoAssetIds,
          ...(v.recipientName ? { recipientName: v.recipientName } : {}),
          ...(v.note ? { note: v.note } : {}),
          ...(v.latitude !== undefined && v.longitude !== undefined ? { latitude: v.latitude, longitude: v.longitude } : {}),
          expectedStatus: shipment.status.toLowerCase()
        },
        v.key
      );
    } catch (e) {
      if (isApiError(e) && e.status === 409) onStale();
      throw e;
    }
    onUpdated(await reloaded());
    toast.show(t('st.order.done', { seller: shipment.sellerName, status: stepLabel(t, shipment.mode, 'DELIVERED') }));
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
            <ShipmentRow key={s.id} shipment={s} n={i + 1} total={shipments.length} busy={set.isPending} onPick={open} onOverride={(shipment) => setOverriding({ shipment, key: idempotencyKey() })} />
          ))}
        </ul>
      )}
      <Dialog
        open={pending !== null}
        onOpenChange={(o) => !o && !set.isPending && setPending(null)}
        title={pending ? t('st.order.confirm.title', { status: stepLabel(t, pending.shipment.mode, pending.step.to) }) : ''}
        description={pending ? t('st.order.confirm.summary', { order, seller: pending.shipment.sellerName, from: stepLabel(t, pending.shipment.mode, pending.shipment.status), to: stepLabel(t, pending.shipment.mode, pending.step.to) }) : undefined}
        closeLabel={t('st.order.confirm.close')}
        width={420}
      >
        {pending ? (
          <form onSubmit={submit} noValidate className="flex flex-col gap-3">
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
                {t('st.order.confirm.submit', { status: stepLabel(t, pending.shipment.mode, pending.step.to) })}
              </Button>
            </DialogActions>
          </form>
        ) : null}
      </Dialog>
      <OverrideDialog order={order} overriding={overriding} onClose={() => setOverriding(null)} onDone={(r, shipment) => {
        setOverriding(null);
        onUpdated(r);
        toast.show(t('st.order.overrideDone', { seller: shipment.sellerName }));
      }} onStale={onStale} />
      {proofFor ? <ProofDialog open mode="deliver" orderNumber={order} onOpenChange={(o) => !o && setProofFor(null)} onSubmit={(v) => deliver(proofFor, v)} /> : null}
    </section>
  );
}

/** Override of a seller-shipped shipment (orders:override-shipment): forward only, a reason is required and kept with who and when. */
function OverrideDialog({ order, overriding, onClose, onDone, onStale }: { order: string; overriding: Overriding | null; onClose: () => void; onDone: (r: RecordDetail, shipment: RecordShipment) => void; onStale: () => void }) {
  const t = useT();
  const [to, setTo] = useState('');
  const [reason, setReason] = useState('');
  const [errors, setErrors] = useState<{ to?: string; reason?: string; form?: string }>({});
  const shipment = overriding?.shipment ?? null;
  useEffect(() => {
    setTo(shipment?.overrideSteps[0] ?? '');
    setReason('');
    setErrors({});
  }, [shipment]);
  const run = useMutation({
    mutationFn: () => staffApi().runAction('orders', order, 'shipment-override', { step: `${shipment?.id ?? ''}|${to}`, reason: reason.trim() }, overriding?.key),
    retry: false,
    onSuccess: (r) => shipment && onDone(r, shipment),
    onError: (e) => {
      if (isApiError(e) && e.status === 409) onStale();
      setErrors(isApiError(e) && e.status === 422 ? { to: e.fieldErrors.step, reason: e.fieldErrors.reason, form: Object.keys(e.fieldErrors).length ? undefined : problemMessage(e) } : { form: problemMessage(e) });
    }
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (run.isPending) return;
    const next: typeof errors = {};
    if (!to) next.to = t('st.order.override.statusMissing');
    if (reason.trim().length < 3) next.reason = t('st.order.confirm.noteMissing');
    setErrors(next);
    if (Object.keys(next).length === 0) run.mutate();
  };
  return (
    <Dialog open={overriding !== null} onOpenChange={(o) => !o && !run.isPending && onClose()} title={t('st.order.override.title')} description={shipment ? t('st.order.override.summary', { order, seller: shipment.sellerName, from: shipmentStatusLabel(t, shipment.status) }) : undefined} closeLabel={t('st.order.confirm.close')} width={420}>
      {shipment ? (
        <form onSubmit={submit} noValidate className="flex flex-col gap-3">
          <p className="m-0 text-xs text-muted">{t('st.order.override.help')}</p>
          <Field id="override-status" label={t('st.order.override.status')} error={errors.to}>
            {(a11y) => (
              <select {...a11y} className={fieldStyles.control} value={to} onChange={(e) => setTo(e.currentTarget.value)}>
                {shipment.overrideSteps.map((step) => (
                  <option key={step} value={step}>
                    {shipmentStatusLabel(t, step)}
                  </option>
                ))}
              </select>
            )}
          </Field>
          <TextField label={t('st.order.override.reason')} value={reason} maxLength={500} required error={errors.reason} onChange={(e) => setReason(e.currentTarget.value)} />
          {errors.form ? (
            <Alert variant="danger" title={t('st.order.override.failed')} fill>
              {errors.form}
            </Alert>
          ) : null}
          <DialogActions>
            <Button variant="neutral" disabled={run.isPending} onClick={onClose}>
              {t('st.order.confirm.cancel')}
            </Button>
            <Button type="submit" loading={run.isPending}>
              {t('st.order.override.submit')}
            </Button>
          </DialogActions>
        </form>
      ) : null}
    </Dialog>
  );
}

function ShipmentRow({ shipment: s, n, total, busy, onPick, onOverride }: { shipment: RecordShipment; n: number; total: number; busy: boolean; onPick: (s: RecordShipment, step: ShipmentStep) => void; onOverride: (s: RecordShipment) => void }) {
  const t = useT();
  const items = [t('st.order.items', { count: s.itemCount }), s.itemTitles.join(', ')].filter(Boolean).join(' · ');
  const modeKey = MODE_KEYS[s.mode];
  const travel = modeKey
    ? t(modeKey)
    : [FULFILMENT_KEYS[s.fulfilmentLabel] ? t(FULFILMENT_KEYS[s.fulfilmentLabel] as MessageKey) : s.fulfilmentLabel, DELIVERY_KEYS[s.deliveryLabel] ? t(DELIVERY_KEYS[s.deliveryLabel] as MessageKey) : s.deliveryLabel].join(' · ');
  const tracking = s.trackingNumber ? (s.carrier ? t('st.order.trackingCarrier', { carrier: s.carrier, number: s.trackingNumber }) : t('st.order.tracking', { number: s.trackingNumber })) : (s.carrier ?? '');
  return (
    <li className="flex flex-col gap-1 border-t border-border py-3 first:border-t-0 first:pt-1" data-testid="shipment-row" data-mode={s.mode}>
      <div className="flex flex-wrap items-start gap-x-3 gap-y-2">
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <div className="flex flex-wrap items-center gap-2">
            <strong className="text-sm">{s.sellerName}</strong>
            <StatusPill tone={s.tone}>{stepLabel(t, s.mode, s.status)}</StatusPill>
          </div>
          {total > 1 ? <span className="text-xs text-muted">{t('st.order.shipmentOf', { n, total })}</span> : null}
          <span className="text-sm [overflow-wrap:anywhere]">{items}</span>
          <span className="text-xs text-muted" data-testid="shipment-mode">{travel}</span>
          {tracking ? <span className="text-xs text-muted [overflow-wrap:anywhere]">{tracking}</span> : null}
        </div>
        <div className="flex flex-col items-end gap-1">
          <StatusControl shipment={s} busy={busy} onPick={onPick} />
          {s.canOverride ? (
            <Button variant="link" size="sm" disabled={busy} onClick={() => onOverride(s)}>
              {t('st.order.override.open')}
            </Button>
          ) : null}
        </div>
      </div>
      <History history={s.history} mode={s.mode} />
    </li>
  );
}

/** "Set status": every step the staff member may take now, and the ones they may not with the accurate reason beside them. */
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
          disabled={step.allowed ? false : step.blocked ? t(BLOCKED_KEYS[step.blocked]) : (step.reason ?? true)}
          onSelect={() => onPick(s, step)}
        >
          {stepLabel(t, s.mode, step.to)}
        </MenuItem>
      ))}
    </Menu>
  );
}

/** Who moved the shipment, when and why: collapsed, newest first. */
function History({ history, mode }: { history: ShipmentChange[]; mode: ShipmentMode }) {
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
              {t('st.order.historyLine', { status: stepLabel(t, mode, h.to), actor: h.actor })}
              {h.note ? <span className="block text-muted">{h.note}</span> : null}
            </span>
          </li>
        ))}
      </ol>
    </details>
  );
}
