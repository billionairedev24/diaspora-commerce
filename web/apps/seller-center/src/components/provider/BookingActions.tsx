'use client';

import { type BookingAction, type BookingCard, sellerKeys } from '@afrotransact/api-client';
import { Button, Dialog, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { idempotencyKey } from '@/lib/format';
import { useSeller } from '@/lib/session';

export const ACTION_LABELS: Record<BookingAction, string> = {
  CONFIRM: 'Confirm', DECLINE: 'Decline', PROPOSE: 'Propose a new time', COMPLETE: 'Mark complete', NO_SHOW: 'Mark no-show', CANCEL: 'Cancel booking'
};

/** Buttons for a booking's allowed actions; decline/cancel ask for a reason, propose asks for a new time. */
export function BookingActions({ booking, compact = false }: { booking: BookingCard; compact?: boolean }) {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can } = useSeller();
  const [ask, setAsk] = useState<BookingAction | null>(null);
  const [reason, setReason] = useState('');
  const [when, setWhen] = useState('');
  const [error, setError] = useState<string | null>(null);
  const act = useMutation({
    mutationFn: (v: { action: BookingAction; reason?: string; proposedStartAt?: string }) => api.actOnBooking(booking.id, v, idempotencyKey()),
    onSuccess: (b) => {
      setAsk(null);
      void qc.invalidateQueries({ queryKey: sellerKeys.requests(orgId) });
      void qc.invalidateQueries({ queryKey: sellerKeys.calendars(orgId) });
      void qc.invalidateQueries({ queryKey: sellerKeys.session() });
      toast.show(`${b.reference}: ${b.statusLabel}`);
    },
    onError: (e) => (ask ? setError(problemMessage(e)) : toast.show(problemMessage(e), 'error'))
  });
  if (!can('requests') || !booking.allowedActions.length) return null;
  return (
    <>
      <div className="flex flex-wrap gap-1.5">
        {booking.allowedActions.map((a) => (
          <Button key={a} size="sm" variant={a === 'CONFIRM' || a === 'COMPLETE' ? 'secondary' : 'neutral'} disabled={act.isPending}
            onClick={() => (a === 'DECLINE' || a === 'CANCEL' || a === 'PROPOSE' ? (setAsk(a), setError(null), setReason(''), setWhen('')) : act.mutate({ action: a }))}>
            {compact && a === 'PROPOSE' ? 'New time' : ACTION_LABELS[a]}
          </Button>
        ))}
      </div>
      <Dialog open={ask !== null} onOpenChange={(o) => (o ? undefined : setAsk(null))} title={ask ? `${ACTION_LABELS[ask]} · ${booking.reference}` : ''}
        description={ask === 'CANCEL' ? 'Cancelling within 24 hours of the start costs a $15 late-cancellation fee and a strike.' : ask === 'PROPOSE' ? 'The customer accepts or declines the new time.' : 'The deposit authorisation is released and the customer is told why.'}>
        <form className="flex flex-col gap-2" onSubmit={(e) => {
          e.preventDefault();
          if (ask === 'PROPOSE') {
            if (!when || new Date(when) <= new Date()) return setError('Choose a future time');
            act.mutate({ action: 'PROPOSE', proposedStartAt: new Date(when).toISOString() });
          } else if (ask) {
            if (!reason.trim()) return setError('Tell the customer why');
            act.mutate({ action: ask, reason: reason.trim() });
          }
        }}>
          {ask === 'PROPOSE' ? (
            <TextField label="New start time" type="datetime-local" value={when} error={error} onChange={(e) => setWhen(e.target.value)} />
          ) : (
            <TextField label="Reason (shared with the customer)" value={reason} error={error} maxLength={500} onChange={(e) => setReason(e.target.value)} />
          )}
          <Button type="submit" loading={act.isPending}>{ask ? ACTION_LABELS[ask] : ''}</Button>
        </form>
      </Dialog>
    </>
  );
}
