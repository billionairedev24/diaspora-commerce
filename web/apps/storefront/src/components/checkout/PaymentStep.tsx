'use client';

import { type CheckoutSummary, type PaymentMethod } from '@afrotransact/api-client';
import { Plus, ShieldCheck } from '@afrotransact/ui';
import { type RefObject } from 'react';

import { AcceptedPaymentsHint } from '@/components/payments/AcceptedPaymentsHint';
import { PaymentChip } from '@/components/payments/PaymentChip';
import { type PaymentElementChange, PaymentElementField, type PaymentElementHandle } from '@/components/payments/PaymentElementField';
import { type MessageKey, type Translator, useT } from '@/i18n/t';

import styles from './Checkout.module.css';

const pmSub = (t: Translator<MessageKey>, pm: PaymentMethod) => (pm.expires ? t('pay.expires', { date: pm.expires }) : pm.type === 'us_bank_account' ? t('pay.bankAccount') : t('pay.wallet'));

type Props = {
  s: CheckoutSummary;
  total: number;
  pmId: string | null;
  setPmId: (id: string) => void;
  /** Opens the add-a-method dialog (saved to the account, then selected). */
  onAddMethod: () => void;
  save: boolean;
  setSave: (v: boolean) => void;
  onNewMethod: (c: PaymentElementChange) => void;
  payEl: RefObject<PaymentElementHandle | null>;
  methodTypes: string[] | undefined;
};

/**
 * Payment, Amazon style: every saved method is listed and the default one is selected; another card or bank account is
 * added in a dialog (saved, then selected). The full payment form shows inline only when nothing is saved yet.
 */
export function PaymentStep({ s, total, pmId, setPmId, onAddMethod, save, setSave, onNewMethod, payEl, methodTypes }: Props) {
  const t = useT();
  const saved = s.paymentMethods;
  return (
    <div className={styles.payment} id="co-payment">
      {saved.length > 0 ? (
        <>
          <fieldset className={styles.saved}>
            <legend className={styles.legend}>{t('co.savedMethods')}</legend>
            {saved.map((p) => (
              <label key={p.id} className={styles.choiceCompact} data-testid="checkout-payment">
                <input type="radio" name="paym" checked={p.id === pmId} onChange={() => setPmId(p.id)} />
                <PaymentChip method={p} />
                <span className={styles.choiceText}>
                  <strong>{p.label}</strong> <span className={styles.muted}>{pmSub(t, p)}</span>
                </span>
                {p.isDefault && <span className={styles.tag}>{t('pay.default')}</span>}
              </label>
            ))}
          </fieldset>
          <button type="button" className={`link-button ${styles.addMethod}`} onClick={onAddMethod} data-testid="checkout-add-method">
            <Plus aria-hidden /> {t('co.addPaymentMethod')}
          </button>
        </>
      ) : (
        <div className={styles.newMethodOn} data-testid="checkout-new-method">
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
        </div>
      )}
      <div className={styles.paymentFoot}>
        <AcceptedPaymentsHint />
        <p className={styles.secureText}>
          <ShieldCheck aria-hidden /> {t('pay.storedByStripe')}
        </p>
      </div>
    </div>
  );
}
