'use client';

import { accountQueries, isApiError, preorderKeys, type PreorderPlace, type PreorderResolution } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { TextField, UsersThree } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type FormEvent, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { setAttribution } from '@/lib/preorder/attribution';
import { type LivePreorder, useLivePreorder, usePreorderResolution } from '@/lib/preorder/usePreorder';

import { Countdown } from './Countdown';
import styles from './Preorder.module.css';
import { PREORDER_PATH } from './PreorderBanners';

/** Home hero for the shopper's pre-order (placement `hero`): creative, kicker, headline, countdown, dates, CTA, count. */
export function PreorderHero({ live }: { live: LivePreorder }) {
  const t = useT();
  const fmt = useFormatters();
  const qc = useQueryClient();
  const { r, w, copy, dates, city } = live;
  if (!r.placements.hero || !r.countdownTo) return null;
  const hero = w.creatives.hero;
  return (
    <section className={styles.hero} aria-label={t('preorder.hero.label', { city })} data-testid="preorder-hero">
      {hero && <div role="img" aria-label={w.name} className={styles.heroImage} style={{ backgroundImage: `url("${hero.url}")` }} />}
      <div className={styles.heroShade}>
        <span className={styles.kicker}>{copy.kicker}</span>
        <h2 className={styles.heroTitle}>{copy.headline}</h2>
        <div className={styles.heroRow}>
          <Countdown variant="blocks" to={r.countdownTo} label={copy.cdLabel} onElapsed={() => void qc.invalidateQueries({ queryKey: preorderKeys.all })} />
          <div className={styles.heroDates}>
            <span>
              {t('preorder.hero.orderBy')} <strong>{dates.closesT}</strong>
            </span>
            <span>
              {t('preorder.hero.delivered')} <strong>{dates.deliversT}</strong>
              {w.door && ` · ${t('preorder.terms.door', { fee: live.doorFee })}`}
            </span>
          </div>
        </div>
        <div className={styles.heroActions}>
          <Link href={PREORDER_PATH} className={styles.goldCta} onClick={() => setAttribution('hero', { po: w.code })}>
            {copy.cta}
          </Link>
          {w.showCount && w.count !== null && live.stage !== 'UPCOMING' && (
            <span className={styles.count}>
              <UsersThree weight="fill" aria-hidden /> {t('preorder.count', { count: fmt.number(w.count), city })}
            </span>
          )}
        </div>
      </div>
    </section>
  );
}

/** Email capture where the fallback is "waitlist only" (POST /v1/preorders/waitlist). */
export function WaitlistForm({ place }: { place: PreorderPlace }) {
  const t = useT();
  const api = browserApi();
  const { data: me } = useQuery(accountQueries.me(api));
  const [email, setEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const join = useMutation({ mutationFn: (e: string) => api.joinPreorderWaitlist({ email: e, place }) });
  const value = email || me?.user?.email || '';
  if (join.isSuccess)
    return (
      <p className={styles.waitlistDone} role="status">
        {t('preorder.waitlist.done', { email: join.variables, city: join.data.city })}
      </p>
    );
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!/^[^\s@]+@[^\s@]+\.[a-z]{2,}$/i.test(value.trim())) return setError(t('preorder.waitlist.invalid'));
    setError(null);
    join.mutate(value.trim(), { onError: (err) => setError(isApiError(err) ? err.message : t('auth.genericError')) });
  };
  return (
    <form className={styles.waitlist} onSubmit={submit} noValidate data-testid="preorder-waitlist">
      <div className={styles.waitlistField}>
        <TextField label={t('preorder.waitlist.email')} type="email" autoComplete="email" value={value} onChange={(e) => setEmail(e.target.value)} error={error} />
      </div>
      <button type="submit" className={styles.inkButton} disabled={join.isPending}>
        {t('preorder.waitlist.join')}
      </button>
    </form>
  );
}

/** /pre-orders for a covered city whose pre-order is off: fallback headline and body (and the waitlist form). */
export function PreorderOffCard({ r, headingLevel = 2 }: { r: PreorderResolution; headingLevel?: 1 | 2 }) {
  const t = useT();
  const waitlist = r.fallback === 'WAITLIST';
  const Heading = headingLevel === 1 ? 'h1' : 'h2';
  return (
    <section className={styles.offCard} data-testid="preorder-off">
      <Heading className={styles.offTitle}>{waitlist ? t('preorder.off.headlineWaitlist', { city: r.place.city }) : t('preorder.off.headlineShip')}</Heading>
      <p className={styles.offBody}>{waitlist ? t('preorder.off.bodyWaitlist') : t('preorder.off.bodyShip')}</p>
      {waitlist && <WaitlistForm place={r.place} />}
    </section>
  );
}

/** Top of the home page (Shop design `preHero`): the pre-order hero only where the shopper's city has a live pre-order.
    Every other city (next-day metros included) shows no pre-order or fallback UI on the home page. */
export function PreorderHome() {
  const { data: r } = usePreorderResolution();
  const live = useLivePreorder(r);
  return live ? <PreorderHero live={live} /> : null;
}
