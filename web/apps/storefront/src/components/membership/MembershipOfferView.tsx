'use client';

import { definitiveFailure, type MembershipInterval, membershipKeys, membershipQueries, type MembershipTier, randomUuid } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, FlatPage, Message, PageHeader, Section, Skeleton, textLinkClassName, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRef, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { accountQueries } from '@/lib/api/queries';
import { signInHref } from '@/lib/links';

import { BenefitList } from './BenefitList';
import styles from './Membership.module.css';
import { everyKey, membershipError } from './memberText';

const INTERVALS: MembershipInterval[] = ['MONTHLY', 'YEARLY'];

/** Whole-percent saving of paying a year at once against twelve months, over the best tier. */
function bestYearlyPercent(tiers: readonly MembershipTier[]): number {
  return tiers.reduce((best, tier) => {
    const full = tier.monthlyPriceMinor * 12;
    return full > 0 ? Math.max(best, Math.round(((full - tier.yearlyPriceMinor) / full) * 100)) : best;
  }, 0);
}

/**
 * Join a membership (S323): the tiers staff configure, monthly or yearly, and a button that starts the free trial when
 * the shopper is eligible. What renews, when it is charged and how to cancel are written out before the button.
 * Joining bills the default saved card; managing it is on the account page.
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
  const percent = bestYearlyPercent(tiers);

  return (
    <FlatPage width="wide">
      <PageHeader
        title={t('mem.title')}
        lead={t('mem.intro')}
        actions={
          signedIn ? (
            <Link href="/account/membership" className={textLinkClassName}>
              {t('mem.manageLink')}
            </Link>
          ) : undefined
        }
      />
      {member && current && (
        <Message tone="success" testId="membership-already">
          {t('mem.alreadyMember', { tier: current.name })}
        </Message>
      )}

      <Section title={t('mem.chooseBilling')} id="mem-billing">
        <fieldset className={styles.radioRows}>
          <legend className="at-visually-hidden">{t('mem.billingGroup')}</legend>
          {INTERVALS.map((i) => (
            <label key={i} className={styles.radioRow}>
              <input type="radio" name="mem-interval" checked={interval === i} onChange={() => setInterval(i)} data-testid={`membership-interval-${i}`} />
              <span className={styles.radioText}>
                <span className={styles.radioTitle}>{t(i === 'MONTHLY' ? 'mem.interval.MONTHLY' : 'mem.interval.YEARLY')}</span>
                <span className={styles.radioDetail}>{i === 'MONTHLY' ? t('mem.billing.MONTHLY') : t('mem.billing.YEARLY', { percent })}</span>
              </span>
            </label>
          ))}
        </fieldset>
      </Section>

      {error && <Message tone="error" testId="membership-error">{error}</Message>}
      {offer.isPending && <Skeleton height={260} />}
      {offer.isError && <Message tone="error">{t('mem.loadError')}</Message>}

      <div className={styles.tiers}>
        {tiers.map((tier, index) => {
          const price = interval === 'YEARLY' ? tier.yearlyPriceMinor : tier.monthlyPriceMinor;
          const yearlySaving = tier.monthlyPriceMinor * 12 - tier.yearlyPriceMinor;
          const trial = trialAvailable && tier.trialDays > 0;
          const money = fmt.money(price, tier.currency);
          const every = t(everyKey(interval));
          return (
            <section key={tier.code} className={styles.tier} aria-labelledby={`tier-${tier.code}`} data-testid={`membership-tier-${tier.code}`}>
              <div className={styles.tierHead}>
                <h2 id={`tier-${tier.code}`} className={styles.tierName}>
                  {tier.name}
                </h2>
                {trial && <span className={styles.trial}>{t('mem.trialDays', { days: tier.trialDays })}</span>}
              </div>
              <p className={styles.fine}>{tier.description}</p>
              <p className={styles.price}>
                {money} <span className={styles.priceUnit}>{every}</span>
              </p>
              {interval === 'YEARLY' && yearlySaving > 0 && <p className={styles.save}>{t('mem.yearlySaving', { amount: fmt.money(yearlySaving, tier.currency) })}</p>}
              <BenefitList benefits={tier.benefits} currency={tier.currency} t={t} fmt={fmt} />
              <div className={styles.disclosure}>
                <p>{trial ? t('mem.disclosure.trial', { days: tier.trialDays, price: money, every }) : t('mem.disclosure.billed', { price: money, every })}</p>
                <p>{t('mem.disclosure.cancel')}</p>
                <p>
                  <Link href="/legal/membership-terms" data-testid={`membership-terms-${tier.code}`}>
                    {t('mem.termsLink')}
                  </Link>
                </p>
              </div>
              <div className={styles.action}>
                {!signedIn ? (
                  <a href={signInHref('/membership')} className={buttonClassName({ variant: index === 0 ? 'primary' : 'neutral' })} data-testid={`membership-signin-${tier.code}`}>
                    {t('mem.signInToJoin')}
                  </a>
                ) : member ? (
                  <Link href="/account/membership" className={buttonClassName({ variant: 'neutral' })}>
                    {t('mem.manageLink')}
                  </Link>
                ) : (
                  <Button
                    variant={index === 0 ? 'primary' : 'neutral'}
                    loading={joining === tier.code}
                    disabled={join.isPending || !account.isSuccess}
                    onClick={() => join.mutate(tier)}
                    data-testid={`membership-join-${tier.code}`}
                  >
                    {trial ? t('mem.joinTrial', { days: tier.trialDays }) : t('mem.join', { tier: tier.name })}
                  </Button>
                )}
              </div>
            </section>
          );
        })}
      </div>
      <p className={styles.fine}>{t('mem.creditNote')}</p>
    </FlatPage>
  );
}
