'use client';

import { type AvailableSlot, bookingKeys, type CustomerBooking, isApiError } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, EmptyState, FlatPage, Message, Section, Skeleton, Star, textLinkClassName, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { AccountHeader } from '@/components/account/AccountHeader';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import styles from './Services.module.css';
import { slotDay, SlotPicker, slotTime } from './SlotPicker';

type Mode = 'idle' | 'cancel' | 'reschedule' | 'review';

function BookingCard({ b }: { b: CustomerBooking }) {
  const fmt = useFormatters();
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [mode, setMode] = useState<Mode>('idle');
  const [slot, setSlot] = useState<AvailableSlot | null>(null);
  const [rating, setRating] = useState(0);
  const [text, setText] = useState('');
  const quote = useQuery({ ...shopperQueries.cancellationQuote(api, b.id), enabled: mode === 'cancel' });
  const refresh = () => qc.invalidateQueries({ queryKey: bookingKeys.all });
  const fail = (e: unknown) => toast.show(isApiError(e) ? (Object.values(e.fieldErrors)[0] ?? e.message) : t('auth.genericError'));
  const cancel = useMutation({
    mutationFn: () => api.cancelBooking(b.id),
    onSuccess: async (r) => { setMode('idle'); await refresh(); toast.show(t('bk.cancelledRefund', { amount: fmt.money(r.applied.refundMinor) })); },
    onError: fail
  });
  const reschedule = useMutation({ mutationFn: () => api.rescheduleBooking(b.id, slot?.start ?? ''), onSuccess: async () => { setMode('idle'); await refresh(); toast.show(t('bk.rescheduleSent')); }, onError: fail });
  const answer = useMutation({ mutationFn: (accept: boolean) => api.answerProposedTime(b.id, accept), onSuccess: () => refresh(), onError: fail });
  const review = useMutation({ mutationFn: () => api.reviewBooking(b.id, rating, text.trim()), onSuccess: async () => { setMode('idle'); await refresh(); toast.show(t('bk.thanksReview')); }, onError: fail });
  const sync = useMutation({ mutationFn: () => api.syncBookingPayment(b.id), onSuccess: () => refresh(), onError: fail });

  return (
    <section className={`${styles.card} ${styles.bookingFlat}`} data-testid="booking" aria-label={`${b.service.name} · ${b.service.providerName}`}>
      <div className={styles.booking}>
        <div className={styles.field}>
          <span className={styles.status}>{b.statusLabel}</span>
          <strong>
            {b.service.name} · {b.service.providerName}
          </strong>
          <span>{t('bk.when', { day: slotDay(fmt.tag, b.startAt), time: slotTime(fmt.tag, b.startAt) })}</span>
          {b.proposedStartAt && <span className={styles.success}>{t('bk.proposed', { day: slotDay(fmt.tag, b.proposedStartAt), time: slotTime(fmt.tag, b.proposedStartAt) })}</span>}
          <span className={styles.muted}>
            {t('bk.money', { deposit: fmt.money(b.service.depositMinor), balance: fmt.money(b.service.balanceMinor) })} · {b.reference}
          </span>
        </div>
        <div className={styles.actions}>
          {b.awaitingPayment && (
            <Button size="sm" onClick={() => sync.mutate()} loading={sync.isPending}>
              {t('bk.checkPayment')}
            </Button>
          )}
          {b.status === 'RESCHEDULE_PROPOSED' && (
            <>
              <Button size="sm" variant="secondary" onClick={() => answer.mutate(true)} loading={answer.isPending}>
                {t('bk.acceptTime')}
              </Button>
              <Button size="sm" variant="neutral" onClick={() => answer.mutate(false)}>
                {t('bk.declineTime')}
              </Button>
            </>
          )}
          {b.canReschedule && (
            <Button size="sm" variant="neutral" onClick={() => setMode(mode === 'reschedule' ? 'idle' : 'reschedule')}>
              {t('bk.reschedule')}
            </Button>
          )}
          {b.canCancel && (
            <Button size="sm" variant="neutral" onClick={() => setMode(mode === 'cancel' ? 'idle' : 'cancel')}>
              {t('bk.cancel')}
            </Button>
          )}
          {b.canReview && (
            <Button size="sm" variant="neutral" onClick={() => setMode(mode === 'review' ? 'idle' : 'review')}>
              {t('bk.review')}
            </Button>
          )}
          <Link href={`/services/${encodeURIComponent(b.service.serviceId)}`} className={buttonClassName({ variant: 'link', size: 'sm' })}>
            {t('bk.bookAgain')}
          </Link>
        </div>
      </div>
      {mode === 'cancel' && (
        <div className={styles.confirm} role="alertdialog" aria-label={t('bk.cancel')}>
          {quote.isPending ? (
            <Skeleton height={32} />
          ) : quote.data ? (
            <>
              <strong>{t('bk.refundQuote', { amount: fmt.money(quote.data.refundMinor) })}</strong>
              <span>{quote.data.explanation}</span>
            </>
          ) : (
            <span>{t('acct.loadError')}</span>
          )}
          <div className={styles.actions}>
            <Button size="sm" onClick={() => cancel.mutate()} loading={cancel.isPending} disabled={!quote.data}>
              {t('bk.confirmCancel')}
            </Button>
            <Button size="sm" variant="neutral" onClick={() => setMode('idle')}>
              {t('bk.keep')}
            </Button>
          </div>
        </div>
      )}
      {mode === 'reschedule' && (
        <div className={styles.field}>
          <SlotPicker serviceId={b.service.serviceId} addOnIds={[]} value={slot} onChange={setSlot} />
          <div className={styles.actions}>
            <Button size="sm" onClick={() => reschedule.mutate()} disabled={!slot} loading={reschedule.isPending}>
              {t('bk.askNewTime')}
            </Button>
          </div>
        </div>
      )}
      {mode === 'review' && (
        <form className={styles.field} onSubmit={(e) => { e.preventDefault(); if (rating) review.mutate(); }}>
          <div className={styles.stars} role="group" aria-label={t('bk.rating')}>
            {[1, 2, 3, 4, 5].map((n) => (
              <button key={n} type="button" className={styles.star} aria-pressed={n <= rating} aria-label={t('bk.stars', { n })} onClick={() => setRating(n)}>
                <Star weight={n <= rating ? 'fill' : 'regular'} aria-hidden />
              </button>
            ))}
          </div>
          <textarea rows={2} maxLength={2000} value={text} onChange={(e) => setText(e.target.value)} aria-label={t('bk.reviewText')} placeholder={t('bk.reviewPlaceholder')} />
          <div className={styles.actions}>
            <Button type="submit" size="sm" disabled={!rating} loading={review.isPending}>
              {t('bk.submitReview')}
            </Button>
          </div>
        </form>
      )}
    </section>
  );
}

/** Your appointments (S150): live status, exact refund before cancelling, reschedule, answer a proposed time, review. */
export function BookingsView() {
  const t = useT();
  const q = useQuery(shopperQueries.bookings(browserApi()));
  const now = Date.now();
  const upcoming = (q.data ?? []).filter((b) => new Date(b.endAt).getTime() >= now && !b.status.startsWith('CANCELLED') && b.status !== 'DECLINED');
  const past = (q.data ?? []).filter((b) => !upcoming.includes(b));
  return (
    <FlatPage>
      <AccountHeader title={t('svc.yourAppointments')} crumb={t('acct.bookings')} />
      {q.isPending && <Skeleton height={120} />}
      {q.isError && <Message tone="error">{t('acct.loadError')}</Message>}
      {q.isSuccess && q.data.length === 0 && (
        <EmptyState action={<Link href="/services" className={textLinkClassName}>{t('svc.findService')}</Link>}>{t('bk.none')}</EmptyState>
      )}
      {upcoming.length > 0 && (
        <Section title={t('bk.upcoming')} id="bk-upcoming">
          {upcoming.map((b) => (
            <BookingCard key={b.id} b={b} />
          ))}
        </Section>
      )}
      {past.length > 0 && (
        <Section title={t('bk.past')} id="bk-past">
          {past.map((b) => (
            <BookingCard key={b.id} b={b} />
          ))}
        </Section>
      )}
    </FlatPage>
  );
}
