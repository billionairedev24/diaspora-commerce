'use client';

import { type CheckoutSummary, type PaymentMethod } from '@afrotransact/api-client';
import { Button, PaymentMethodMark, Plus, ShieldCheck, X } from '@afrotransact/ui';
import { type RefObject, useEffect, useRef, useState } from 'react';

import { AcceptedPaymentsHint } from '@/components/payments/AcceptedPaymentsHint';
import { PaymentChip } from '@/components/payments/PaymentChip';
import { type PaymentElementChange, PaymentElementField, type PaymentElementHandle } from '@/components/payments/PaymentElementField';
import { type MessageKey, type Translator, useT } from '@/i18n/t';

import styles from './Checkout.module.css';

const pmSub = (t: Translator<MessageKey>, pm: PaymentMethod) => (pm.expires ? t('pay.expires', { date: pm.expires }) : pm.type === 'us_bank_account' ? t('pay.bankAccount') : t('pay.wallet'));

const TYPE_NAMES: Record<string, string> = {
  card: 'Card', link: 'Link', cashapp: 'Cash App Pay', amazon_pay: 'Amazon Pay', klarna: 'Klarna', affirm: 'Affirm', afterpay_clearpay: 'Afterpay',
  us_bank_account: 'US bank account', google_pay: 'Google Pay', apple_pay: 'Apple Pay', samsung_pay: 'Samsung Pay', paypal: 'PayPal', revolut_pay: 'Revolut Pay'
};

/** "Klarna", "Google Pay" … for the method chosen in the Payment Element. */
export const newMethodName = (type: string | null) => (type ? (TYPE_NAMES[type] ?? type.replace(/_/g, ' ')) : 'Card');

type Props = {
  s: CheckoutSummary;
  total: number;
  payWith: 'saved' | 'new' | null;
  setPayWith: (v: 'saved' | 'new') => void;
  pmId: string | null;
  setPmId: (id: string) => void;
  save: boolean;
  setSave: (v: boolean) => void;
  newMethod: PaymentElementChange;
  onNewMethod: (c: PaymentElementChange) => void;
  payEl: RefObject<PaymentElementHandle | null>;
  methodTypes: string[] | undefined;
};

/**
 * Payment, Amazon style: every saved method is listed (the default selected). "Use another payment method" opens a
 * modal with Stripe's Payment Element — every method enabled in the Dashboard (cards, Google Pay, Apple Pay, Samsung
 * Pay, Link, Klarna, Affirm, Amazon Pay, Cash App, US bank account …). The modal stays mounted after it closes so the
 * entered method is confirmed by Place order. The form shows inline only when nothing is saved yet.
 */
export function PaymentStep({ s, total, payWith, setPayWith, pmId, setPmId, save, setSave, newMethod, onNewMethod, payEl, methodTypes }: Props) {
  const t = useT();
  const saved = s.paymentMethods;
  const [open, setOpen] = useState(false);
  /** The element is created the first time the modal opens and kept afterwards. */
  const [mounted, setMounted] = useState(false);
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    panel.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  const element = (
    <>
      <PaymentElementField
        ref={payEl}
        publishableKey={s.publishableKey ?? ''}
        amountMinor={total}
        currency={s.currency}
        save={save}
        stripeCustomerId={s.stripeCustomerId}
        paymentMethodTypes={methodTypes}
        onChange={onNewMethod}
      />
      <label className={styles.save}>
        <input type="checkbox" checked={save} onChange={(e) => setSave(e.target.checked)} />
        <span>
          {t('co.saveForLater')}
          <small>{t('co.saveForLaterHint')}</small>
        </span>
      </label>
    </>
  );

  if (!saved.length) {
    return (
      <div className={styles.payment} id="co-payment">
        <div className={styles.newMethodOn} data-testid="checkout-new-method">
          {element}
        </div>
        <PaymentFoot t={t} />
      </div>
    );
  }

  const newChosen = payWith === 'new';
  return (
    <div className={styles.payment} id="co-payment">
      <fieldset className={styles.saved}>
        <legend className={styles.legend}>{t('co.savedMethods')}</legend>
        {saved.map((p) => (
          <label key={p.id} className={styles.choiceCompact} data-testid="checkout-payment">
            <input type="radio" name="paym" checked={!newChosen && p.id === pmId} onChange={() => setPmId(p.id)} />
            <PaymentChip method={p} />
            <span className={styles.choiceText}>
              <strong>{p.label}</strong> <span className={styles.muted}>{pmSub(t, p)}</span>
            </span>
            {p.isDefault && <span className={styles.tag}>{t('pay.default')}</span>}
          </label>
        ))}
        {newChosen ? (
          <label className={styles.choiceCompact} data-testid="checkout-payment-new">
            <input type="radio" name="paym" checked readOnly />
            <span aria-hidden className={styles.newMark}>
              <PaymentMethodMark type={newMethod.type === 'us_bank_account' ? 'us_bank_account' : (newMethod.type ?? 'card')} height={26} />
            </span>
            <span className={styles.choiceText}>
              <strong>{newMethodName(newMethod.type)}</strong> <span className={styles.muted}>{t('co.newMethodEntered')}</span>
            </span>
            <button type="button" className="link-button" onClick={() => setOpen(true)}>
              {t('co.change')}
            </button>
          </label>
        ) : null}
      </fieldset>
      <button
        type="button"
        className={`link-button ${styles.addMethod}`}
        onClick={() => {
          setMounted(true);
          setOpen(true);
        }}
        data-testid="checkout-add-method"
      >
        <Plus aria-hidden /> {t('co.useAnotherMethod')}
      </button>
      <PaymentFoot t={t} />

      {mounted ? (
        <div className={styles.modalLayer} hidden={!open} data-testid="checkout-payment-modal">
          <div className={styles.modalBackdrop} onClick={() => setOpen(false)} aria-hidden />
          <div ref={panel} className={styles.modal} role="dialog" aria-modal="true" aria-labelledby="co-pm-modal-title" tabIndex={-1}>
            <div className={styles.modalHead}>
              <h2 id="co-pm-modal-title" className={styles.modalTitle}>{t('co.useAnotherMethod')}</h2>
              <button type="button" className={styles.modalClose} onClick={() => setOpen(false)} aria-label={t('common.cancel')}>
                <X aria-hidden />
              </button>
            </div>
            <div className={styles.modalBody}>{element}</div>
            <div className={styles.modalFoot}>
              <Button variant="secondary" onClick={() => setOpen(false)}>
                {t('common.cancel')}
              </Button>
              <Button
                disabled={!newMethod.complete && newMethod.empty}
                onClick={() => {
                  setPayWith('new');
                  setOpen(false);
                }}
                data-testid="checkout-use-method"
              >
                {t('co.useThisMethod')}
              </Button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function PaymentFoot({ t }: { t: Translator<MessageKey> }) {
  return (
    <div className={styles.paymentFoot}>
      <AcceptedPaymentsHint />
      <p className={styles.secureText}>
        <ShieldCheck aria-hidden /> {t('pay.storedByStripe')}
      </p>
    </div>
  );
}
