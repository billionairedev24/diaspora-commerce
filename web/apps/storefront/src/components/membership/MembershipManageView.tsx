'use client';

import {
  cartKeys,
  checkoutKeys,
  definitiveFailure,
  type MembershipAccount,
  type MembershipInterval,
  membershipKeys,
  membershipQueries,
  type MembershipTier,
  randomUuid
} from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, CheckCircle, Skeleton, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { AccountCrumb } from '@/components/account/AccountCrumb';
import pages from '@/components/account/Pages.module.css';
import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './Membership.module.css';
import { benefitDetail, benefitName, benefitTitle, everyKey, membershipError } from './memberText';

const STATUS_KEYS: Record<string, MessageKey> = {
  TRIALING: 'mem.status.TRIALING',
  ACTIVE: 'mem.status.ACTIVE',
  PAST_DUE: 'mem.status.PAST_DUE',
  CANCELED: 'mem.status.CANCELED',
  LAPSED: 'mem.status.LAPSED',
  EXPIRED: 'mem.status.EXPIRED'
};

const priceOf = (tier: MembershipTier, interval: MembershipInterval) => (interval === 'YEARLY' ? tier.yearlyPriceMinor : tier.monthlyPriceMinor);

/**
 * Manage the membership (S323): status, renewal or end date, what it gives, upgrade now or switch at the end of the
 * period, cancel (benefits run to the end of the period) or cancel with a refund while the refund rule allows it.
 */
export function MembershipManageView() {
  const t = useT();
  const fmt = useFormatters();
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const account = useQuery(membershipQueries.account(api));
  const uses = useQuery({ ...membershipQueries.benefits(api), enabled: account.data?.member === true });
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<'cancel' | 'refund' | null>(null);

  const refresh = async (acct: MembershipAccount) => {
    qc.setQueryData(membershipQueries.account(api).queryKey, acct);
    await Promise.all([
      qc.invalidateQueries({ queryKey: membershipKeys.all }),
      qc.invalidateQueries({ queryKey: cartKeys.all }),
      qc.invalidateQueries({ queryKey: checkoutKeys.all })
    ]);
  };
  const failed = (e: unknown) => setError(membershipError(t, e));

  const change = useMutation({
    mutationFn: ({ tier, interval }: { tier: MembershipTier; interval: MembershipInterval }) => api.changeAccountMembership({ tierCode: tier.code, interval }, randomUuid()),
    onMutate: () => setError(null),
    onSuccess: async (acct) => {
      await refresh(acct);
      toast.show(acct.membership?.pendingTierName ? t('mem.manage.scheduled', { date: fmt.date(acct.membership.periodEnd) }) : t('mem.manage.changed'));
    },
    onError: failed
  });
  const cancel = useMutation({
    mutationFn: (refund: boolean) => api.cancelAccountMembership(refund),
    onMutate: () => setError(null),
    onSuccess: async (acct, refund) => {
      setConfirm(null);
      await refresh(acct);
      toast.show(refund ? t('mem.refund.done', { amount: fmt.money(account.data?.refund.amountMinor ?? 0) }) : t('mem.manage.canceledToast', { date: fmt.date(acct.membership?.periodEnd ?? new Date()) }));
    },
    onError: (e) => {
      setConfirm(null);
      if (definitiveFailure(e)) void qc.invalidateQueries({ queryKey: membershipKeys.account() });
      failed(e);
    }
  });
  const resume = useMutation({
    mutationFn: () => api.resumeAccountMembership(),
    onMutate: () => setError(null),
    onSuccess: async (acct) => {
      await refresh(acct);
      toast.show(t('mem.manage.resumedToast'));
    },
    onError: failed
  });

  if (account.isPending) {
    return (
      <div className={pages.page} aria-busy="true">
        <AccountCrumb>{t('account.membership')}</AccountCrumb>
        <Skeleton height={36} width={260} />
        <Skeleton height={180} />
      </div>
    );
  }
  if (account.isError) {
    return (
      <div className={pages.page}>
        <AccountCrumb>{t('account.membership')}</AccountCrumb>
        <p className={pages.error} role="alert">{t('mem.loadError')}</p>
      </div>
    );
  }
  const a = account.data;
  const m = a.membership;
  const tier = a.tier;
  const busy = change.isPending || cancel.isPending || resume.isPending;

  if (!m || !tier) {
    return (
      <div className={pages.page}>
        <AccountCrumb>{t('account.membership')}</AccountCrumb>
        <h1 className={pages.h1}>{t('mem.manage.title')}</h1>
        <section className={pages.card} data-testid="membership-none">
          <p className={styles.intro}>{t('mem.intro')}</p>
          <Link href="/membership" className={buttonClassName({ variant: 'primary' })}>
            {t('mem.title')}
          </Link>
        </section>
      </div>
    );
  }

  const every = t(everyKey(m.interval));
  const price = fmt.money(m.priceMinor, m.currency);
  const live = a.member;
  const ending = m.cancelAtPeriodEnd && live;
  const pending = a.pendingTier;
  const needsAttention = m.status === 'PAST_DUE';
  const refund = a.refund;

  return (
    <div className={pages.page}>
      <AccountCrumb>{t('account.membership')}</AccountCrumb>
      <div className={pages.head}>
        <h1 className={pages.h1}>{t('mem.manage.title')}</h1>
        <Link href="/membership" className={buttonClassName({ variant: 'secondary' })}>
          {t('mem.title')}
        </Link>
      </div>
      {error && (
        <p className={pages.error} role="alert" data-testid="membership-error">
          {error}
        </p>
      )}

      <section className={pages.card} aria-labelledby="mem-plan" data-testid="membership-plan">
        <div className={styles.tierHead}>
          <h2 id="mem-plan" className={styles.big}>
            {t('mem.manage.plan', { tier: tier.name, price, every })}
          </h2>
          <span className={`${styles.pill} ${needsAttention ? styles.pillWarn : !live ? styles.pillBad : ''}`} data-testid="membership-status">
            {t(STATUS_KEYS[m.status] ?? 'mem.status.ACTIVE')}
          </span>
        </div>
        {m.status === 'TRIALING' && m.trialEnd && !ending && (
          <p className={pages.muted}>{t('mem.manage.trialEnds', { date: fmt.date(m.trialEnd), price, every })}</p>
        )}
        {m.source === 'COMPLIMENTARY' && live && <p className={pages.muted}>{t('mem.manage.free', { date: fmt.date(m.periodEnd) })}</p>}
        {live && m.source === 'PAID' && !ending && m.status !== 'TRIALING' && <p className={pages.muted}>{t('mem.manage.renews', { date: fmt.date(m.periodEnd) })}</p>}
        {ending && <p className={styles.note} data-testid="membership-ending">{t('mem.manage.endsOn', { date: fmt.date(m.periodEnd) })}</p>}
        {!live && !needsAttention && <p className={pages.muted}>{t('mem.manage.ended', { date: fmt.date(m.endedAt ?? m.periodEnd) })}</p>}
        {needsAttention && (
          <p className={styles.alert} role="alert">
            {t('mem.manage.pastDue')} {m.nextRetryAt ? t('mem.manage.retryOn', { date: fmt.date(m.nextRetryAt) }) : ''}
          </p>
        )}
        {pending && m.pendingInterval && (
          <p className={styles.note} data-testid="membership-pending">
            {t('mem.manage.pending', { date: fmt.date(m.periodEnd), tier: pending.name, price: fmt.money(priceOf(pending, m.pendingInterval), pending.currency), every: t(everyKey(m.pendingInterval)) })}
          </p>
        )}
        <p className={pages.muted}>
          {a.card?.last4 ? t('mem.manage.card', { card: `${a.card.brand ?? ''} ${a.card.last4}`.trim() }) : t('mem.manage.noCard')}
        </p>
        {(ending || pending) && live && (
          <div className={styles.buttons}>
            <Button variant="secondary" loading={resume.isPending} disabled={busy} onClick={() => resume.mutate()} data-testid="membership-resume">
              {t('mem.manage.keep')}
            </Button>
          </div>
        )}
      </section>

      <section className={pages.card} aria-labelledby="mem-benefits">
        <h2 id="mem-benefits" className={pages.h}>{t('mem.manage.benefits')}</h2>
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
        <p className={styles.fine}>{t('mem.creditNote')}</p>
      </section>

      {live && a.tiers.length > 0 && (
        <section className={pages.card} aria-labelledby="mem-change" data-testid="membership-change">
          <h2 id="mem-change" className={pages.h}>{t('mem.manage.change')}</h2>
          <ul className={pages.list}>
            {a.tiers.flatMap((o) =>
              (['MONTHLY', 'YEARLY'] as const).map((interval) => {
                const isCurrent = o.code === tier.code && interval === m.interval && !pending;
                const isUpgrade = o.sortOrder > tier.sortOrder;
                return (
                  <li key={`${o.code}-${interval}`} className={styles.planRow} data-testid={`membership-plan-${o.code}-${interval}`}>
                    <span className={styles.planText}>
                      <strong>
                        {o.name} · {t(interval === 'YEARLY' ? 'mem.interval.YEARLY' : 'mem.interval.MONTHLY')}
                      </strong>
                      <span className={styles.fine}>
                        {fmt.money(priceOf(o, interval), o.currency)} {t(everyKey(interval))}
                        {isCurrent ? '' : ` · ${isUpgrade ? t('mem.manage.upgradeNote') : t('mem.manage.switchNote', { date: fmt.date(m.periodEnd) })}`}
                      </span>
                    </span>
                    {isCurrent ? (
                      <span className={styles.pill}>{t('mem.manage.current')}</span>
                    ) : (
                      <Button
                        size="sm"
                        variant={isUpgrade ? 'primary' : 'secondary'}
                        disabled={busy || needsAttention}
                        loading={change.isPending && change.variables?.tier.code === o.code && change.variables.interval === interval}
                        onClick={() => change.mutate({ tier: o, interval })}
                      >
                        {isUpgrade ? t('mem.manage.upgrade') : t('mem.manage.switch')}
                      </Button>
                    )}
                  </li>
                );
              })
            )}
          </ul>
        </section>
      )}

      {live && m.source === 'PAID' && (
        <section className={pages.card} aria-labelledby="mem-cancel" data-testid="membership-cancel">
          <h2 id="mem-cancel" className={pages.h}>{t('mem.manage.cancel')}</h2>
          {refund.eligible && (
            <div className={styles.note} data-testid="membership-refund">
              <strong>{t('mem.refund.title')}</strong>
              <p className={pages.muted}>{t('mem.refund.ok', { amount: fmt.money(refund.amountMinor, refund.currency), date: fmt.date(refund.until ?? new Date()) })}</p>
              {confirm === 'refund' ? (
                <div className={styles.buttons}>
                  <Button variant="dark" loading={cancel.isPending} onClick={() => cancel.mutate(true)} data-testid="membership-refund-confirm">
                    {t('mem.refund.confirm')}
                  </Button>
                  <Button variant="secondary" disabled={cancel.isPending} onClick={() => setConfirm(null)}>
                    {t('mem.manage.keep')}
                  </Button>
                </div>
              ) : (
                <Button variant="secondary" disabled={busy} onClick={() => setConfirm('refund')} data-testid="membership-refund-button">
                  {t('mem.refund.button', { amount: fmt.money(refund.amountMinor, refund.currency) })}
                </Button>
              )}
            </div>
          )}
          {!refund.eligible && refund.reason === 'BENEFIT_USED' && <p className={pages.muted} data-testid="membership-refund-used">{t('mem.refund.BENEFIT_USED')}</p>}
          {!refund.eligible && refund.reason === 'WINDOW_PASSED' && <p className={pages.muted}>{t('mem.refund.WINDOW_PASSED', { days: refund.windowDays })}</p>}
          {!ending && (
            <>
              <p className={pages.muted}>{t('mem.manage.cancelBody', { date: fmt.date(m.periodEnd) })}</p>
              {confirm === 'cancel' ? (
                <div className={styles.buttons}>
                  <Button variant="dark" loading={cancel.isPending} onClick={() => cancel.mutate(false)} data-testid="membership-cancel-confirm">
                    {t('mem.manage.cancelConfirm')}
                  </Button>
                  <Button variant="secondary" disabled={cancel.isPending} onClick={() => setConfirm(null)}>
                    {t('mem.manage.keep')}
                  </Button>
                </div>
              ) : (
                <div className={styles.buttons}>
                  <Button variant="secondary" disabled={busy} onClick={() => setConfirm('cancel')} data-testid="membership-cancel-button">
                    {t('mem.manage.cancel')}
                  </Button>
                </div>
              )}
            </>
          )}
        </section>
      )}

      <section className={pages.card} aria-labelledby="mem-usage">
        <h2 id="mem-usage" className={pages.h}>{t('mem.usage.title')}</h2>
        {a.usage.length === 0 ? (
          <p className={pages.muted}>{t('mem.usage.none')}</p>
        ) : (
          <ul className={pages.list} data-testid="membership-usage">
            {a.usage.map((u) => (
              <li key={u.benefit} className={styles.entry}>
                <span>{t('mem.usage.row', { times: u.uses, benefit: benefitName(t, fmt, u.benefit) })}</span>
                <span className={styles.amount}>{fmt.money(u.amountMinor, u.currency)}</span>
              </li>
            ))}
          </ul>
        )}
        {(uses.data?.items.length ?? 0) > 0 && (
          <>
            <h3 className={pages.h}>{t('mem.benefitsUsed.title')}</h3>
            <ul className={pages.list} data-testid="membership-uses">
              {uses.data?.items.map((u) => (
                <li key={`${u.benefit}-${u.createdAt}`} className={styles.entry}>
                  <span>{t('mem.benefitsUsed.row', { benefit: benefitName(t, fmt, u.benefit), amount: fmt.money(u.amountMinor, u.currency) })}</span>
                  <span className={styles.date}>{fmt.date(u.createdAt)}</span>
                </li>
              ))}
            </ul>
          </>
        )}
      </section>

      <section className={pages.card} aria-labelledby="mem-charges">
        <h2 id="mem-charges" className={pages.h}>{t('mem.charges.title')}</h2>
        {a.charges.length === 0 ? (
          <p className={pages.muted}>{t('mem.charges.none')}</p>
        ) : (
          <ul className={pages.list} data-testid="membership-charges">
            {a.charges.map((c) => (
              <li key={`${c.kind}-${c.createdAt}`} className={styles.entry}>
                <span>
                  {t(c.kind === 'JOIN' ? 'mem.charge.JOIN' : c.kind === 'RENEWAL' ? 'mem.charge.RENEWAL' : 'mem.charge.UPGRADE')}
                  {c.status === 'FAILED' ? ` · ${t('mem.charge.FAILED')}` : ''}
                  {c.refundedMinor > 0 ? ` · ${t('mem.charge.refunded', { amount: fmt.money(c.refundedMinor, c.currency) })}` : ''}
                </span>
                <span className={styles.amount}>{fmt.money(c.amountMinor, c.currency)}</span>
                <span className={styles.date}>{fmt.date(c.createdAt)}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
