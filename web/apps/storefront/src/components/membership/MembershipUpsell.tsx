'use client';

import { membershipKeys, type MembershipSavings, type MembershipUpsell as Upsell } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Sparkle } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { accountQueries } from '@/lib/api/queries';

import styles from './Membership.module.css';
import { benefitName } from './memberText';

/**
 * One quiet line under the totals: "Save $X on this order with Plus. Try free for 14 days." with a single link to the
 * membership page, where the price, the terms and the button are. It names no benefit and no price of its own: the amount
 * and the trial come from the quote (the tier staff configured), and joining happens on the membership page.
 */
export function MembershipUpsell({ upsell }: { upsell: Upsell }) {
  const t = useT();
  const fmt = useFormatters();
  return (
    <p className={styles.upsellLine} data-testid="membership-upsell">
      {t('co.mem.upsell', { tier: upsell.tierName, amount: fmt.money(upsell.savingMinor, upsell.currency) })}{' '}
      <Link href="/membership" className={styles.upsellLink} data-testid="membership-upsell-link">
        {upsell.trialDays > 0 ? t('co.mem.upsellTry', { days: upsell.trialDays }) : t('co.mem.upsellLink')}
      </Link>
    </p>
  );
}

/** What the membership does for this order, for a member: each fee waived and the credit earned on delivery. */
export function MembershipSavingsNote({ savings }: { savings: MembershipSavings }) {
  const t = useT();
  const fmt = useFormatters();
  if (!savings.member || (savings.waivers.length === 0 && savings.cashbackMinor === 0)) return null;
  return (
    <aside className={styles.savings} aria-label={savings.tierName ?? ''} data-testid="membership-savings">
      {savings.waivers.map((w) => (
        <p key={w.packageId} className={styles.upsellTitle}>
          <Sparkle weight="fill" aria-hidden /> {t('co.mem.waivedRow')}: {t('co.mem.waivedNote', { tier: savings.tierName ?? '', benefit: benefitName(t, fmt, w.benefit), amount: fmt.money(w.waivedMinor) })} · {w.seller}
        </p>
      ))}
      {savings.cashbackMinor > 0 && (
        <p className={styles.upsellBody} data-testid="membership-earn">
          {t('co.mem.earn', { amount: fmt.money(savings.cashbackMinor) })}
        </p>
      )}
    </aside>
  );
}

/**
 * The cart's version: asks payments (through the BFF) what the membership does for the cart as it is now. A shopper who
 * is not a member sees what joining would save; a member sees the fees waived and the credit earned. Nothing shows for
 * guests or carts a membership would not change.
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
  const data = savings.data;
  if (!data) return null;
  if (data.upsell) return <MembershipUpsell upsell={data.upsell} />;
  return <MembershipSavingsNote savings={data} />;
}
