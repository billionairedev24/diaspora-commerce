'use client';

import {
  cartKeys,
  checkoutKeys,
  definitiveFailure,
  membershipKeys,
  type MembershipSavings,
  type MembershipUpsell as Upsell,
  randomUuid
} from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, Sparkle } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRef, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { accountQueries } from '@/lib/api/queries';

import styles from './Membership.module.css';
import { benefitName, membershipError } from './memberText';

/**
 * "Join Plus to save $X on this order" with the trial and the price (S323). The cart and the checkout show it, and the
 * shopper can join right there on the default saved card; the prices then update at once.
 */
export function MembershipUpsell({ upsell }: { upsell: Upsell }) {
  const t = useT();
  const fmt = useFormatters();
  const api = browserApi();
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  /** One Idempotency-Key per join attempt: a retry after a timeout replays the same charge. */
  const keyRef = useRef(randomUuid());
  const price = fmt.money(upsell.monthlyPriceMinor, upsell.currency);
  const join = useMutation({
    mutationFn: () => api.joinAccountMembership({ tierCode: upsell.tierCode, interval: 'MONTHLY' }, keyRef.current),
    onMutate: () => setError(null),
    onSuccess: async () => {
      keyRef.current = randomUuid();
      await Promise.all([
        qc.invalidateQueries({ queryKey: membershipKeys.all }),
        qc.invalidateQueries({ queryKey: cartKeys.all }),
        qc.invalidateQueries({ queryKey: checkoutKeys.all })
      ]);
    },
    onError: (e) => {
      if (definitiveFailure(e)) keyRef.current = randomUuid();
      setError(membershipError(t, e));
    }
  });
  return (
    <aside className={styles.upsell} aria-label={upsell.tierName} data-testid="membership-upsell">
      <p className={styles.upsellTitle}>
        <Sparkle weight="fill" aria-hidden /> {t('co.mem.upsell', { tier: upsell.tierName, amount: fmt.money(upsell.savingMinor, upsell.currency) })}
      </p>
      <p className={styles.upsellBody}>{upsell.trialDays > 0 ? t('co.mem.upsellTrial', { days: upsell.trialDays, price }) : t('co.mem.upsellNoTrial', { price })}</p>
      {error && (
        <p className={styles.upsellBody} role="alert" data-testid="membership-upsell-error">
          {error}
        </p>
      )}
      <div className={styles.buttons}>
        <Button size="sm" loading={join.isPending} onClick={() => join.mutate()} data-testid="membership-upsell-join">
          {upsell.trialDays > 0 ? t('mem.joinTrial', { days: upsell.trialDays }) : t('co.mem.joinNow')}
        </Button>
        <Link href="/membership" className={styles.upsellLink}>
          {t('co.mem.upsellLink')}
        </Link>
      </div>
    </aside>
  );
}

/** What the membership does for this order, for a member: each fee waived and the credit earned on delivery. */
export function MembershipSavingsNote({ savings }: { savings: MembershipSavings }) {
  const t = useT();
  const fmt = useFormatters();
  if (!savings.member || (savings.waivers.length === 0 && savings.cashbackMinor === 0)) return null;
  return (
    <aside className={styles.upsell} aria-label={savings.tierName ?? ''} data-testid="membership-savings">
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
