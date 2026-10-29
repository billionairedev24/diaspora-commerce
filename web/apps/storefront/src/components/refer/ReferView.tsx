'use client';

import { type ReferralFriend, referralQueries,type ReferralRewardTerms } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, Skeleton, useToast } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { AccountCrumb } from '@/components/account/AccountCrumb';
import pages from '@/components/account/Pages.module.css';
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

/**
 * Account, Refer a friend (S322): the customer's code and share link with copy and share buttons, the offer, the
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

  return (
    <div className={pages.page}>
      <AccountCrumb>{t('account.refer')}</AccountCrumb>
      <div className={pages.head}>
        <h1 className={pages.h1}>{t('refer.title')}</h1>
      </div>

      {page.isPending && <Skeleton height={160} />}
      {page.isError && <p className={pages.error} role="alert">{t('refer.loadError')}</p>}

      {data && (
        <>
          <section className={pages.card} aria-labelledby="refer-code">
            <p className={styles.intro}>
              {t('refer.intro', {
                min: fmt.money(data.program.minOrderMinor, currency),
                mine: reward(data.program.referrerReward),
                theirs: reward(data.program.referredReward)
              })}
            </p>
            {!data.program.enabled && <p className={pages.muted} role="status" data-testid="refer-paused">{t('refer.off')}</p>}

            <h2 id="refer-code" className={styles.label}>{t('refer.code')}</h2>
            <div className={styles.codeRow}>
              <strong className={styles.code} data-testid="refer-code">{data.displayCode}</strong>
              <Button variant="secondary" onClick={() => void copy(data.displayCode)} data-testid="refer-copy-code">
                {t('refer.copyCode')}
              </Button>
            </div>

            <h2 className={styles.label}>{t('refer.link')}</h2>
            <div className={styles.codeRow}>
              <span className={styles.link} data-testid="refer-link">{data.shareUrl}</span>
              <Button variant="secondary" onClick={() => void copy(data.shareUrl)} data-testid="refer-copy-link">
                {t('refer.copyLink')}
              </Button>
            </div>

            <div className={styles.share} role="group" aria-label={t('refer.share')}>
              {canShare && (
                <Button onClick={() => void navigator.share({ title: subject, text: message, url: data.shareUrl }).catch(() => undefined)} data-testid="refer-native-share">
                  {t('refer.share')}
                </Button>
              )}
              <a className={buttonClassName({ variant: 'secondary' })} href={`https://wa.me/?text=${encodeURIComponent(message)}`} target="_blank" rel="noopener noreferrer" data-testid="refer-whatsapp">
                {t('refer.shareWhatsApp')}
              </a>
              <a className={buttonClassName({ variant: 'secondary' })} href={`mailto:?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(message)}`} data-testid="refer-email">
                {t('refer.shareEmail')}
              </a>
              <a className={buttonClassName({ variant: 'secondary' })} href={`sms:?&body=${encodeURIComponent(message)}`} data-testid="refer-sms">
                {t('refer.shareSms')}
              </a>
            </div>
          </section>

          <section className={pages.card} aria-labelledby="refer-how">
            <h2 id="refer-how" className={pages.h}>{t('refer.how.title')}</h2>
            <ol className={styles.steps}>
              <li>{t('refer.how.1')}</li>
              <li>{t('refer.how.2', { min: fmt.money(data.program.minOrderMinor, currency) })}</li>
              <li>{t('refer.how.3', { days: data.program.returnWindowDays })}</li>
            </ol>
            <p className={styles.fine}>{t('refer.terms')}</p>
          </section>

          {data.asFriend && (
            <section className={pages.card} aria-labelledby="refer-welcome" data-testid="refer-as-friend">
              <h2 id="refer-welcome" className={pages.h}>{t('refer.asFriend.title')}</h2>
              <p className={styles.intro}>
                {data.asFriend.reward?.granted
                  ? t('refer.asFriend.granted', { reward: reward(data.asFriend.reward.terms) })
                  : t('refer.asFriend.body', { min: fmt.money(data.program.minOrderMinor, currency), reward: reward(data.asFriend.reward?.terms ?? data.program.referredReward) })}
              </p>
            </section>
          )}

          <section className={pages.card} aria-labelledby="refer-earnings">
            <h2 id="refer-earnings" className={pages.h}>{t('refer.earnings.title')}</h2>
            <dl className={styles.stats}>
              <div>
                <dt>{t('refer.earnings.invited')}</dt>
                <dd data-testid="refer-invited">{data.earnings.invited}</dd>
              </div>
              <div>
                <dt>{t('refer.earnings.rewarded')}</dt>
                <dd data-testid="refer-rewarded">{data.earnings.rewarded}</dd>
              </div>
              <div>
                <dt>{t('refer.earnings.earned')}</dt>
                <dd data-testid="refer-earned">
                  {fmt.money(data.earnings.earnedMinor, currency)}
                  {data.earnings.membershipDays > 0 ? ` + ${t('refer.reward.days', { days: data.earnings.membershipDays })}` : ''}
                </dd>
              </div>
              <div>
                <dt>{t('refer.earnings.pending')}</dt>
                <dd data-testid="refer-pending">{fmt.money(data.earnings.pendingMinor, currency)}</dd>
              </div>
            </dl>
          </section>

          <section className={pages.card} aria-labelledby="refer-friends">
            <h2 id="refer-friends" className={pages.h}>{t('refer.friends.title')}</h2>
            {data.friends.length === 0 && <p className={pages.muted}>{t('refer.friends.none')}</p>}
            <ul className={pages.list} data-testid="refer-friends">
              {data.friends.map((f) => (
                <li key={f.id} className={styles.friend} data-testid="refer-friend">
                  <span className={styles.friendName}>{f.friendName}</span>
                  <span className={styles.status}>{t(STATUS_KEYS[f.status])}</span>
                  <span className={styles.friendMeta}>{t('refer.friend.joined', { date: fmt.date(f.signedUpAt) })}</span>
                  <span className={styles.friendReward}>{friendReward(f)}</span>
                </li>
              ))}
            </ul>
          </section>
        </>
      )}
    </div>
  );

  function friendReward(f: ReferralFriend): string {
    if (f.status === 'CLOSED' || f.status === 'EXPIRED') return '';
    const terms = f.reward?.terms ?? data?.program.referrerReward;
    if (!terms) return '';
    if (f.reward?.granted) return t('refer.friend.granted', { reward: reward(terms) });
    return f.eligibleAt ? t('refer.friend.pendingOn', { reward: reward(terms), date: fmt.date(f.eligibleAt) }) : t('refer.friend.pending', { reward: reward(terms) });
  }
}
