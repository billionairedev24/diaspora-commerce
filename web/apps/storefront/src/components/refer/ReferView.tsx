'use client';

import { type ReferralFriend, referralQueries, type ReferralRewardTerms } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, DataTable, DefinitionList, EmptyState, FlatPage, Message, Row, RowList, Section, Skeleton, StatusText, type StatusTone, textLinkClassName, TextLinkButton, useToast } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { AccountHeader } from '@/components/account/AccountHeader';
import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './Refer.module.css';

const STATUS_KEYS: Record<ReferralFriend['status'], MessageKey> = {
  PENDING: 'refer.status.PENDING',
  WAITING: 'refer.status.WAITING',
  REWARDED: 'refer.status.REWARDED',
  EXPIRED: 'refer.status.EXPIRED',
  CLOSED: 'refer.status.CLOSED'
};

const STATUS_TONES: Record<ReferralFriend['status'], StatusTone> = { PENDING: 'neutral', WAITING: 'info', REWARDED: 'positive', EXPIRED: 'neutral', CLOSED: 'neutral' };

/**
 * Account, Refer a friend (S322): the customer's code and share link with copy and share actions, the offer, the
 * friends who signed up (by name) with what each pays, and the earnings. AfroTransact pays; nothing here claims or moves
 * credit, which only buys products and services.
 */
export function ReferView() {
  const t = useT();
  const fmt = useFormatters();
  const toast = useToast();
  const page = useQuery(referralQueries.account(browserApi()));
  const [canShare, setCanShare] = useState(false);
  useEffect(() => setCanShare(typeof navigator !== 'undefined' && typeof navigator.share === 'function'), []);

  const data = page.data;
  const currency = data?.program.currency ?? 'USD';
  const reward = (r: ReferralRewardTerms): string =>
    r.type === 'MEMBERSHIP_DAYS' ? t('refer.reward.days', { days: r.membershipDays }) : t('refer.reward.credit', { amount: fmt.money(r.creditMinor, currency) });

  const copy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      toast.show(t('refer.copied'));
    } catch {
      toast.show(t('refer.copyFailed'));
    }
  };

  const message = data ? t('refer.shareText', { theirs: reward(data.program.referredReward), link: data.shareUrl }) : '';
  const subject = t('refer.shareSubject');

  const friendReward = (f: ReferralFriend): string => {
    if (f.status === 'CLOSED' || f.status === 'EXPIRED') return '';
    const terms = f.reward?.terms ?? data?.program.referrerReward;
    if (!terms) return '';
    if (f.reward?.granted) return t('refer.friend.granted', { reward: reward(terms) });
    return f.eligibleAt ? t('refer.friend.pendingOn', { reward: reward(terms), date: fmt.date(f.eligibleAt) }) : t('refer.friend.pending', { reward: reward(terms) });
  };

  return (
    <FlatPage>
      <AccountHeader
        title={t('refer.title')}
        lead={
          data
            ? t('refer.intro', {
                min: fmt.money(data.program.minOrderMinor, currency),
                mine: reward(data.program.referrerReward),
                theirs: reward(data.program.referredReward)
              })
            : undefined
        }
      />

      {page.isPending && <Skeleton height={160} />}
      {page.isError && (
        <Message tone="error">
          {t('refer.loadError')} <TextLinkButton onClick={() => void page.refetch()}>{t('acct.retry')}</TextLinkButton>
        </Message>
      )}

      {data && (
        <>
          {!data.program.enabled && <Message tone="muted" testId="refer-paused">{t('refer.off')}</Message>}

          <Section title={t('refer.code')} id="refer-code-section">
            <RowList>
              <Row
                title={<span className={styles.code} data-testid="refer-code">{data.displayCode}</span>}
                actions={
                  <TextLinkButton onClick={() => void copy(data.displayCode)} data-testid="refer-copy-code">
                    {t('refer.copyCode')}
                  </TextLinkButton>
                }
              />
              <Row
                title={t('refer.link')}
                detail={<span className={styles.link} data-testid="refer-link">{data.shareUrl}</span>}
                actions={
                  <TextLinkButton onClick={() => void copy(data.shareUrl)} data-testid="refer-copy-link">
                    {t('refer.copyLink')}
                  </TextLinkButton>
                }
              />
            </RowList>
          </Section>

          <Section title={t('refer.share')} id="refer-share" lead={t('refer.shareLead')}>
            <div className={styles.share} role="group" aria-label={t('refer.share')}>
              {canShare && (
                <Button onClick={() => void navigator.share({ title: subject, text: message, url: data.shareUrl }).catch(() => undefined)} data-testid="refer-native-share">
                  {t('refer.share')}
                </Button>
              )}
              <a className={textLinkClassName} href={`https://wa.me/?text=${encodeURIComponent(message)}`} target="_blank" rel="noopener noreferrer" data-testid="refer-whatsapp">
                {t('refer.shareWhatsApp')}
              </a>
              <a className={textLinkClassName} href={`mailto:?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(message)}`} data-testid="refer-email">
                {t('refer.shareEmail')}
              </a>
              <a className={textLinkClassName} href={`sms:?&body=${encodeURIComponent(message)}`} data-testid="refer-sms">
                {t('refer.shareSms')}
              </a>
            </div>
          </Section>

          <Section title={t('refer.how.title')} id="refer-how">
            <ol className={styles.steps}>
              <li>{t('refer.how.1')}</li>
              <li>{t('refer.how.2', { min: fmt.money(data.program.minOrderMinor, currency) })}</li>
              <li>{t('refer.how.3', { days: data.program.returnWindowDays })}</li>
            </ol>
            <p className={styles.fine}>{t('refer.terms')}</p>
          </Section>

          {data.asFriend && (
            <Section title={t('refer.asFriend.title')} id="refer-welcome" testId="refer-as-friend">
              <p className={styles.para}>
                {data.asFriend.reward?.granted
                  ? t('refer.asFriend.granted', { reward: reward(data.asFriend.reward.terms) })
                  : t('refer.asFriend.body', { min: fmt.money(data.program.minOrderMinor, currency), reward: reward(data.asFriend.reward?.terms ?? data.program.referredReward) })}
              </p>
            </Section>
          )}

          <Section title={t('refer.earnings.title')} id="refer-earnings">
            <DefinitionList
              items={[
                { term: t('refer.earnings.invited'), value: data.earnings.invited, testId: 'refer-invited' },
                { term: t('refer.earnings.rewarded'), value: data.earnings.rewarded, testId: 'refer-rewarded' },
                {
                  term: t('refer.earnings.earned'),
                  value: `${fmt.money(data.earnings.earnedMinor, currency)}${data.earnings.membershipDays > 0 ? ` + ${t('refer.reward.days', { days: data.earnings.membershipDays })}` : ''}`,
                  testId: 'refer-earned'
                },
                { term: t('refer.earnings.pending'), value: fmt.money(data.earnings.pendingMinor, currency), testId: 'refer-pending' }
              ]}
            />
          </Section>

          <Section title={t('refer.friends.title')} id="refer-friends-section">
            {data.friends.length === 0 ? (
              <EmptyState>{t('refer.friends.none')}</EmptyState>
            ) : (
              <DataTable
                caption={t('refer.table')}
                testId="refer-friends"
                rowTestId="refer-friend"
                rows={data.friends}
                rowKey={(f) => f.id}
                columns={[
                  { key: 'n', header: t('refer.col.friend'), label: t('refer.col.friend'), cell: (f) => <strong>{f.friendName}</strong> },
                  { key: 's', header: t('refer.col.status'), label: t('refer.col.status'), cell: (f) => <StatusText tone={STATUS_TONES[f.status]}>{t(STATUS_KEYS[f.status])}</StatusText> },
                  { key: 'j', header: t('refer.col.joined'), label: t('refer.col.joined'), cell: (f) => fmt.date(f.signedUpAt) },
                  { key: 'r', header: t('refer.col.reward'), label: t('refer.col.reward'), cell: (f) => friendReward(f) }
                ]}
              />
            )}
          </Section>
        </>
      )}
    </FlatPage>
  );
}
