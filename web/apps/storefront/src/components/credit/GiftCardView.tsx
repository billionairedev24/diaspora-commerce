'use client';

import { creditKeys, creditQueries, type GiftCard, type GiftCardPurchaseRequest, isApiError, randomUuid } from '@afrotransact/api-client';
import { useFormatters, useLocale } from '@afrotransact/i18n/react';
import { Button, buttonClassName, Skeleton, TextField, useToast } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { type FormEvent, useEffect, useRef, useState } from 'react';

import { AccountCrumb } from '@/components/account/AccountCrumb';
import pages from '@/components/account/Pages.module.css';
import { type PaymentElementChange, PaymentElementField,type PaymentElementHandle } from '@/components/payments/PaymentElementField';
import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './Credit.module.css';

const STATUS_KEYS: Record<GiftCard['status'], MessageKey> = {
  PENDING_PAYMENT: 'gift.status.PENDING_PAYMENT',
  ACTIVE: 'gift.status.ACTIVE',
  REDEEMED: 'gift.status.REDEEMED',
  VOID: 'gift.status.VOID',
  PAYMENT_FAILED: 'gift.status.PAYMENT_FAILED'
};

const LOCALES = ['en', 'fr', 'es', 'ar', 'yo'] as const;
const todayIso = () => new Date().toISOString().slice(0, 10);
/** 09:00 UTC on the chosen day; the email goes out at the first run after that. */
const sendAtOf = (day: string) => (day ? `${day}T09:00:00Z` : undefined);
const parseMajor = (v: string) => {
  const n = Number(v.replace(',', '.').trim());
  return Number.isFinite(n) && n > 0 ? Math.round(n * 100) : 0;
};

/**
 * Buy or send a gift card (S321). The card is bought with a card only (credit never buys credit); the code is emailed
 * to the recipient on the chosen day and redeems into their AfroTransact credit. Payment is Stripe's Payment Element
 * restricted to cards; the purchase is idempotent per attempt, confirmed after Stripe says the payment is authorised.
 */
export function GiftCardView() {
  const t = useT();
  const fmt = useFormatters();
  const locale = useLocale();
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const params = useSearchParams();
  const config = useQuery(creditQueries.giftCardConfig(api));
  const mine = useQuery(creditQueries.giftCards(api));
  const cfg = config.data;

  const [preset, setPreset] = useState<number | null>(5000);
  const [custom, setCustom] = useState('');
  const [recipientEmail, setRecipientEmail] = useState('');
  const [recipientName, setRecipientName] = useState('');
  const [senderName, setSenderName] = useState('');
  const [message, setMessage] = useState('');
  const [sendOn, setSendOn] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [bought, setBought] = useState<GiftCard | null>(null);
  const [cardReady, setCardReady] = useState<PaymentElementChange>({ complete: false, empty: true, type: null });
  const payEl = useRef<PaymentElementHandle>(null);
  const busyRef = useRef(false);
  /** One Idempotency-Key per purchase attempt: a retry after a timeout replays the same card and PaymentIntent. */
  const keyRef = useRef(randomUuid());

  const amountMinor = custom.trim() ? parseMajor(custom) : (preset ?? 0);
  const outOfRange = !!cfg && (amountMinor < cfg.minMinor || amountMinor > cfg.maxMinor);

  // Stripe sends the shopper back here after a redirect step: finish the purchase it belongs to.
  const returned = params.get('card');
  useEffect(() => {
    if (!returned) return;
    api
      .confirmGiftCard(returned)
      .then(async (card) => {
        setBought(card);
        await qc.invalidateQueries({ queryKey: creditKeys.giftCards() });
      })
      .catch((e: unknown) => setError(isApiError(e) ? e.message : t('auth.genericError')));
  }, [returned, api, qc, t]);

  const validate = (): Record<string, string> => {
    const errs: Record<string, string> = {};
    if (!cfg || outOfRange || amountMinor <= 0) errs.amountMinor = t('gift.errAmount', { min: fmt.money(cfg?.minMinor ?? 0), max: fmt.money(cfg?.maxMinor ?? 0) });
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/.test(recipientEmail.trim())) errs.recipientEmail = t('gift.errEmail');
    if (sendOn && sendOn < todayIso()) errs.sendAt = t('gift.errDate');
    return errs;
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (busyRef.current) return;
    const errs = validate();
    setFieldErrors(errs);
    if (Object.keys(errs).length) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      const el = payEl.current;
      if (!el) throw new Error(t('co.needPayment'));
      const submitted = await el.submit();
      if (!submitted.ok) throw new Error(submitted.message);
      const request: GiftCardPurchaseRequest = {
        amountMinor,
        recipientEmail: recipientEmail.trim(),
        recipientName: recipientName.trim() || undefined,
        senderName: senderName.trim() || undefined,
        message: message.trim() || undefined,
        sendAt: sendAtOf(sendOn),
        locale: LOCALES.find((l) => l === locale)
      };
      const purchase = await api.buyGiftCard(request, keyRef.current);
      if (purchase.clientSecret) {
        const returnUrl = `${window.location.origin}/gift-cards?card=${encodeURIComponent(purchase.id)}`;
        const confirmed = await el.confirm(purchase.clientSecret, returnUrl);
        if (!confirmed.ok) throw new Error(confirmed.message);
        if (confirmed.status === 'redirected') return; // back on this page with ?card=
      }
      const card = await api.confirmGiftCard(purchase.id);
      keyRef.current = randomUuid();
      setBought(card);
      toast.show(t('gift.bought'));
      await qc.invalidateQueries({ queryKey: creditKeys.giftCards() });
    } catch (err) {
      if (isApiError(err) && Object.keys(err.fieldErrors).length) setFieldErrors(err.fieldErrors);
      setError(isApiError(err) ? err.message : err instanceof Error ? err.message : t('auth.genericError'));
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const again = () => {
    setBought(null);
    setRecipientEmail('');
    setRecipientName('');
    setMessage('');
    setSendOn('');
    keyRef.current = randomUuid();
  };

  const when = (c: GiftCard) => (c.deliveryStatus === 'SENT' ? t('gift.delivery.SENT', { date: fmt.date(c.sentAt ?? c.sendAt) }) : t('gift.delivery.SCHEDULED', { date: fmt.date(c.sendAt) }));

  return (
    <div className={pages.page}>
      <AccountCrumb>{t('gift.title')}</AccountCrumb>
      <div className={pages.head}>
        <h1 className={pages.h1}>{t('gift.title')}</h1>
        <Link href="/account/credit" className={buttonClassName({ variant: 'secondary' })}>
          {t('gift.toCredit')}
        </Link>
      </div>

      {bought ? (
        <section className={`${pages.card} ${styles.done}`} aria-live="polite" data-testid="gift-card-done">
          <h2 className={pages.h}>{t('gift.doneTitle')}</h2>
          <p>{bought.deliveryStatus === 'SENT' ? t('gift.doneSent', { email: bought.recipientEmail }) : t('gift.doneScheduled', { email: bought.recipientEmail, date: fmt.date(bought.sendAt) })}</p>
          <Button onClick={again}>{t('gift.another')}</Button>
        </section>
      ) : (
        <form className={`${pages.card} ${pages.form}`} onSubmit={(e) => void submit(e)} noValidate data-testid="gift-card-form">
          <p className={pages.muted}>{t('gift.intro')}</p>
          {config.isPending && <Skeleton height={48} />}
          {cfg && (
            <fieldset className={styles.presets}>
              <legend className={styles.legend}>{t('gift.amount')}</legend>
              {cfg.presetsMinor.map((p) => (
                <button key={p} type="button" className={styles.preset} aria-pressed={!custom.trim() && preset === p} onClick={() => { setPreset(p); setCustom(''); }}>
                  {fmt.money(p, cfg.currency)}
                </button>
              ))}
            </fieldset>
          )}
          {cfg && (
            <TextField
              label={t('gift.custom')}
              inputMode="decimal"
              value={custom}
              onChange={(e) => { setCustom(e.target.value); setPreset(null); }}
              help={t('gift.amountRange', { min: fmt.money(cfg.minMinor, cfg.currency), max: fmt.money(cfg.maxMinor, cfg.currency) })}
              error={fieldErrors.amountMinor}
            />
          )}
          <TextField label={t('gift.recipientEmail')} type="email" autoComplete="off" value={recipientEmail} onChange={(e) => setRecipientEmail(e.target.value)} error={fieldErrors.recipientEmail} />
          <TextField label={t('gift.recipientName')} autoComplete="off" maxLength={120} value={recipientName} onChange={(e) => setRecipientName(e.target.value)} />
          <TextField label={t('gift.senderName')} autoComplete="name" maxLength={120} value={senderName} onChange={(e) => setSenderName(e.target.value)} />
          <label className={styles.legend} htmlFor="gift-message">{t('gift.message')}</label>
          <textarea id="gift-message" className={styles.textarea} maxLength={500} value={message} onChange={(e) => setMessage(e.target.value)} />
          <TextField label={t('gift.sendOn')} type="date" min={todayIso()} value={sendOn} onChange={(e) => setSendOn(e.target.value)} help={t('gift.sendOnHelp')} error={fieldErrors.sendAt} />

          <div>
            <p className={styles.legend}>{t('gift.pay')}</p>
            <PaymentElementField
              ref={payEl}
              publishableKey={cfg?.publishableKey ?? ''}
              amountMinor={Math.max(amountMinor, 0)}
              currency={cfg?.currency ?? 'USD'}
              save={false}
              paymentMethodTypes={['card']}
              onChange={setCardReady}
            />
            <p className={styles.fine}>{t('gift.cardOnly')}</p>
          </div>

          {error && <p className={pages.error} role="alert">{error}</p>}
          <Button type="submit" size="lg" loading={busy} disabled={!cfg || busy || (!cardReady.complete && !cardReady.empty)} data-testid="gift-card-buy">
            {busy ? t('gift.buying') : t('gift.buy', { amount: fmt.money(amountMinor, cfg?.currency) })}
          </Button>
          <p className={styles.fine}>{cfg?.terms[locale] || cfg?.terms.en || t('gift.defaultTerms')}</p>
        </form>
      )}

      <section className={pages.card} aria-labelledby="gift-mine">
        <h2 id="gift-mine" className={pages.h}>{t('gift.mine')}</h2>
        {mine.isPending && <Skeleton height={48} />}
        {mine.isSuccess && mine.data.items.length === 0 && <p className={pages.muted}>{t('gift.noneBought')}</p>}
        <ul className={pages.list} data-testid="gift-card-list">
          {mine.data?.items.map((c) => (
            <li key={c.id} className={styles.giftRow} data-testid="gift-card-row">
              <span>
                <strong>{fmt.money(c.amountMinor, c.currency)}</strong> · {c.recipientName || c.recipientEmail}
                <br />
                <span className={styles.entryDate}>{when(c)}</span>
              </span>
              <span className={styles.status}>{t(STATUS_KEYS[c.status])}</span>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
