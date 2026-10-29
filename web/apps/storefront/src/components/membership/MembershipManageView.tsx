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
import {
  Button,
  buttonClassName,
  DataTable,
  DefinitionList,
  EmptyState,
  FlatPage,
  Message,
  Row,
  RowList,
  Section,
  Skeleton,
  StatusText,
  type StatusTone,
  textLinkClassName,
  TextLinkButton,
  useToast
} from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { AccountHeader } from '@/components/account/AccountHeader';
import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import { BenefitList } from './BenefitList';
import styles from './Membership.module.css';
import { benefitName, everyKey, membershipError } from './memberText';

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
 * Manage the membership (S323): status, plan, next billing date and payment method, what it gives and what has been
 * used, change plan (upgrade now or switch at the end of the period), and cancel in one path with the refund rule shown
 * (benefits run to the end of the period, or a full refund while the rule allows it).
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

  const header = <AccountHeader title={t('mem.manage.title')} crumb={t('account.membership')} actions={<Link href="/membership" className={textLinkClassName}>{t('mem.title')}</Link>} />;

  if (account.isPending) {
    return (
      <FlatPage>
        {header}
        <Skeleton height={36} width={260} />
        <Skeleton height={180} />
      </FlatPage>
    );
  }
  if (account.isError) {
    return (
      <FlatPage>
        {header}
        <Message tone="error">
          {t('mem.loadError')} <TextLinkButton onClick={() => void account.refetch()}>{t('acct.retry')}</TextLinkButton>
        </Message>
      </FlatPage>
    );
  }
  const a = account.data;
  const m = a.membership;
  const tier = a.tier;
  const busy = change.isPending || cancel.isPending || resume.isPending;

  if (!m || !tier) {
    return (
      <FlatPage>
        <AccountHeader title={t('mem.manage.title')} crumb={t('account.membership')} />
        <Section title={t('mem.manage.noneTitle')} testId="membership-none">
          <EmptyState
            action={
              <div>
                <Link href="/membership" className={buttonClassName({ variant: 'primary' })}>
                  {t('mem.title')}
                </Link>
              </div>
            }
          >
            {t('mem.intro')}
          </EmptyState>
        </Section>
      </FlatPage>
    );
  }

  const every = t(everyKey(m.interval));
  const price = fmt.money(m.priceMinor, m.currency);
  const live = a.member;
  const ending = m.cancelAtPeriodEnd && live;
  const pending = a.pendingTier;
  const needsAttention = m.status === 'PAST_DUE';
  const refund = a.refund;
  const statusTone: StatusTone = needsAttention ? 'warning' : !live ? 'negative' : m.status === 'TRIALING' ? 'info' : 'positive';

  const dateRow =
    m.status === 'TRIALING' && m.trialEnd && !ending
      ? { term: t('mem.manage.trialEndsLabel'), value: fmt.date(m.trialEnd) }
      : ending || !live
        ? { term: t('mem.manage.endsLabel'), value: fmt.date(m.endedAt ?? m.periodEnd) }
        : m.source === 'PAID'
          ? { term: t('mem.manage.nextBilling'), value: fmt.date(m.periodEnd) }
          : { term: t('mem.manage.endsLabel'), value: fmt.date(m.periodEnd) };
  const card = a.card?.last4 ? `${a.card.brand ?? ''} ${a.card.last4}`.trim() : null;

  return (
    <FlatPage>
      {header}
      {error && <Message tone="error" testId="membership-error">{error}</Message>}

      <Section title={t('mem.manage.plan', { tier: tier.name, price, every })} id="mem-plan" testId="membership-plan">
        <DefinitionList
          items={[
            { term: t('mem.manage.statusLabel'), value: <StatusText tone={statusTone} testId="membership-status">{t(STATUS_KEYS[m.status] ?? 'mem.status.ACTIVE')}</StatusText> },
            { term: t('mem.manage.planLabel'), value: t('mem.manage.planValue', { tier: tier.name, interval: t(m.interval === 'YEARLY' ? 'mem.interval.YEARLY' : 'mem.interval.MONTHLY') }) },
            { term: t('mem.manage.priceLabel'), value: `${price} ${every}` },
            dateRow,
            {
              term: t('mem.manage.paymentLabel'),
              value: (
                <>
                  {card ?? t('mem.manage.noCardValue')}{' '}
                  <Link href="/account/payments" className={textLinkClassName}>
                    {t('mem.manage.updateCard')}
                  </Link>
                </>
              )
            }
          ]}
        />
        {m.status === 'TRIALING' && m.trialEnd && !ending && <p className={styles.rule}>{t('mem.manage.trialEnds', { date: fmt.date(m.trialEnd), price, every })}</p>}
        {m.source === 'COMPLIMENTARY' && live && <p className={styles.rule}>{t('mem.manage.free', { date: fmt.date(m.periodEnd) })}</p>}
        {live && m.source === 'PAID' && !ending && m.status !== 'TRIALING' && <p className={styles.rule}>{t('mem.manage.renews', { date: fmt.date(m.periodEnd) })}</p>}
        {ending && <p className={styles.notice} data-testid="membership-ending">{t('mem.manage.endsOn', { date: fmt.date(m.periodEnd) })}</p>}
        {!live && !needsAttention && <p className={styles.rule}>{t('mem.manage.ended', { date: fmt.date(m.endedAt ?? m.periodEnd) })}</p>}
        {needsAttention && (
          <p className={styles.alert} role="alert">
            {t('mem.manage.pastDue')} {m.nextRetryAt ? t('mem.manage.retryOn', { date: fmt.date(m.nextRetryAt) }) : ''}
          </p>
        )}
        {pending && m.pendingInterval && (
          <p className={styles.notice} data-testid="membership-pending">
            {t('mem.manage.pending', { date: fmt.date(m.periodEnd), tier: pending.name, price: fmt.money(priceOf(pending, m.pendingInterval), pending.currency), every: t(everyKey(m.pendingInterval)) })}
          </p>
        )}
        {!card && <p className={styles.rule}>{t('mem.manage.noCard')}</p>}
        {(ending || pending) && live && (
          <div className={styles.buttons}>
            <Button loading={resume.isPending} disabled={busy} onClick={() => resume.mutate()} data-testid="membership-resume">
              {t('mem.manage.keep')}
            </Button>
          </div>
        )}
      </Section>

      <Section title={t('mem.manage.benefits')} id="mem-benefits">
        <BenefitList benefits={tier.benefits} currency={tier.currency} t={t} fmt={fmt} />
        <p className={styles.fine}>{t('mem.creditNote')}</p>
      </Section>

      <Section title={t('mem.usage.title')} id="mem-usage">
        {a.usage.length === 0 ? (
          <EmptyState>{t('mem.usage.none')}</EmptyState>
        ) : (
          <DataTable
            caption={t('mem.tableUsage')}
            testId="membership-usage"
            rows={a.usage}
            rowKey={(u) => u.benefit}
            columns={[
              { key: 'b', header: t('mem.col.benefit'), label: t('mem.col.benefit'), cell: (u) => benefitName(t, fmt, u.benefit) },
              { key: 'n', header: t('mem.col.times'), label: t('mem.col.times'), numeric: true, cell: (u) => u.uses },
              { key: 'a', header: t('mem.col.saved'), label: t('mem.col.saved'), numeric: true, cell: (u) => fmt.money(u.amountMinor, u.currency) }
            ]}
          />
        )}
        {(uses.data?.items.length ?? 0) > 0 && (
          <RowList testId="membership-uses" label={t('mem.benefitsUsed.title')}>
            {uses.data?.items.map((u) => (
              <Row
                key={`${u.benefit}-${u.createdAt}`}
                title={t('mem.benefitsUsed.row', { benefit: benefitName(t, fmt, u.benefit), amount: fmt.money(u.amountMinor, u.currency) })}
                detail={fmt.date(u.createdAt)}
              />
            ))}
          </RowList>
        )}
      </Section>

      {live && a.tiers.length > 0 && (
        <Section title={t('mem.manage.change')} id="mem-change" testId="membership-change">
          <RowList>
            {a.tiers.flatMap((o) =>
              (['MONTHLY', 'YEARLY'] as const).map((interval) => {
                const isCurrent = o.code === tier.code && interval === m.interval && !pending;
                const isUpgrade = o.sortOrder > tier.sortOrder;
                return (
                  <Row
                    key={`${o.code}-${interval}`}
                    testId={`membership-plan-${o.code}-${interval}`}
                    title={`${o.name} · ${t(interval === 'YEARLY' ? 'mem.interval.YEARLY' : 'mem.interval.MONTHLY')}`}
                    detail={`${fmt.money(priceOf(o, interval), o.currency)} ${t(everyKey(interval))}${isCurrent ? '' : ` · ${isUpgrade ? t('mem.manage.upgradeNote') : t('mem.manage.switchNote', { date: fmt.date(m.periodEnd) })}`}`}
                    actions={
                      isCurrent ? (
                        <StatusText tone="positive">{t('mem.manage.current')}</StatusText>
                      ) : (
                        <TextLinkButton
                          disabled={busy || needsAttention}
                          onClick={() => change.mutate({ tier: o, interval })}
                          aria-busy={change.isPending && change.variables?.tier.code === o.code && change.variables.interval === interval ? true : undefined}
                        >
                          {isUpgrade ? t('mem.manage.upgrade') : t('mem.manage.switch')}
                        </TextLinkButton>
                      )
                    }
                  />
                );
              })
            )}
          </RowList>
        </Section>
      )}

      {live && m.source === 'PAID' && (
        <Section title={t('mem.manage.cancel')} id="mem-cancel" testId="membership-cancel">
          <div className={styles.stack}>
            {refund.eligible && (
              <div data-testid="membership-refund" className={styles.stack}>
                <p className={styles.rule}>
                  <strong>{t('mem.refund.title')}</strong> {t('mem.refund.ok', { amount: fmt.money(refund.amountMinor, refund.currency), date: fmt.date(refund.until ?? new Date()) })}
                </p>
                {confirm === 'refund' ? (
                  <div className={styles.buttons}>
                    <Button variant="dark" loading={cancel.isPending} onClick={() => cancel.mutate(true)} data-testid="membership-refund-confirm">
                      {t('mem.refund.confirm')}
                    </Button>
                    <TextLinkButton disabled={cancel.isPending} onClick={() => setConfirm(null)}>
                      {t('mem.manage.keep')}
                    </TextLinkButton>
                  </div>
                ) : (
                  <div className={styles.buttons}>
                    <TextLinkButton disabled={busy} onClick={() => setConfirm('refund')} data-testid="membership-refund-button">
                      {t('mem.refund.button', { amount: fmt.money(refund.amountMinor, refund.currency) })}
                    </TextLinkButton>
                  </div>
                )}
              </div>
            )}
            {!refund.eligible && refund.reason === 'BENEFIT_USED' && <p className={styles.rule} data-testid="membership-refund-used">{t('mem.refund.BENEFIT_USED')}</p>}
            {!refund.eligible && refund.reason === 'WINDOW_PASSED' && <p className={styles.rule}>{t('mem.refund.WINDOW_PASSED', { days: refund.windowDays })}</p>}
            {!ending && (
              <>
                <p className={styles.rule}>{t('mem.manage.cancelBody', { date: fmt.date(m.periodEnd) })}</p>
                {confirm === 'cancel' ? (
                  <div className={styles.buttons}>
                    <Button variant="dark" loading={cancel.isPending} onClick={() => cancel.mutate(false)} data-testid="membership-cancel-confirm">
                      {t('mem.manage.cancelConfirm')}
                    </Button>
                    <TextLinkButton disabled={cancel.isPending} onClick={() => setConfirm(null)}>
                      {t('mem.manage.keep')}
                    </TextLinkButton>
                  </div>
                ) : (
                  <div className={styles.buttons}>
                    <TextLinkButton danger disabled={busy} onClick={() => setConfirm('cancel')} data-testid="membership-cancel-button">
                      {t('mem.manage.cancel')}
                    </TextLinkButton>
                  </div>
                )}
              </>
            )}
            <p className={styles.fine}>
              <Link href="/legal/membership-terms">{t('mem.termsLink')}</Link>
            </p>
          </div>
        </Section>
      )}

      <Section title={t('mem.charges.title')} id="mem-charges">
        {a.charges.length === 0 ? (
          <EmptyState>{t('mem.charges.none')}</EmptyState>
        ) : (
          <DataTable
            caption={t('mem.tableCharges')}
            testId="membership-charges"
            rows={a.charges}
            rowKey={(c) => `${c.kind}-${c.createdAt}`}
            columns={[
              { key: 'd', header: t('mem.col.date'), label: t('mem.col.date'), cell: (c) => fmt.date(c.createdAt) },
              {
                key: 'w',
                header: t('mem.col.description'),
                label: t('mem.col.description'),
                cell: (c) => (
                  <>
                    {t(c.kind === 'JOIN' ? 'mem.charge.JOIN' : c.kind === 'RENEWAL' ? 'mem.charge.RENEWAL' : 'mem.charge.UPGRADE')}
                    {c.status === 'FAILED' ? ` · ${t('mem.charge.FAILED')}` : ''}
                    {c.refundedMinor > 0 ? ` · ${t('mem.charge.refunded', { amount: fmt.money(c.refundedMinor, c.currency) })}` : ''}
                  </>
                )
              },
              { key: 'a', header: t('mem.col.amount'), label: t('mem.col.amount'), numeric: true, cell: (c) => fmt.money(c.amountMinor, c.currency) }
            ]}
          />
        )}
      </Section>
    </FlatPage>
  );
}
