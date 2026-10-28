'use client';

import { definitiveFailure, type MembershipInterval, membershipKeys, membershipQueries, type MembershipTier, randomUuid } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, CheckCircle, Skeleton, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRef, useState } from 'react';

import pages from '@/components/account/Pages.module.css';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { accountQueries } from '@/lib/api/queries';
import { signInHref } from '@/lib/links';

import styles from './Membership.module.css';
import { benefitDetail, benefitTitle, everyKey, membershipError } from './memberText';

/**
 * Join a membership (S323): the tiers staff configure, monthly or yearly, and a button that starts the free trial when
 * the shopper is eligible. Joining bills the default saved card; managing it is on the account page.
 */
export function MembershipOfferView() {
  const t = useT();
  const fmt = useFormatters();
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const me = useQuery(accountQueries.me(api));
  const signedIn = me.data?.authenticated === true;
  const offer = useQuery(membershipQueries.offer(api));
  const account = useQuery({ ...membershipQueries.account(api), enabled: signedIn });
  const [interval, setInterval] = useState<MembershipInterval>('MONTHLY');
  const [error, setError] = useState<string | null>(null);
  const [joining, setJoining] = useState<string | null>(null);
  /** One Idempotency-Key per join attempt: a retry after a timeout replays the same charge. */
  const keyRef = useRef(randomUuid());

  const join = useMutation({
    mutationFn: (tier: MembershipTier) => api.joinAccountMembership({ tierCode: tier.code, interval }, keyRef.current),
    onMutate: (tier) => {
      setError(null);
      setJoining(tier.code);
    },
    onSuccess: async (acct, tier) => {
      keyRef.current = randomUuid();
      toast.show(t('mem.joinedToast', { tier: tier.name }));
      qc.setQueryData(membershipQueries.account(api).queryKey, acct);
      await qc.invalidateQueries({ queryKey: membershipKeys.all });
    },
    onError: (e) => {
      if (definitiveFailure(e)) keyRef.current = randomUuid();
      setError(membershipError(t, e));
    },
    onSettled: () => setJoining(null)
  });

  const member = account.data?.member === true;
  const current = account.data?.tier;
  const tiers = offer.data?.tiers ?? [];
  const trialAvailable = account.data?.trialAvailable ?? true;

  return (
    <div className={pages.page}>
      <div className={pages.head}>
        <h1 className={pages.h1}>{t('mem.title')}</h1>
        {signedIn && (
          <Link href="/account/membership" className={buttonClassName({ variant: 'secondary' })}>
            {t('mem.manageLink')}
          </Link>
        )}
      </div>
      <p className={styles.intro}>{t('mem.intro')}</p>
      {member && current && (
        <p className={styles.note} role="status" data-testid="membership-already">
          {t('mem.alreadyMember', { tier: current.name })}
        </p>
      )}

      <div className={styles.toggle} role="group" aria-label={t('mem.title')}>
        {(['MONTHLY', 'YEARLY'] as const).map((i) => (
          <button key={i} type="button" className={styles.toggleOption} aria-pressed={interval === i} onClick={() => setInterval(i)} data-testid={`membership-interval-${i}`}>
            {t(i === 'MONTHLY' ? 'mem.interval.MONTHLY' : 'mem.interval.YEARLY')}
          </button>
        ))}
      </div>

      {error && (
        <p className={pages.error} role="alert" data-testid="membership-error">
          {error}
        </p>
      )}
      {offer.isPending && <Skeleton height={260} />}
      {offer.isError && <p className={pages.error} role="alert">{t('mem.loadError')}</p>}

      <div className={styles.tiers}>
        {tiers.map((tier) => {
          const price = interval === 'YEARLY' ? tier.yearlyPriceMinor : tier.monthlyPriceMinor;
          const yearlySaving = tier.monthlyPriceMinor * 12 - tier.yearlyPriceMinor;
          const trial = trialAvailable && tier.trialDays > 0;
          return (
            <section key={tier.code} className={`${pages.card} ${styles.tier}`} aria-labelledby={`tier-${tier.code}`} data-testid={`membership-tier-${tier.code}`}>
              <div className={styles.tierHead}>
                <h2 id={`tier-${tier.code}`} className={styles.tierName}>{tier.name}</h2>
                {trial && <span className={styles.pill}>{t('mem.trialDays', { days: tier.trialDays })}</span>}
              </div>
              <p className={styles.fine}>{tier.description}</p>
              <p className={styles.price}>
                {fmt.money(price, tier.currency)} <span className={styles.priceUnit}>{t(everyKey(interval))}</span>
              </p>
              {interval === 'YEARLY' && yearlySaving > 0 && <p className={styles.save}>{t('mem.yearlySaving', { amount: fmt.money(yearlySaving, tier.currency) })}</p>}
              <ul className={styles.benefits}>
                {tier.benefits.map((b) => {
                  const detail = benefitDetail(t, fmt, b, tier.currency);
                  return (
                    <li key={b.code} className={styles.benefit}>
                      <CheckCircle weight="fill" className={styles.benefitIcon} aria-hidden />
                      <span className={styles.benefitText}>
                        <span className={styles.benefitTitle}>{benefitTitle(t, fmt, b)}</span>
                        {detail && <span className={styles.benefitDetail}>{detail}</span>}
                      </span>
                    </li>
                  );
                })}
              </ul>
              <p className={styles.fine}>{trial ? t('mem.trialThen', { price: fmt.money(price, tier.currency), every: t(everyKey(interval)) }) : t('mem.billed', { price: fmt.money(price, tier.currency), every: t(everyKey(interval)) })}</p>
              {!signedIn ? (
                <a href={signInHref('/membership')} className={buttonClassName({ block: true })} data-testid={`membership-signin-${tier.code}`}>
                  {t('mem.signInToJoin')}
                </a>
              ) : member ? (
                <Link href="/account/membership" className={buttonClassName({ block: true, variant: 'secondary' })}>
                  {t('mem.manageLink')}
                </Link>
              ) : (
                <Button block loading={joining === tier.code} disabled={join.isPending || !account.isSuccess} onClick={() => join.mutate(tier)} data-testid={`membership-join-${tier.code}`}>
                  {trial ? t('mem.joinTrial', { days: tier.trialDays }) : t('mem.join', { tier: tier.name })}
                </Button>
              )}
            </section>
          );
        })}
      </div>
      <p className={styles.fine}>{t('mem.creditNote')}</p>
    </div>
  );
}
