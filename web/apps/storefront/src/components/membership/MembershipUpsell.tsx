'use client';

import { membershipKeys,type MembershipUpsell as Upsell } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Sparkle } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { accountQueries } from '@/lib/api/queries';

import styles from './Membership.module.css';

/** "Join Plus to save $X on this order" with the trial and the price (S323); the checkout and the cart show it. */
export function MembershipUpsell({ upsell }: { upsell: Upsell }) {
  const t = useT();
  const fmt = useFormatters();
  const price = fmt.money(upsell.monthlyPriceMinor, upsell.currency);
  return (
    <aside className={styles.upsell} aria-label={upsell.tierName} data-testid="membership-upsell">
      <p className={styles.upsellTitle}>
        <Sparkle weight="fill" aria-hidden /> {t('co.mem.upsell', { tier: upsell.tierName, amount: fmt.money(upsell.savingMinor, upsell.currency) })}
      </p>
      <p className={styles.upsellBody}>{upsell.trialDays > 0 ? t('co.mem.upsellTrial', { days: upsell.trialDays, price }) : t('co.mem.upsellNoTrial', { price })}</p>
      <Link href="/membership" className={styles.upsellLink}>
        {t('co.mem.upsellLink')}
      </Link>
    </aside>
  );
}

/**
 * The cart's version: asks payments (through the BFF) what joining would save on the cart as it is now, for a signed-in
 * shopper who is not a member. Nothing shows for guests, members or carts a membership would not change.
 */
export function CartMembershipUpsell({ subtotalMinor, selectedCount }: { subtotalMinor: number; selectedCount: number }) {
  const api = browserApi();
  const me = useQuery(accountQueries.me(api));
  const savings = useQuery({
    // The answer depends on what is in the cart: a changed cart asks again.
    queryKey: [...membershipKeys.cart(), subtotalMinor, selectedCount],
    queryFn: ({ signal }) => api.getCartMembershipSavings(signal),
    staleTime: 30_000,
    enabled: me.data?.authenticated === true && selectedCount > 0
  });
  const upsell = savings.data?.upsell;
  return upsell ? <MembershipUpsell upsell={upsell} /> : null;
}
