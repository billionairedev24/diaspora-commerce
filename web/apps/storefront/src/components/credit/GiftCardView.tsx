'use client';

import { creditKeys, creditQueries, type GiftCard, type GiftCardPurchaseRequest, isApiError, randomUuid } from '@afrotransact/api-client';
import { useFormatters, useLocale } from '@afrotransact/i18n/react';
import { Button, cx, EmptyState, fieldWidth, FlatPage, FormActions, Message, Row, RowList, Section, Skeleton, StatusText, textLinkClassName, TextField, useToast } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { type FormEvent, useEffect, useRef, useState } from 'react';

import { AccountHeader } from '@/components/account/AccountHeader';
import { type PaymentElementChange, PaymentElementField,type PaymentElementHandle } from '@/components/payments/PaymentElementField';
import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './GiftCard.module.css';

const STATUS_KEYS: Record<GiftCard['status'], MessageKey> = {
  PENDING_PAYMENT: 'gift.status.PENDING_PAYMENT',
  ACTIVE: 'gift.status.ACTIVE',
  REDEEMED: 'gift.status.REDEEMED',
  VOID: 'gift.status.VOID',
  PAYMENT_FAILED: 'gift.status.PAYMENT_FAILED'
};

const MESSAGE_MAX = 500;
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
  const [deliver, setDeliver] = useState<'now' | 'later'>('now');
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
    if (deliver === 'later' && !sendOn) errs.sendAt = t('gift.errDateRequired');
    else if (deliver === 'later' && sendOn < todayIso()) errs.sendAt = t('gift.errDate');
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
        sendAt: deliver === 'later' ? sendAtOf(sendOn) : undefined,
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
    setDeliver('now');
    keyRef.current = randomUuid();
  };

  const when = (c: GiftCard) => (c.deliveryStatus === 'SENT' ? t('gift.delivery.SENT', { date: fmt.date(c.sentAt ?? c.sendAt) }) : t('gift.delivery.SCHEDULED', { date: fmt.date(c.sendAt) }));
  const currency = cfg?.currency;

  return (
    <FlatPage width="wide">
      <AccountHeader title={t('gift.title')} via={[{ label: t('credit.title'), href: '/account/credit' }]} lead={t('gift.intro')} />

      {bought ? (
        <Section title={t('gift.doneTitle')} id="gift-done" testId="gift-card-done">
          <div aria-live="polite">
            <Message tone="success">{bought.deliveryStatus === 'SENT' ? t('gift.doneSent', { email: bought.recipientEmail }) : t('gift.doneScheduled', { email: bought.recipientEmail, date: fmt.date(bought.sendAt) })}</Message>
          </div>
          <FormActions>
            <Button onClick={again}>{t('gift.another')}</Button>
            <Link href="/account/credit" className={textLinkClassName}>
              {t('credit.title')}
            </Link>
          </FormActions>
        </Section>
      ) : (
        <div className={styles.layout}>
          <form className={styles.formCol} onSubmit={(e) => void submit(e)} noValidate data-testid="gift-card-form" aria-label={t('gift.title')}>
            <Section title={t('gift.amount')} id="gift-amount">
              {config.isPending && <Skeleton height={48} />}
              {cfg && (
                <fieldset className={styles.chips} aria-labelledby="gift-amount-h">
                  {cfg.presetsMinor.map((p) => (
                    <button
                      key={p}
                      type="button"
                      className={styles.chip}
                      aria-pressed={!custom.trim() && preset === p}
                      onClick={() => {
                        setPreset(p);
                        setCustom('');
                      }}
                    >
                      {fmt.money(p, cfg.currency)}
                    </button>
                  ))}
                </fieldset>
              )}
              {cfg && (
                <TextField
                  className={fieldWidth.short}
                  label={t('gift.custom')}
                  inputMode="decimal"
                  value={custom}
                  onChange={(e) => {
                    setCustom(e.target.value);
                    setPreset(null);
                  }}
                  help={t('gift.amountRange', { min: fmt.money(cfg.minMinor, cfg.currency), max: fmt.money(cfg.maxMinor, cfg.currency) })}
                  error={fieldErrors.amountMinor}
                />
              )}
            </Section>

            <Section title={t('gift.sectionRecipient')} id="gift-recipient">
              <TextField className={fieldWidth.fit} label={t('gift.recipientName')} autoComplete="off" maxLength={120} value={recipientName} onChange={(e) => setRecipientName(e.target.value)} />
              <TextField className={fieldWidth.fit} label={t('gift.recipientEmail')} type="email" autoComplete="off" value={recipientEmail} onChange={(e) => setRecipientEmail(e.target.value)} error={fieldErrors.recipientEmail} />
              <div className={fieldWidth.wide}>
                <label className={styles.textareaLabel} htmlFor="gift-message">
                  {t('gift.message')}
                </label>
                <textarea id="gift-message" className={styles.textarea} maxLength={MESSAGE_MAX} value={message} onChange={(e) => setMessage(e.target.value)} aria-describedby="gift-message-count" />
                <p id="gift-message-count" className={styles.counter}>
                  {t('gift.messageCount', { count: message.length, max: MESSAGE_MAX })}
                </p>
              </div>
              <TextField className={fieldWidth.fit} label={t('gift.senderName')} autoComplete="name" maxLength={120} value={senderName} onChange={(e) => setSenderName(e.target.value)} />
            </Section>

            <Section title={t('gift.sectionDelivery')} id="gift-delivery">
              <fieldset className={styles.radios}>
                <legend className={styles.legend}>{t('gift.deliverLegend')}</legend>
                <label className={styles.radio}>
                  <input type="radio" name="gift-deliver" checked={deliver === 'now'} onChange={() => setDeliver('now')} />
                  {t('gift.deliverNow')}
                </label>
                <label className={styles.radio}>
                  <input type="radio" name="gift-deliver" checked={deliver === 'later'} onChange={() => setDeliver('later')} />
                  {t('gift.deliverLater')}
                </label>
              </fieldset>
              {deliver === 'later' && (
                <TextField className={fieldWidth.fit} label={t('gift.sendOn')} type="date" min={todayIso()} value={sendOn} onChange={(e) => setSendOn(e.target.value)} help={t('gift.sendOnHelp')} error={fieldErrors.sendAt} />
              )}
            </Section>

            <Section title={t('gift.pay')} id="gift-pay">
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
            </Section>

            <div>
              <p className={styles.fine}>
                {t('gift.cardsByEmail')}{' '}
                <Link href="/legal/gift-card-terms" data-testid="gift-terms-link">
                  {t('gift.termsLink')}
                </Link>
              </p>
              <p className={styles.fine}>{cfg?.terms[locale] || cfg?.terms.en || t('gift.defaultTerms')}</p>
            </div>
            {error && <Message tone="error">{error}</Message>}
            <FormActions>
              <Button type="submit" size="lg" loading={busy} disabled={!cfg || busy || (!cardReady.complete && !cardReady.empty)} data-testid="gift-card-buy">
                {busy ? t('gift.buying') : t('gift.buy', { amount: fmt.money(amountMinor, currency) })}
              </Button>
            </FormActions>
          </form>

          <aside className={styles.previewCol} aria-label={t('gift.previewTitle')}>
            <GiftPreview amountMinor={amountMinor} currency={currency} recipientName={recipientName} recipientEmail={recipientEmail} senderName={senderName} message={message} deliver={deliver} sendOn={sendOn} />
          </aside>
        </div>
      )}

      <Section title={t('gift.mine')} id="gift-mine">
        {mine.isPending && <Skeleton height={48} />}
        {mine.isSuccess && mine.data.items.length === 0 && <EmptyState>{t('gift.noneBought')}</EmptyState>}
        {mine.isSuccess && mine.data.items.length > 0 && (
          <RowList testId="gift-card-list">
            {mine.data.items.map((c) => (
              <Row
                key={c.id}
                testId="gift-card-row"
                title={
                  <>
                    <strong>{fmt.money(c.amountMinor, c.currency)}</strong> · {c.recipientName || c.recipientEmail}
                  </>
                }
                detail={when(c)}
                actions={<StatusText tone={c.status === 'ACTIVE' || c.status === 'REDEEMED' ? 'positive' : c.status === 'PAYMENT_FAILED' ? 'negative' : 'neutral'}>{t(STATUS_KEYS[c.status])}</StatusText>}
              />
            ))}
          </RowList>
        )}
      </Section>
    </FlatPage>
  );
}

interface PreviewProps {
  amountMinor: number;
  currency: string | undefined;
  recipientName: string;
  recipientEmail: string;
  senderName: string;
  message: string;
  deliver: 'now' | 'later';
  sendOn: string;
}

/** The gift card email as the recipient will see it, updating as the form is filled in. */
function GiftPreview({ amountMinor, currency, recipientName, recipientEmail, senderName, message, deliver, sendOn }: PreviewProps) {
  const t = useT();
  const fmt = useFormatters();
  const sender = senderName.trim();
  const name = recipientName.trim();
  const note = message.trim();
  return (
    <>
      <h2 className={styles.legend}>{t('gift.previewTitle')}</h2>
      <div className={styles.mail} data-testid="gift-preview">
        <div className={styles.mailMeta}>
          <span className={styles.mailSubject}>{sender ? t('gift.previewSubject', { sender }) : t('gift.previewSubjectAnon')}</span>
          <span>{recipientEmail.trim() ? t('gift.previewTo', { email: recipientEmail.trim() }) : t('gift.previewToEmpty')}</span>
        </div>
        <p className={styles.mailAmount}>{fmt.money(Math.max(amountMinor, 0), currency)}</p>
        <p className={styles.mailText}>{name ? t('gift.previewHello', { name }) : t('gift.previewHelloAnon')}</p>
        <p className={styles.mailText}>{t('gift.previewBody')}</p>
        <p className={cx(styles.mailNote, !note && styles.mailNoteEmpty)}>{note || t('gift.previewMessageEmpty')}</p>
        {sender ? <p className={styles.mailText}>{t('gift.previewFrom', { sender })}</p> : null}
        <p className={styles.mailCodeLabel}>{t('gift.previewCode')}</p>
        <p className={styles.mailCode}>{t('credit.redeem.placeholder')}</p>
        <span className={styles.mailButton}>{t('gift.previewCta')}</span>
        <p className={styles.mailFoot}>{deliver === 'later' && sendOn ? t('gift.previewOn', { date: fmt.date(`${sendOn}T09:00:00Z`) }) : t('gift.previewNow')}</p>
        <p className={styles.mailFoot}>{t('gift.previewFooter')}</p>
      </div>
    </>
  );
}
