'use client';

import { type AvailableSlot, type BookingCreated, bookingKeys, isApiError, type ServiceMode } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, CheckCircle, Plus, ShieldCheck, Skeleton } from '@afrotransact/ui';
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
 * Service detail and booking (S150): add-ons, mode, live days and times, the deposit now / balance later quote and
 * the provider's cancellation policy; booking holds the deposit with Stripe (bookings' PaymentIntent) and syncs.
 */
export function ServiceDetailView({ serviceId }: { serviceId: string }) {
  const fmt = useFormatters();
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const pathname = usePathname();
  const me = useQuery(accountQueries.me(api));
  const svc = useQuery(shopperQueries.service(api, serviceId));
  const [addOns, setAddOns] = useState<string[]>([]);
  const [mode, setMode] = useState<ServiceMode | null>(null);
  const [slot, setSlot] = useState<AvailableSlot | null>(null);
  const [notes, setNotes] = useState('');
  const [location, setLocation] = useState('');
  const [pmId, setPmId] = useState<string | null>(null);
  const [addPm, setAddPm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<BookingCreated['booking'] | null>(null);
  const [key, setKey] = useState(() => `bk-${crypto.randomUUID()}`);
  const signedIn = me.data?.authenticated === true;
  const methods = useQuery({ ...shopperQueries.paymentMethods(api), enabled: signedIn });
  const s = svc.data;
  const activeMode = mode ?? s?.modes[0] ?? 'IN_PERSON';
  const quote = useQuery({ ...shopperQueries.bookingQuote(api, serviceId, { addOnIds: addOns, mode: activeMode }), enabled: !!s });

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
      setKey(`bk-${crypto.randomUUID()}`);
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

  if (svc.isPending) return <Skeleton height={360} />;
  if (svc.isError || !s) return <p className={styles.error}>{isApiError(svc.error) && svc.error.status === 404 ? t('svc.notFound') : t('acct.loadError')}</p>;

  return (
    <div className={styles.page}>
      <Link href="/services">← {t('svc.all')}</Link>
      <div className={styles.layout}>
        <section className={styles.card} aria-labelledby="svc-title">
          <span className={styles.status}>{s.category}</span>
          <h1 id="svc-title" className={styles.h1}>
            {s.name}
          </h1>
          <Link href={`/services/providers/${encodeURIComponent(s.orgId)}`}>{t('svc.by', { provider: s.providerName })}</Link>
          <p className={styles.muted}>{s.description}</p>
          <dl className={styles.dl}>
            <dt>{t('svc.duration')}</dt>
            <dd>{t('svc.minutes', { n: s.durationMinutes })}</dd>
            <dt>{t('svc.price')}</dt>
            <dd>{fmt.money(s.priceMinor)}</dd>
            <dt>{t('svc.depositNow')}</dt>
            <dd>{fmt.money(s.depositMinor)}</dd>
          </dl>
          <div className={styles.policy}>
            <ShieldCheck aria-hidden />
            <span>
              <strong>{t('svc.policy', { label: s.policy.label })}</strong> {s.policy.text}
            </span>
          </div>
        </section>

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
                <Link href={signInHref(pathname)} className={buttonClassName({ block: true })}>
                  {t('bk.signInToBook')}
                </Link>
              )}
              <span className={styles.muted}>{s.instantBooking ? t('bk.instant') : t('bk.request', { mins: quote.data?.providerAnswersWithinMinutes ?? 120 })}</span>
            </form>
          )}
        </aside>
      </div>
      <AddPaymentMethodDialog open={addPm} onOpenChange={setAddPm} onSaved={(p) => { if (p) setPmId(p.id); }} />
    </div>
  );
}
