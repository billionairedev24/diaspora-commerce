'use client';

import { staffKeys, staffQueries,type SupportShipment } from '@afrotransact/api-client';
import { Button, Dialog, Field, fieldStyles, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type ReactNode, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { staffApi } from '@/lib/api';
import { dateTime, humanize, idempotencyKey, money } from '@/lib/format';

import { RefundDialog } from './RefundDialog';

const tone = (status: string) =>
  /DELIVERED|COMPLETED|RESOLVED_REFUND|REFUNDED|SENT|RESOLVED/.test(status) ? 'good' : /CANCELLED|FAILED|DENIED|REJECTED/.test(status) ? 'bad' : /OPEN|REVIEW|REQUESTED|PLACED/.test(status) ? 'brand' : 'info';

function Section({ title, count, children }: { title: string; count: number; children: ReactNode }) {
  return (
    <Card title={`${title} (${count})`}>
      {count ? children : <p className="m-0 text-sm text-muted">None.</p>}
    </Card>
  );
}

/** Customer 360 with the support actions the staff member's roles allow; every action is recorded upstream. */
export function CustomerPanel({ customerId, caseId }: { customerId: string; caseId?: string }) {
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const q = useQuery(staffQueries.customer(api, customerId));
  const [refundFor, setRefundFor] = useState<{ orderNumber: string; claimId?: string } | null>(null);
  const [cancel, setCancel] = useState<{ orderNumber: string; shipment: SupportShipment } | null>(null);
  const [reason, setReason] = useState('');
  const refresh = () => void qc.invalidateQueries({ queryKey: staffKeys.customer(customerId) });
  const resend = useMutation({
    mutationFn: (id: string) => api.resendNotification(id, customerId, idempotencyKey()),
    onSuccess: (r) => {
      toast.show(r.message);
      refresh();
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const cancelShipment = useMutation({
    mutationFn: () => (cancel ? api.cancelShipment(cancel.shipment.id, { customerId, orderNumber: cancel.orderNumber, reason }, idempotencyKey()) : Promise.reject(new Error('none'))),
    onSuccess: (r) => {
      toast.show(r.message);
      setCancel(null);
      setReason('');
      refresh();
    }
  });

  if (q.isPending) return <Spinner label="Loading the customer…" />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const c = q.data;
  return (
    <div className="flex flex-col gap-4">
      <Card
        title={c.customer.name}
        actions={
          c.can.refund && c.orders.length ? (
            <Button size="sm" onClick={() => setRefundFor({ orderNumber: c.orders[0]?.orderNumber ?? '' })}>
              Refund…
            </Button>
          ) : null
        }
      >
        <p className="m-0 text-sm text-muted">
          {c.customer.email} · {c.customer.enabled ? 'Active' : 'Disabled'} · email {c.customer.emailVerified ? 'verified' : 'not verified'}
        </p>
        {c.membership ? (
          <p className="m-0 flex flex-wrap items-center gap-2 text-sm" data-testid="support-membership">
            <StatusPill tone="brand">{c.membership.tierName} member</StatusPill>
            {c.membership.prioritySupport ? <StatusPill tone="good">Priority support: handle first</StatusPill> : null}
            {c.membership.freeReturns ? <StatusPill tone="info">Free returns</StatusPill> : null}
          </p>
        ) : null}
        <p className="m-0 text-xs text-muted">
          Refund limit: {c.refundLimitMinor === null ? 'no limit' : c.refundLimitMinor === 0 ? 'your roles may not refund' : money(c.refundLimitMinor)} · every action is recorded in the audit log by the service that performs it.
        </p>
      </Card>
      {c.unavailable.length ? (
        <Notice tone="warning" title="Some details are unavailable:">
          {c.unavailable.join(', ')}. Those sections stay empty until the service answers.
        </Notice>
      ) : null}
      <Section title="Orders" count={c.orders.length}>
        <ul className="m-0 flex list-none flex-col gap-3 p-0">
          {c.orders.map((o) => (
            <li key={o.orderNumber} className="flex flex-col gap-1.5 rounded-md border border-border p-3">
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <Link href={`/orders?open=${encodeURIComponent(o.orderNumber)}`} className="font-mono text-xs">
                  {o.orderNumber}
                </Link>
                <StatusPill tone={tone(o.status)}>{humanize(o.status)}</StatusPill>
                <span className="flex-1 text-muted">{dateTime(o.placedAt)}</span>
                <strong>{money(o.totalMinor, o.currency)}</strong>
              </div>
              {o.shipments.map((s) => (
                <div key={s.id} className="flex flex-wrap items-center gap-2 pl-2 text-xs">
                  <span className="font-semibold">{s.label}</span>
                  <span className="text-muted">{s.sellerName}</span>
                  <StatusPill tone={tone(s.status)}>{humanize(s.status)}</StatusPill>
                  {s.trackingNumber ? <span className="font-mono">{s.carrier} {s.trackingNumber}</span> : null}
                  <span className="flex-1" />
                  {c.can.cancelShipment && s.cancellable ? (
                    <Button size="sm" variant="neutral" className="text-danger" onClick={() => setCancel({ orderNumber: o.orderNumber, shipment: s })}>
                      Cancel shipment
                    </Button>
                  ) : null}
                </div>
              ))}
            </li>
          ))}
        </ul>
      </Section>
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,320px),1fr))] gap-4">
        <Section title="Claims" count={c.claims.length}>
          <ul className="m-0 flex list-none flex-col gap-2 p-0 text-sm">
            {c.claims.map((k) => (
              <li key={k.id} className="flex flex-wrap items-center gap-2">
                <Link href={`/disputes?open=${encodeURIComponent(k.id)}`} className="font-mono text-xs">
                  {k.reference}
                </Link>
                <StatusPill tone={tone(k.status)}>{humanize(k.status)}</StatusPill>
                <span className="flex-1">{money(k.amountMinor)}</span>
                {k.open && c.can.refund ? (
                  <Button size="sm" variant="neutral" onClick={() => setRefundFor({ orderNumber: k.orderNumber, claimId: k.id })}>
                    Refund claim
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        </Section>
        <Section title="Returns" count={c.returns.length}>
          <ul className="m-0 flex list-none flex-col gap-2 p-0 text-sm">
            {c.returns.map((r) => (
              <li key={r.id} className="flex flex-wrap items-center gap-2">
                <span className="font-mono text-xs">{r.number}</span>
                <StatusPill tone={tone(r.status)}>{humanize(r.status)}</StatusPill>
                <span className="flex-1 text-muted">order {r.orderNumber}</span>
                <span>{money(r.estimateMinor)}</span>
              </li>
            ))}
          </ul>
        </Section>
        <Section title="Bookings" count={c.bookings.length}>
          <ul className="m-0 flex list-none flex-col gap-2 p-0 text-sm">
            {c.bookings.map((b) => (
              <li key={b.id}>
                {b.service} · {dateTime(b.startsAt)} · {humanize(b.status)}
              </li>
            ))}
          </ul>
        </Section>
        <Section title="Notifications" count={c.notifications.length}>
          <ul className="m-0 flex list-none flex-col gap-2 p-0 text-sm">
            {c.notifications.map((n) => (
              <li key={n.id} className="flex flex-wrap items-center gap-2">
                <span className="flex-1">
                  {humanize(n.templateKey)} · {n.channel} · <span className="text-muted">{dateTime(n.createdAt)}</span>
                </span>
                <StatusPill tone={tone(n.status)}>{humanize(n.status)}</StatusPill>
                {c.can.resend ? (
                  <Button size="sm" variant="neutral" loading={resend.isPending && resend.variables === n.id} onClick={() => resend.mutate(n.id)}>
                    Resend
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        </Section>
        <Section title="Support cases" count={c.cases.length}>
          <ul className="m-0 flex list-none flex-col gap-2 p-0 text-sm">
            {c.cases.map((k) => (
              <li key={k.id} className={k.id === caseId ? 'rounded-sm bg-gold-50 p-2' : undefined}>
                <span className="font-mono text-xs">{k.reference}</span> · {k.reason} <StatusPill tone={tone(k.status)}>{humanize(k.status)}</StatusPill>
                <br />
                <span className="text-xs text-muted">{k.summary}</span>
              </li>
            ))}
          </ul>
        </Section>
        <Section title="Audit trail" count={c.audit.length}>
          <ol className="m-0 flex list-none flex-col gap-1.5 p-0 text-xs">
            {c.audit.map((a, i) => (
              <li key={`${a.at}-${i}`}>
                <span className="text-muted">{dateTime(a.at)}</span> · <strong>{a.actor}</strong> · <span className="font-mono">{a.action}</span> — {a.summary}
              </li>
            ))}
          </ol>
        </Section>
      </div>
      {refundFor ? <RefundDialog c360={c} open onOpenChange={(o) => !o && setRefundFor(null)} caseId={caseId} preset={refundFor} /> : null}
      <Dialog open={cancel !== null} onOpenChange={(o) => !o && setCancel(null)} title="Cancel the shipment" width={440}>
        <form
          noValidate
          className="flex flex-col gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            if (reason.trim().length >= 3) cancelShipment.mutate();
          }}
        >
          <p className="m-0 text-sm">
            {cancel?.shipment.label} of {cancel?.orderNumber} ({cancel ? money(cancel.shipment.totalMinor) : ''}). Shipments can be cancelled until they are packed.
          </p>
          <Field label="Reason" error={reason.length > 0 && reason.trim().length < 3 ? 'Give a reason (3 characters or more)' : null}>
            {(a11y) => <textarea {...a11y} className={fieldStyles.control} rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.currentTarget.value)} />}
          </Field>
          {cancelShipment.isError ? (
            <Notice tone="danger" title="Not cancelled." role="alert">
              {problemMessage(cancelShipment.error)}
            </Notice>
          ) : null}
          <div className="flex justify-end gap-2">
            <Button variant="neutral" onClick={() => setCancel(null)}>
              Keep it
            </Button>
            <Button type="submit" loading={cancelShipment.isPending} disabled={reason.trim().length < 3}>
              Cancel shipment
            </Button>
          </div>
        </form>
      </Dialog>
    </div>
  );
}
