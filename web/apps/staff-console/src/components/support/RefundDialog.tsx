'use client';

import { type Customer360, staffKeys, type SupportRefundForm, SupportRefundFormSchema } from '@afrotransact/api-client';
import { Button, Dialog, Field, fieldStyles, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { problemMessage } from '@/components/ui/States';
import { staffApi } from '@/lib/api';
import { idempotencyKey, money, toMinor } from '@/lib/format';

/**
 * Refund within the role's limit: decide the customer's open claim with a refund (trust-ops), or issue a partial refund
 * on the order (tax-returns). The limit is checked here and enforced by the BFF (403 REFUND_LIMIT).
 */
export function RefundDialog({ c360, open, onOpenChange, caseId, preset }: { c360: Customer360; open: boolean; onOpenChange: (o: boolean) => void; caseId?: string; preset?: { orderNumber: string; claimId?: string } }) {
  const qc = useQueryClient();
  const toast = useToast();
  const openClaims = c360.claims.filter((c) => c.open);
  const [via, setVia] = useState<'ORDER' | 'CLAIM'>(preset?.claimId ? 'CLAIM' : 'ORDER');
  const [orderNumber, setOrderNumber] = useState(preset?.orderNumber ?? c360.orders[0]?.orderNumber ?? '');
  const [claimId, setClaimId] = useState(preset?.claimId ?? '');
  const [amount, setAmount] = useState(() => {
    const claim = c360.claims.find((c) => c.id === preset?.claimId);
    return claim ? (claim.amountMinor / 100).toFixed(2) : '';
  });
  const [reason, setReason] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const limit = c360.refundLimitMinor;
  const refund = useMutation({
    mutationFn: (body: SupportRefundForm) => staffApi().refund({ ...body, customerId: c360.customer.id }, idempotencyKey()),
    onSuccess: (r) => {
      toast.show(r.message);
      onOpenChange(false);
      void qc.invalidateQueries({ queryKey: staffKeys.customer(c360.customer.id) });
    }
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const parsed = SupportRefundFormSchema.safeParse({ via, orderNumber, claimId: via === 'CLAIM' ? claimId : undefined, amountMinor: toMinor(amount), reason, caseId });
    const found: Record<string, string> = {};
    if (!parsed.success) parsed.error.issues.forEach((i) => (found[String(i.path[0] ?? '_')] ??= i.message));
    if (Number.isNaN(toMinor(amount))) found.amountMinor = 'Enter an amount, e.g. 12.50';
    else if (limit !== null && toMinor(amount) > limit) found.amountMinor = `Your roles can refund up to ${money(limit)}`;
    setErrors(found);
    if (parsed.success && Object.keys(found).length === 0) refund.mutate(parsed.data);
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="Refund the customer" description={limit === null ? 'Your roles have no refund limit.' : `Your roles can refund up to ${money(limit)}.`} width={480}>
      <form noValidate onSubmit={submit} className="flex flex-col gap-3">
        <Select
          label="Refund through"
          value={via}
          onChange={(e) => setVia(e.currentTarget.value === 'CLAIM' ? 'CLAIM' : 'ORDER')}
          options={[
            { value: 'ORDER', label: 'The order (partial refund, no return)' },
            { value: 'CLAIM', label: 'An open A-to-z claim', disabled: openClaims.length === 0 }
          ]}
        />
        <Select label="Order" value={orderNumber} placeholder="Choose…" onChange={(e) => setOrderNumber(e.currentTarget.value)} error={errors.orderNumber} options={c360.orders.map((o) => ({ value: o.orderNumber, label: `${o.orderNumber} · ${money(o.totalMinor)}` }))} />
        {via === 'CLAIM' ? (
          <Select
            label="Claim"
            value={claimId}
            placeholder="Choose…"
            error={errors.claimId}
            onChange={(e) => {
              const id = e.currentTarget.value;
              setClaimId(id);
              const claim = openClaims.find((c) => c.id === id);
              if (claim) {
                setOrderNumber(claim.orderNumber);
                setAmount((claim.amountMinor / 100).toFixed(2));
              }
            }}
            options={openClaims.map((c) => ({ value: c.id, label: `${c.reference} · ${money(c.amountMinor)}` }))}
          />
        ) : null}
        <TextField label="Amount ($)" inputMode="decimal" placeholder="0.00" value={amount} onChange={(e) => setAmount(e.currentTarget.value)} error={errors.amountMinor} help={via === 'CLAIM' ? 'A claim refund is for the claimed amount.' : undefined} />
        <Field label="Reason (the customer sees it)" error={errors.reason}>
          {(a11y) => <textarea {...a11y} className={fieldStyles.control} rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.currentTarget.value)} />}
        </Field>
        {refund.isError ? (
          <Notice tone="danger" title="Not refunded." role="alert">
            {problemMessage(refund.error)}
          </Notice>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" loading={refund.isPending}>
            Refund
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
