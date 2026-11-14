'use client';

import { Button, buttonClassName } from '@afrotransact/ui';
import Link from 'next/link';

import { useT } from '@/i18n/t';

import styles from './Checkout.module.css';

type Props = {
  /** `error`: the cart could not be read (Try again); `empty`: nothing in the cart; `none-selected`: items, all unticked. */
  kind: 'error' | 'empty' | 'none-selected';
  retrying?: boolean;
  onRetry?: () => void;
};

/**
 * What the checkout page says instead of an order, so a cart that was not read (or has nothing to buy) is never shown as
 * "0 items, $0.00, Place your order": the shopper is told which of the three it is and what to do next.
 */
export function CheckoutState({ kind, retrying = false, onRetry }: Props) {
  const t = useT();
  const text =
    kind === 'error'
      ? { title: t('co.errorTitle'), body: t('co.loadErrorBody') }
      : kind === 'empty'
        ? { title: t('co.cartEmptyTitle'), body: t('co.cartEmptyBody') }
        : { title: t('co.noneSelectedTitle'), body: t('co.noneSelectedBody') };
  return (
    <div className={styles.empty} data-testid={`checkout-${kind}`}>
      <h1 className={styles.title}>{text.title}</h1>
      <p className={kind === 'error' ? styles.error : styles.muted} role={kind === 'error' ? 'alert' : undefined}>
        {text.body}
      </p>
      {kind === 'error' && onRetry && (
        <Button loading={retrying} onClick={onRetry} data-testid="checkout-retry">
          {t('co.retry')}
        </Button>
      )}
      <Link href={kind === 'empty' ? '/' : '/cart'} className={buttonClassName({ variant: kind === 'error' ? 'secondary' : 'primary' })}>
        {kind === 'empty' ? t('cart.continue') : t('common.goToCart')}
      </Link>
    </div>
  );
}
