'use client';

import { type AvailableSlot, type BookingCreated, bookingKeys, definitiveFailure, isApiError, randomUuid, type ServiceDetail, type ServiceMode } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, CheckCircle, Plus } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { type FormEvent, useEffect, useState } from 'react';

import { AddPaymentMethodDialog } from '@/components/payments/AddPaymentMethodDialog';
import { PaymentChip } from '@/components/payments/PaymentChip';
import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { accountQueries, shopperQueries } from '@/lib/api/queries';
import { signInHref } from '@/lib/links';
import { confirmWithSavedMethod } from '@/lib/payments/stripe';

import styles from './Services.module.css';
import { slotDay, SlotPicker, slotTime } from './SlotPicker';

const modeKey = (m: ServiceMode) => `svc.mode.${m}` as MessageKey;

/**
 * The booking panel of a service (S150): add-ons, mode, live days and times, the deposit now / balance later quote; booking
 * holds the deposit with Stripe (bookings' PaymentIntent) and syncs. Shared by the service page and a provider's storefront.
 */
export function BookingPanel({ service: s }: { service: ServiceDetail }) {
  const serviceId = s.id;
  const fmt = useFormatters();
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const pathname = usePathname();
  const me = useQuery(accountQueries.me(api));
  const [addOns, setAddOns] = useState<string[]>([]);
  const [mode, setMode] = useState<ServiceMode | null>(null);
  const [slot, setSlot] = useState<AvailableSlot | null>(null);
  const [notes, setNotes] = useState('');
  const [location, setLocation] = useState('');
  const [pmId, setPmId] = useState<string | null>(null);
  const [addPm, setAddPm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<BookingCreated['booking'] | null>(null);
  const [key, setKey] = useState(() => `bk-${randomUuid()}`);
  const signedIn = me.data?.authenticated === true;
  const methods = useQuery({ ...shopperQueries.paymentMethods(api), enabled: signedIn });
  const activeMode = mode ?? s.modes[0] ?? 'IN_PERSON';
  const quote = useQuery({ ...shopperQueries.bookingQuote(api, serviceId, { addOnIds: addOns, mode: activeMode }), enabled: true });

  useEffect(() => {
    const list = methods.data;
    if (list && (!pmId || !list.some((p) => p.id === pmId))) setPmId((list.find((p) => p.isDefault && p.type === 'card') ?? list.find((p) => p.type === 'card') ?? list[0])?.id ?? null);
  }, [methods.data, pmId]);

  const book = useMutation({
    mutationFn: async () => {
      const created = await api.bookService({ serviceId, startAt: slot?.start ?? '', mode: activeMode, addOnIds: addOns, notes: notes.trim() || undefined, location: location.trim() || undefined }, key);
      if (created.payment && created.booking.awaitingPayment) {
        const res = await confirmWithSavedMethod(created.payment.publishableKey, created.payment.clientSecret, pmId ?? '');
        if (!res.ok) throw new Error(res.message);
        return api.syncBookingPayment(created.booking.id);
      }
      return created.booking;
    },
    onSuccess: async (b) => {
      setDone(b);
      await qc.invalidateQueries({ queryKey: bookingKeys.all });
    },
    onError: (e) => {
      // Same key for a retry after a timeout or a card error (bookings replays the booking and its deposit
      // PaymentIntent, so the deposit is never taken twice); a new one only once the attempt was refused (4xx).
      if (definitiveFailure(e)) setKey(`bk-${randomUuid()}`);
      setError(isApiError(e) ? (e.fieldErrors.startAt ?? Object.values(e.fieldErrors)[0] ?? e.message) : e instanceof Error ? e.message : t('auth.genericError'));
      void qc.invalidateQueries({ queryKey: ['services', 'slots'] });
    }
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!slot) return setError(t('bk.pickTime'));
    if (activeMode === 'AT_HOME' && !location.trim()) return setError(t('bk.needAddress'));
    if (!pmId) return setError(t('bk.needCard'));
    book.mutate();
  };


  return (
    <>
        <aside className={styles.panel} aria-label={t('bk.panel')}>
          {done ? (
            <div className={styles.field} role="status" data-testid="booking-done">
              <strong className={styles.success}>
                <CheckCircle weight="fill" aria-hidden /> {done.status === 'CONFIRMED' ? t('bk.confirmed') : t('bk.requested')}
              </strong>
              <span>{t('bk.doneWhen', { day: slotDay(fmt.tag, done.startAt), time: slotTime(fmt.tag, done.startAt), ref: done.reference })}</span>
              <span className={styles.muted}>{t('bk.liveStatus')}</span>
              <Link href="/account/bookings" className={buttonClassName({ size: 'sm' })}>
                {t('svc.yourAppointments')}
              </Link>
            </div>
          ) : (
            <form className={styles.field} onSubmit={submit} noValidate>
              {s.addOns.length > 0 && (
                <fieldset className={styles.field}>
                  <legend>
                    <strong>{t('bk.addOns')}</strong>
                  </legend>
                  {s.addOns.map((a) => (
                    <label key={a.id} className={styles.check}>
                      <input type="checkbox" checked={addOns.includes(a.id)} onChange={(e) => setAddOns((x) => (e.target.checked ? [...x, a.id] : x.filter((y) => y !== a.id)))} />
                      {a.name} · +{fmt.money(a.priceMinor)}
                    </label>
                  ))}
                </fieldset>
              )}
              {s.modes.length > 1 && (
                <fieldset className={styles.field}>
                  <legend>
                    <strong>{t('bk.where')}</strong>
                  </legend>
                  {s.modes.map((m) => (
                    <label key={m} className={styles.check}>
                      <input type="radio" name="mode" checked={activeMode === m} onChange={() => setMode(m)} />
                      {t(modeKey(m))}
                    </label>
                  ))}
                </fieldset>
              )}
              <SlotPicker serviceId={serviceId} addOnIds={addOns} value={slot} onChange={setSlot} />
              {activeMode === 'AT_HOME' && (
                <label className={styles.field}>
                  {t('bk.homeAddress')}
                  <input value={location} onChange={(e) => setLocation(e.target.value)} maxLength={300} autoComplete="street-address" />
                </label>
              )}
              <label className={styles.field}>
                {t('bk.note', { provider: s.providerName })}
                <textarea rows={2} maxLength={1000} value={notes} onChange={(e) => setNotes(e.target.value)} placeholder={t('bk.notePlaceholder')} />
              </label>
              {quote.data && (
                <dl className={styles.dl}>
                  <dt>{t('svc.price')}</dt>
                  <dd>{fmt.money(quote.data.priceMinor)}</dd>
                  <dt>{t('bk.dueNow')}</dt>
                  <dd>
                    <strong>{fmt.money(quote.data.depositNowMinor)}</strong>
                  </dd>
                  <dt>{t('bk.dueAfter')}</dt>
                  <dd>{fmt.money(quote.data.balanceLaterMinor)}</dd>
                </dl>
              )}
              {signedIn ? (
                <fieldset className={styles.field}>
                  <legend>
                    <strong>{t('bk.payWith')}</strong>
                  </legend>
                  {methods.data?.map((p) => (
                    <label key={p.id} className={styles.check}>
                      <input type="radio" name="bkpm" checked={pmId === p.id} onChange={() => setPmId(p.id)} />
                      <PaymentChip method={p} /> {p.label}
                    </label>
                  ))}
                  <button type="button" className="link-button" onClick={() => setAddPm(true)}>
                    <Plus aria-hidden /> {t('co.addPayment')}
                  </button>
                </fieldset>
              ) : null}
              {error && <p className={styles.error} role="alert">{error}</p>}
              {signedIn ? (
                <Button type="submit" variant="secondary" loading={book.isPending} block>
                  {quote.data ? t('bk.bookCta', { amount: fmt.money(quote.data.depositNowMinor) }) : t('bk.book')}
                </Button>
              ) : (
                <a href={signInHref(pathname)} className={buttonClassName({ block: true })}>
                  {t('bk.signInToBook')}
                </a>
              )}
              <span className={styles.muted}>{s.instantBooking ? t('bk.instant') : t('bk.request', { mins: quote.data?.providerAnswersWithinMinutes ?? 120 })}</span>
            </form>
          )}
        </aside>
      <AddPaymentMethodDialog open={addPm} onOpenChange={setAddPm} onSaved={(p) => { if (p) setPmId(p.id); }} />
    </>
  );
}
