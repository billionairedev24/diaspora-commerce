'use client';

import { creditKeys, creditQueries, isApiError } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, Skeleton, TextField, useToast } from '@afrotransact/ui';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type FormEvent, useState } from 'react';

import { AccountCrumb } from '@/components/account/AccountCrumb';
import pages from '@/components/account/Pages.module.css';
import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './Credit.module.css';

const SOURCE_KEYS: Record<string, MessageKey> = {
  REFERRAL: 'credit.src.REFERRAL',
  GIFT_CARD: 'credit.src.GIFT_CARD',
  MEMBERSHIP: 'credit.src.MEMBERSHIP',
  REFUND: 'credit.src.REFUND',
  STAFF: 'credit.src.STAFF',
  PROMO: 'credit.src.PROMO',
  ORDER: 'credit.src.ORDER'
};

/**
 * AfroTransact credit (S320): balance, history and the redeem box (S321). Credit buys products and services only, so
 * there is no withdraw, payout, transfer or "refund to card" anywhere on this page.
 */
export function CreditView() {
  const t = useT();
  const fmt = useFormatters();
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const account = useInfiniteQuery(creditQueries.account(api));
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);

  const redeem = useMutation({
    mutationFn: (value: string) => api.redeemGiftCard(value),
    onSuccess: async (r) => {
      setCode('');
      setError(null);
      toast.show(t('credit.redeem.done', { amount: fmt.money(r.redeemedMinor, r.currency) }));
      await qc.invalidateQueries({ queryKey: creditKeys.all });
    },
    onError: (e) => {
      if (!isApiError(e)) return setError(t('auth.genericError'));
      setError(
        e.status === 429
          ? t('credit.redeem.tooMany')
          : e.code === 'GIFT_CARD_REDEEMED'
            ? t('credit.redeem.used')
            : e.status === 404 || e.status === 422
              ? t('credit.redeem.invalid')
              : t('auth.genericError')
      );
    }
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (code.trim() && !redeem.isPending) redeem.mutate(code.trim());
  };

  const first = account.data?.pages[0];
  const entries = account.data?.pages.flatMap((p) => p.entries) ?? [];
  const currency = first?.currency ?? 'USD';

  return (
    <div className={pages.page}>
      <AccountCrumb>{t('account.credit')}</AccountCrumb>
      <div className={pages.head}>
        <h1 className={pages.h1}>{t('credit.title')}</h1>
        <Link href="/gift-cards" className={buttonClassName({ variant: 'secondary' })}>
          {t('credit.buy')}
        </Link>
      </div>

      <section className={`${pages.card} ${styles.balanceCard}`} aria-label={t('credit.balance')}>
        <div>
          <p className={styles.balanceLabel}>{t('credit.balance')}</p>
          {account.isPending ? <Skeleton height={40} width={160} /> : <p className={styles.balance} data-testid="credit-balance">{fmt.money(first?.balanceMinor ?? 0, currency)}</p>}
        </div>
        <p className={styles.note}>{t('credit.intro')}</p>
      </section>

      <section className={pages.card} aria-labelledby="credit-redeem">
        <h2 id="credit-redeem" className={pages.h}>{t('credit.redeem.title')}</h2>
        <form className={styles.redeemRow} onSubmit={submit} noValidate>
          <TextField
            label={t('credit.redeem.label')}
            value={code}
            onChange={(e) => setCode(e.target.value)}
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            maxLength={40}
            className={styles.code}
            help={t('credit.redeem.hint')}
            error={error}
            data-testid="credit-redeem-code"
          />
          <Button type="submit" loading={redeem.isPending} disabled={!code.trim()} data-testid="credit-redeem-button">
            {t('credit.redeem.button')}
          </Button>
        </form>
      </section>

      <section className={pages.card} aria-labelledby="credit-history">
        <h2 id="credit-history" className={pages.h}>{t('credit.history')}</h2>
        {account.isPending && <Skeleton height={48} />}
        {account.isError && <p className={pages.error} role="alert">{t('acct.loadError')}</p>}
        {account.isSuccess && entries.length === 0 && <p className={pages.muted}>{t('credit.none')}</p>}
        <ul className={pages.list} data-testid="credit-history">
          {entries.map((e) => (
            <li key={e.id} className={styles.entry} data-testid="credit-entry">
              <span className={styles.entryTitle}>{t(SOURCE_KEYS[e.source] ?? 'credit.src.STAFF')}</span>
              <span className={`${styles.entryAmount} ${e.amountMinor > 0 ? styles.plus : ''}`}>
                {e.amountMinor > 0 ? '+' : '−'}
                {fmt.money(Math.abs(e.amountMinor), e.currency)}
              </span>
              <span className={styles.entryDate}>{fmt.date(e.createdAt)}</span>
              <span className={styles.entryAfter}>{t('credit.after', { amount: fmt.money(e.balanceAfterMinor, e.currency) })}</span>
            </li>
          ))}
        </ul>
        {account.hasNextPage && (
          <Button variant="secondary" className={styles.more} onClick={() => void account.fetchNextPage()} loading={account.isFetchingNextPage}>
            {t('credit.loadMore')}
          </Button>
        )}
      </section>
    </div>
  );
}
