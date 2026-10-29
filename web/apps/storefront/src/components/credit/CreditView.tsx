'use client';

import { type CreditEntry, creditKeys, creditQueries, isApiError } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import {
  BigFigure,
  Button,
  buttonClassName,
  DataTable,
  EmptyState,
  fieldStyles,
  FilterBar,
  FilterSelect,
  FlatPage,
  InlineFormRow,
  Message,
  Pager,
  Section,
  Skeleton,
  TextLinkButton,
  textLinkClassName,
  useToast
} from '@afrotransact/ui';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type ChangeEvent, type FormEvent, useState } from 'react';

import { AccountHeader } from '@/components/account/AccountHeader';
import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import { formatGiftCode, giftCodeComplete } from './giftCode';

const SOURCE_KEYS: Record<string, MessageKey> = {
  REFERRAL: 'credit.src.REFERRAL',
  GIFT_CARD: 'credit.src.GIFT_CARD',
  MEMBERSHIP: 'credit.src.MEMBERSHIP',
  REFUND: 'credit.src.REFUND',
  STAFF: 'credit.src.STAFF',
  PROMO: 'credit.src.PROMO',
  ORDER: 'credit.src.ORDER'
};

const PAGE_SIZE = 10;
type TypeFilter = 'all' | 'added' | 'used';

/**
 * Gift cards & credit (S320, S321): the balance as a plain figure, redeeming a card in one inline row, buying a card,
 * and the activity as a table with filters and pages. Credit buys products and services only, so there is no withdraw,
 * payout, transfer or "refund to card" anywhere on this page.
 */
export function CreditView() {
  const t = useT();
  const fmt = useFormatters();
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const account = useInfiniteQuery(creditQueries.account(api));
  const [code, setCode] = useState('');
  const [message, setMessage] = useState<{ tone: 'error' | 'success'; text: string } | null>(null);
  const [type, setType] = useState<TypeFilter>('all');
  const [source, setSource] = useState('all');
  const [page, setPage] = useState(0);

  const redeem = useMutation({
    mutationFn: (value: string) => api.redeemGiftCard(value),
    onSuccess: async (r) => {
      setCode('');
      const done = t('credit.redeem.done', { amount: fmt.money(r.redeemedMinor, r.currency) });
      setMessage({ tone: 'success', text: done });
      toast.show(done);
      await qc.invalidateQueries({ queryKey: creditKeys.all });
    },
    onError: (e) => {
      const text = !isApiError(e)
        ? t('auth.genericError')
        : e.status === 429
          ? t('credit.redeem.tooMany')
          : e.code === 'GIFT_CARD_REDEEMED'
            ? t('credit.redeem.used')
            : e.status === 404 || e.status === 422
              ? t('credit.redeem.invalid')
              : t('auth.genericError');
      setMessage({ tone: 'error', text });
    }
  });
  const complete = giftCodeComplete(code);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (redeem.isPending) return;
    if (!complete) return setMessage({ tone: 'error', text: t('credit.redeem.invalid') });
    redeem.mutate(code);
  };
  const onCode = (e: ChangeEvent<HTMLInputElement>) => {
    setCode(formatGiftCode(e.target.value));
    setMessage(null);
  };

  const first = account.data?.pages[0];
  const entries = account.data?.pages.flatMap((p) => p.entries) ?? [];
  const currency = first?.currency ?? 'USD';

  const matches = (e: CreditEntry) => (type === 'all' || (type === 'added' ? e.amountMinor > 0 : e.amountMinor < 0)) && (source === 'all' || e.source === source);
  const filtered = entries.filter(matches);
  const start = page * PAGE_SIZE;
  const shown = filtered.slice(start, start + PAGE_SIZE);
  const moreLoaded = start + PAGE_SIZE < filtered.length;
  const filterChanged = () => setPage(0);

  const next = async () => {
    if (moreLoaded) return setPage((p) => p + 1);
    if (!account.hasNextPage) return;
    const res = await account.fetchNextPage();
    const all = res.data?.pages.flatMap((p) => p.entries) ?? [];
    if (all.filter(matches).length > start + PAGE_SIZE) setPage((p) => p + 1);
  };

  return (
    <FlatPage width="wide">
      <AccountHeader title={t('credit.title')} />

      <Section title={t('credit.balance')} id="credit-balance-section">
        {account.isPending ? (
          <Skeleton height={44} width={160} />
        ) : (
          <BigFigure testId="credit-balance">{fmt.money(first?.balanceMinor ?? 0, currency)}</BigFigure>
        )}
        <Message tone="muted">{t('credit.intro')}</Message>
      </Section>

      <Section title={t('credit.redeem.title')} id="credit-redeem">
        <InlineFormRow
          fieldId="credit-redeem-field"
          label={t('credit.redeem.label')}
          onSubmit={submit}
          help={t('credit.redeem.hint')}
          message={message}
          button={
            <Button type="submit" variant={complete ? 'primary' : 'neutral'} loading={redeem.isPending} disabled={!complete} data-testid="credit-redeem-button">
              {t('credit.redeem.button')}
            </Button>
          }
        >
          {(field) => (
            <input
              {...field}
              className={fieldStyles.control}
              value={code}
              onChange={onCode}
              placeholder={t('credit.redeem.placeholder')}
              autoComplete="off"
              autoCapitalize="characters"
              spellCheck={false}
              inputMode="text"
              maxLength={24}
              data-testid="credit-redeem-code"
            />
          )}
        </InlineFormRow>
      </Section>

      <Section title={t('credit.buy')} id="credit-buy">
        <Message tone="muted">{t('credit.buyText')}</Message>
        <div>
          <Link href="/gift-cards" className={buttonClassName({ variant: 'secondary' })}>
            {t('credit.buy')}
          </Link>
        </div>
      </Section>

      <Section title={t('credit.history')} id="credit-history">
        {account.isPending && <Skeleton height={120} />}
        {account.isError && (
          <Message tone="error">
            {t('acct.loadError')}{' '}
            <TextLinkButton onClick={() => void account.refetch()}>{t('acct.retry')}</TextLinkButton>
          </Message>
        )}
        {account.isSuccess && entries.length === 0 && (
          <EmptyState testId="credit-empty" action={<Link href="/account/refer" className={textLinkClassName}>{t('account.refer')}</Link>}>
            {t('credit.none')}
          </EmptyState>
        )}
        {entries.length > 0 && (
          <>
            <Filters
              type={type}
              source={source}
              onType={(v) => {
                setType(v);
                filterChanged();
              }}
              onSource={(v) => {
                setSource(v);
                filterChanged();
              }}
            />
            {filtered.length === 0 ? (
              <Message tone="muted">{t('credit.noMatch')}</Message>
            ) : (
              <DataTable
                caption={t('credit.table')}
                testId="credit-history"
                rowTestId="credit-entry"
                rows={shown}
                rowKey={(e) => e.id}
                columns={[
                  { key: 'date', header: t('credit.col.date'), label: t('credit.col.date'), cell: (e) => fmt.date(e.createdAt) },
                  { key: 'what', header: t('credit.col.description'), label: t('credit.col.description'), cell: (e) => t(SOURCE_KEYS[e.source] ?? 'credit.src.STAFF') },
                  {
                    key: 'amount',
                    header: t('credit.col.amount'),
                    label: t('credit.col.amount'),
                    numeric: true,
                    cell: (e) => (
                      <strong>
                        {e.amountMinor > 0 ? '+' : '−'}
                        {fmt.money(Math.abs(e.amountMinor), e.currency)}
                      </strong>
                    )
                  },
                  { key: 'balance', header: t('credit.col.balance'), label: t('credit.col.balance'), numeric: true, cell: (e) => fmt.money(e.balanceAfterMinor, e.currency) }
                ]}
              />
            )}
            {(page > 0 || moreLoaded || account.hasNextPage) && (
              <Pager label={t('credit.pages')} info={filtered.length > 0 ? t('credit.showing', { from: start + 1, to: start + shown.length }) : undefined}>
                <TextLinkButton disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
                  {t('credit.prev')}
                </TextLinkButton>
                <TextLinkButton disabled={!moreLoaded && !account.hasNextPage} onClick={() => void next()}>
                  {account.isFetchingNextPage ? t('common.loading') : t('credit.next')}
                </TextLinkButton>
              </Pager>
            )}
          </>
        )}
      </Section>
    </FlatPage>
  );
}

function Filters({ type, source, onType, onSource }: { type: TypeFilter; source: string; onType: (v: TypeFilter) => void; onSource: (v: string) => void }) {
  const t = useT();
  return (
    <FilterBar label={t('credit.filter.type')}>
      <FilterSelect
        label={t('credit.filter.type')}
        value={type}
        onChange={(v) => onType(v as TypeFilter)}
        testId="credit-filter-type"
        options={[
          { value: 'all', label: t('credit.filter.all') },
          { value: 'added', label: t('credit.filter.added') },
          { value: 'used', label: t('credit.filter.used') }
        ]}
      />
      <FilterSelect
        label={t('credit.filter.source')}
        value={source}
        onChange={onSource}
        testId="credit-filter-source"
        options={[{ value: 'all', label: t('credit.filter.anySource') }, ...Object.entries(SOURCE_KEYS).map(([value, key]) => ({ value, label: t(key) }))]}
      />
    </FilterBar>
  );
}
