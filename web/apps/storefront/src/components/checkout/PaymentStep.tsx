'use client';

import { type CheckoutSummary, type PaymentMethod } from '@afrotransact/api-client';
import { PoweredByStripe, ShieldCheck } from '@afrotransact/ui';
import { type RefObject } from 'react';

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
 * Payment section: every saved method is listed, and the full Stripe Payment Element is directly visible on the page
 * in deferred mode. Selecting a saved method selects it for order placement; interacting with the Payment Element
 * switches seamlessly to the new method flow.
 */
export function PaymentStep({ s, total, payWith, setPayWith, pmId, setPmId, save, setSave, newMethod: _newMethod, onNewMethod, payEl, methodTypes }: Props) {
  const t = useT();
  const saved = s.paymentMethods;

  return (
    <div className={styles.payment} id="co-payment">
      {saved.length > 0 && (
        <fieldset className={styles.saved}>
          <legend className={styles.legend}>{t('co.savedMethods')}</legend>
          {saved.map((p) => (
            <label key={p.id} className={styles.choiceCompact} data-testid="checkout-payment">
              <input
                type="radio"
                name="paym"
                checked={payWith === 'saved' && p.id === pmId}
                onChange={() => {
                  setPayWith('saved');
                  setPmId(p.id);
                }}
              />
              <PaymentChip method={p} />
              <span className={styles.choiceText}>
                <strong>{p.label}</strong> <span className={styles.muted}>{pmSub(t, p)}</span>
              </span>
              {p.isDefault && <span className={styles.tag}>{t('pay.default')}</span>}
            </label>
          ))}
        </fieldset>
      )}

      <div className={payWith === 'new' ? styles.newMethodOn : styles.newMethod} data-testid="checkout-new-method">
        <span className={styles.legend}>{saved.length > 0 ? t('co.orNew') : t('co.payWith')}</span>
        <PaymentElementField
          ref={payEl}
          publishableKey={s.publishableKey ?? ''}
          amountMinor={total}
          currency={s.currency}
          save={save}
          stripeCustomerId={s.stripeCustomerId}
          paymentMethodTypes={methodTypes}
          onChange={(c) => {
            onNewMethod(c);
            if (!c.empty) setPayWith('new');
          }}
        />
        <label className={styles.save}>
          <input type="checkbox" checked={save} onChange={(e) => setSave(e.target.checked)} />
          <span>
            {t('co.saveForLater')}
            <small>{t('co.saveForLaterHint')}</small>
          </span>
        </label>
      </div>

      <div className={styles.paymentFoot}>
        <AcceptedPaymentsHint />
        <p className={styles.secureText}>
          <ShieldCheck aria-hidden /> {t('pay.storedByStripe')}
        </p>
        <div className={styles.stripeLine}>
          <PoweredByStripe height={22} />
          <span>{t('co.chargeNote')}</span>
        </div>
      </div>
    </div>
  );
}
