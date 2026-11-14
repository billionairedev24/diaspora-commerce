'use client';

import { type DeliveryDetail, type DeliveryStatus, isApiError, staffKeys, staffQueries } from '@afrotransact/api-client';
import { Button, Dialog, DialogActions, Field, fieldStyles, Select, Sheet, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime, idempotencyKey, shortDate } from '@/lib/format';

import { AUDIT_LABEL, customerPlace, STATUS_LABEL, STATUS_TONE } from './model';
import { ProofDialog, type ProofSubmit } from './ProofDialog';

const section = 'flex flex-col gap-2 border-t border-border pt-4';
const h3 = 'm-0 text-sm font-bold';
const dl = 'm-0 grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm';

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="text-muted">{label}</dt>
      <dd className="m-0">{children}</dd>
    </>
  );
}

/** One delivery: who it goes to and from where, who has it, the proof, the audit trail, and the steps this person may take. */
export function DeliverySheet({ id, onClose }: { id: string | null; onClose: () => void }) {
  const t = useT();
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const q = useQuery({ ...staffQueries.delivery(api, id ?? ''), enabled: id !== null });
  const d = q.data;
  const [dialog, setDialog] = useState<null | 'assign' | 'failed' | 'deliver' | 'correct'>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = (next: DeliveryDetail) => {
    qc.setQueryData(staffKeys.delivery(next.row.shipmentId), next);
    void qc.invalidateQueries({ queryKey: staffKeys.deliveries(), refetchType: 'active' });
  };
  const act = useMutation({
    mutationFn: (run: () => Promise<DeliveryDetail>) => run(),
    onSuccess: (next) => {
      setError(null);
      refresh(next);
    },
    onError: (e) => setError(problemMessage(e))
  });
  // One key per step and shipment version: tapping twice is one action; the next step gets its own.
  const key = useRef({ at: '', value: '' });
  const keyFor = (step: string) => {
    const at = `${id}:${step}:${d?.job.version ?? 0}`;
    if (key.current.at !== at) key.current = { at, value: idempotencyKey() };
    return key.current.value;
  };
  const version = d?.job.version;

  const step = (name: string, run: (shipmentId: string, key: string) => Promise<DeliveryDetail>, done: string) => {
    if (!id) return;
    act.mutate(() => run(id, keyFor(name)), { onSuccess: () => toast.show(done) });
  };

  const status = d?.row.status as DeliveryStatus | undefined;
  const next = new Set(d?.nextStatuses ?? []);
  const iHaveIt = d?.row.assigneeMe === true;
  const orderNo = d?.row.orderNumber ?? '';

  return (
    <Sheet open={id !== null} onOpenChange={(o) => !o && onClose()} title={orderNo ? t('st.dlv.sheetTitle', { order: orderNo }) : t('st.dlv.title')} description={d?.row.shipmentLabel} closeLabel={t('st.dlv.close')}>
      {q.isPending && id ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {d && status ? (
        <div className="flex flex-col gap-4" data-testid="delivery-sheet">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
            <StatusPill tone={STATUS_TONE[status]}>{t(STATUS_LABEL[status])}</StatusPill>
            <span className="text-sm text-muted">{d.row.mode === 'FULFILLED_BY_AFROTRANSACT' ? t('st.dlv.mode.fba') : t('st.dlv.mode.store')}</span>
            {d.row.perishable ? <span className="text-sm font-semibold">{t('st.dlv.perishable')}</span> : null}
          </div>

          {d.canManage ? (
            <div className="flex flex-wrap gap-2" role="group" aria-label={t('st.dlv.steps')}>
              {!d.job.assigneeName && ['ready', 'picked_up', 'in_transit', 'out_for_delivery', 'delivery_failed'].includes(status) ? (
                <Button loading={act.isPending} onClick={() => step('take', (s, k) => api.takeDelivery(s, { version }, k), t('st.dlv.toast.taken'))}>
                  {t('st.dlv.take')}
                </Button>
              ) : null}
              {next.has('picked_up') ? (
                <Button loading={act.isPending} onClick={() => step('picked_up', (s, k) => api.markDeliveryPickedUp(s, { version }, k), t('st.dlv.toast.pickedUp'))}>
                  {t('st.dlv.pickedUp')}
                </Button>
              ) : null}
              {next.has('out_for_delivery') ? (
                <Button loading={act.isPending} onClick={() => step('out', (s, k) => api.markDeliveryOutForDelivery(s, { version }, k), t('st.dlv.toast.out'))}>
                  {status === 'delivery_failed' ? t('st.dlv.reattempt') : t('st.dlv.outForDelivery')}
                </Button>
              ) : null}
              {next.has('delivered') ? <Button onClick={() => setDialog('deliver')}>{t('st.dlv.delivered')}</Button> : null}
              {next.has('delivery_failed') ? (
                <Button variant="neutral" onClick={() => setDialog('failed')}>
                  {t('st.dlv.failed')}
                </Button>
              ) : null}
              {['ready', 'picked_up', 'in_transit', 'out_for_delivery', 'delivery_failed'].includes(status) ? (
                <Button variant="neutral" onClick={() => setDialog('assign')}>
                  {d.job.assigneeName ? t('st.dlv.reassign') : t('st.dlv.assign')}
                </Button>
              ) : null}
            </div>
          ) : (
            <p className="m-0 text-sm text-muted">{t('st.dlv.readOnly')}</p>
          )}
          {error ? (
            <Notice tone="danger" title={t('st.dlv.notDone')} role="alert">
              {error}
            </Notice>
          ) : null}
          {d.canManage && !d.job.assigneeName && status === 'ready' ? <p className="m-0 text-sm text-muted">{t('st.dlv.takeFirst')}</p> : null}

          <section className={section} aria-label={t('st.dlv.deliverTo')}>
            <h3 className={h3}>{t('st.dlv.deliverTo')}</h3>
            <dl className={dl}>
              <Row label={t('st.dlv.customer')}>{d.destination.name}</Row>
              {d.destination.line1 ? (
                <Row label={t('st.dlv.address')}>
                  {[d.destination.line1, d.destination.line2].filter(Boolean).join(', ')}
                  <br />
                  {[customerPlace(d.row), d.destination.postalCode].filter(Boolean).join(' ')}
                </Row>
              ) : (
                <Row label={t('st.dlv.city')}>{customerPlace(d.row)}</Row>
              )}
              {d.destination.phone ? <Row label={t('st.dlv.phone')}>{d.destination.phone}</Row> : null}
              {d.destination.instructions ? <Row label={t('st.dlv.instructions')}>{d.destination.instructions}</Row> : null}
              {d.destination.handToCustomer ? <Row label={t('st.dlv.handover')}>{t('st.dlv.handToCustomer')}</Row> : null}
              <Row label={t('st.dlv.collectFrom')}>{[d.row.sellerName, d.row.originCity].filter(Boolean).join(' · ')}</Row>
              {d.row.promisedBy ? <Row label={t('st.dlv.promisedBy')}>{shortDate(d.row.promisedBy)}</Row> : null}
            </dl>
          </section>

          <section className={section} aria-label={t('st.dlv.items')}>
            <h3 className={h3}>{t('st.dlv.items')}</h3>
            <ul className="m-0 list-none p-0 text-sm">
              {d.items.map((it, i) => (
                <li key={i}>
                  {it.quantity} × {it.title}
                </li>
              ))}
            </ul>
          </section>

          <section className={section} aria-label={t('st.dlv.job')}>
            <h3 className={h3}>{t('st.dlv.job')}</h3>
            <dl className={dl}>
              <Row label={t('st.dlv.assignedTo')}>{d.job.assigneeName ? `${d.job.assigneeName}${iHaveIt ? ` (${t('st.dlv.you')})` : ''}` : t('st.dlv.unassigned')}</Row>
              {d.job.assignedAt ? <Row label={t('st.dlv.since')}>{dateTime(d.job.assignedAt)}</Row> : null}
              <Row label={t('st.dlv.attempts')}>{d.job.attempts}</Row>
              {d.job.lastFailure ? <Row label={t('st.dlv.lastFailure')}>{d.job.lastFailure}</Row> : null}
            </dl>
          </section>

          {d.proof ? (
            <section className={section} aria-label={t('st.dlv.proof.title')} data-testid="delivery-proof-section">
              <div className="flex items-center gap-3">
                <h3 className={h3}>{t('st.dlv.proof.title')}</h3>
                {d.canManage ? (
                  <Button size="sm" variant="link" onClick={() => setDialog('correct')}>
                    {t('st.dlv.proof.correct')}
                  </Button>
                ) : null}
              </div>
              <ul className="m-0 flex list-none flex-wrap gap-3 p-0">
                {d.proof.photoUrls.map((u, i) =>
                  u ? (
                    <li key={d.proof?.photoAssetIds[i] ?? i}>
                      {/* eslint-disable-next-line @next/next/no-img-element -- a short-lived signed link to a private photo */}
                      <img src={u} alt={t('st.dlv.proof.photoAlt', { n: i + 1 })} className="h-32 w-44 rounded-sm object-cover" data-testid="delivery-proof-photo" />
                    </li>
                  ) : (
                    <li key={i} className="flex h-32 w-44 items-center justify-center rounded-sm bg-surface-hover text-xs text-muted">
                      {t('st.dlv.proof.photoUnavailable')}
                    </li>
                  )
                )}
              </ul>
              <dl className={dl}>
                <Row label={t('st.dlv.proof.deliveredAt')}>{dateTime(d.proof.deliveredAt)}</Row>
                <Row label={t('st.dlv.proof.recordedBy')}>{d.proof.recordedByName}</Row>
                {d.proof.recipientName ? <Row label={t('st.dlv.proof.recipient')}>{d.proof.recipientName}</Row> : null}
                {d.proof.note ? <Row label={t('st.dlv.proof.note')}>{d.proof.note}</Row> : null}
                {d.proof.latitude != null && d.proof.longitude != null ? (
                  <Row label={t('st.dlv.proof.position')}>
                    {d.proof.latitude.toFixed(4)}, {d.proof.longitude.toFixed(4)}
                  </Row>
                ) : null}
                {d.proof.corrections > 0 ? (
                  <Row label={t('st.dlv.proof.corrected')}>{t('st.dlv.proof.correctedBy', { n: d.proof.corrections, name: d.proof.correctedByName ?? '', date: dateTime(d.proof.correctedAt) })}</Row>
                ) : null}
              </dl>
            </section>
          ) : null}

          <section className={section} aria-label={t('st.dlv.history')}>
            <h3 className={h3}>{t('st.dlv.history')}</h3>
            {d.audit.length ? (
              <ol className="m-0 flex list-none flex-col gap-1 p-0 text-sm" data-testid="delivery-audit">
                {[...d.audit].reverse().map((a, i) => (
                  <li key={i}>
                    <strong>{AUDIT_LABEL[a.action] ? t(AUDIT_LABEL[a.action] as never) : a.action}</strong>
                    {a.note ? <span> · {a.note}</span> : null}
                    <span className="block text-xs text-muted">
                      {a.actorName} · {dateTime(a.at)}
                    </span>
                  </li>
                ))}
              </ol>
            ) : (
              <p className="m-0 text-sm text-muted">{t('st.dlv.noHistory')}</p>
            )}
          </section>
        </div>
      ) : null}

      {d ? (
        <>
          <AssignDialog open={dialog === 'assign'} onOpenChange={(o) => setDialog(o ? 'assign' : null)} onAssign={(who, k) => act.mutateAsync(() => api.assignDelivery(d.row.shipmentId, who, { version }, k)).then(() => toast.show(t('st.dlv.toast.assigned')))} />
          <FailedDialog open={dialog === 'failed'} onOpenChange={(o) => setDialog(o ? 'failed' : null)} onFail={(reason, k) => act.mutateAsync(() => api.markDeliveryFailed(d.row.shipmentId, reason, version, k)).then(() => toast.show(t('st.dlv.toast.failed')))} />
          <ProofDialog
            open={dialog === 'deliver'}
            onOpenChange={(o) => setDialog(o ? 'deliver' : null)}
            mode="deliver"
            orderNumber={orderNo}
            onSubmit={(v: ProofSubmit) =>
              act
                .mutateAsync(() => api.markDeliveryDelivered(d.row.shipmentId, { photoAssetIds: v.photoAssetIds, recipientName: v.recipientName || undefined, note: v.note || undefined, latitude: v.latitude, longitude: v.longitude, version }, v.key))
                .then(() => toast.show(t('st.dlv.toast.delivered')))
            }
          />
          <ProofDialog
            open={dialog === 'correct'}
            onOpenChange={(o) => setDialog(o ? 'correct' : null)}
            mode="correct"
            orderNumber={orderNo}
            existing={d.proof}
            onSubmit={(v: ProofSubmit) =>
              act
                .mutateAsync(() =>
                  api.correctDeliveryProof(d.row.shipmentId, { photoAssetIds: v.photoAssetIds, recipientName: v.recipientName || undefined, note: v.note || undefined, latitude: v.latitude, longitude: v.longitude, reason: v.reason, version: d.proof?.version }, v.key)
                )
                .then(() => toast.show(t('st.dlv.toast.corrected')))
            }
          />
        </>
      ) : null}
    </Sheet>
  );
}

/** Assign by name to staff holding delivery:manage (the server checks again). */
function AssignDialog({ open, onOpenChange, onAssign }: { open: boolean; onOpenChange: (o: boolean) => void; onAssign: (assigneeId: string, key: string) => Promise<unknown> }) {
  const t = useT();
  const couriers = useQuery({ ...staffQueries.deliveryCouriers(staffApi()), enabled: open });
  const [who, setWho] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const key = useRef('');
  const save = useMutation({
    mutationFn: () => onAssign(who, key.current),
    onSuccess: () => onOpenChange(false),
    onError: (e) => setErr(isApiError(e) && Object.keys(e.fieldErrors).length ? Object.values(e.fieldErrors).join(' ') : problemMessage(e))
  });
  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (o) {
          key.current = idempotencyKey();
          setWho('');
          setErr(null);
        }
        onOpenChange(o);
      }}
      title={t('st.dlv.assign.title')}
      description={t('st.dlv.assign.help')}
      closeLabel={t('st.dlv.close')}
    >
      <form
        noValidate
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (!who) setErr(t('st.dlv.assign.choose'));
          else save.mutate();
        }}
      >
        {couriers.isPending ? <Spinner /> : null}
        {couriers.isError ? <ErrorState error={couriers.error} onRetry={() => void couriers.refetch()} /> : null}
        {couriers.data ? (
          <Select
            label={t('st.dlv.assign.who')}
            placeholder={t('st.dlv.assign.choose')}
            value={who}
            onChange={(e) => setWho(e.currentTarget.value)}
            options={couriers.data.map((c) => ({ value: c.id, label: c.me ? `${c.name} (${t('st.dlv.you')})` : c.name }))}
            error={err}
          />
        ) : null}
        <DialogActions>
          <Button type="button" variant="neutral" onClick={() => onOpenChange(false)}>
            {t('st.dlv.cancel')}
          </Button>
          <Button type="submit" loading={save.isPending}>
            {t('st.dlv.assign.submit')}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

/** A failed attempt needs a reason (the customer is not told it failed; staff can try again). */
function FailedDialog({ open, onOpenChange, onFail }: { open: boolean; onOpenChange: (o: boolean) => void; onFail: (reason: string, key: string) => Promise<unknown> }) {
  const t = useT();
  const [reason, setReason] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const key = useRef('');
  const save = useMutation({
    mutationFn: () => onFail(reason.trim(), key.current),
    onSuccess: () => onOpenChange(false),
    onError: (e) => setErr(isApiError(e) && Object.keys(e.fieldErrors).length ? Object.values(e.fieldErrors).join(' ') : problemMessage(e))
  });
  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (o) {
          key.current = idempotencyKey();
          setReason('');
          setErr(null);
        }
        onOpenChange(o);
      }}
      title={t('st.dlv.failedDialog.title')}
      description={t('st.dlv.failedDialog.help')}
      closeLabel={t('st.dlv.close')}
    >
      <form
        noValidate
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (reason.trim().length < 3) setErr(t('st.dlv.failedDialog.required'));
          else save.mutate();
        }}
      >
        <Field id="failed-reason" label={t('st.dlv.failedDialog.reason')} error={err}>
          {(a11y) => <textarea {...a11y} className={fieldStyles.control} rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.currentTarget.value)} />}
        </Field>
        <DialogActions>
          <Button type="button" variant="neutral" onClick={() => onOpenChange(false)}>
            {t('st.dlv.cancel')}
          </Button>
          <Button type="submit" loading={save.isPending}>
            {t('st.dlv.failedDialog.submit')}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
