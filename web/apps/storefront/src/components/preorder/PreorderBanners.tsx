'use client';

import { preorderKeys } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { CalendarCheck, cx, Package } from '@afrotransact/ui';
import { useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { usePathname } from 'next/navigation';

import { useT } from '@/i18n/t';
import { setAttribution } from '@/lib/preorder/attribution';
import { familyCities } from '@/lib/preorder/copy';
import { type LivePreorder, useLivePreorder, useOpenPreorders, usePreorderResolution } from '@/lib/preorder/usePreorder';

import { Countdown } from './Countdown';
import styles from './Preorder.module.css';

export const PREORDER_PATH = '/pre-orders';

const RIBBON_TONE = { OPEN: styles.ribbonOpen, UPCOMING: styles.ribbonUpcoming, CLOSED: styles.ribbonClosed } as const;

/** Gold / grey ribbon under the header on every page (placement `ribbon`); a link that records the `ribbon` touch. */
export function PreorderRibbon({ live }: { live: LivePreorder }) {
  const t = useT();
  const qc = useQueryClient();
  const { copy, w, stage, r } = live;
  if (!r.placements.ribbon || !r.countdownTo) return null;
  return (
    <Link href={PREORDER_PATH} className={cx(styles.ribbon, RIBBON_TONE[stage])} onClick={() => setAttribution('ribbon', { po: w.code })} data-testid="preorder-ribbon">
      <CalendarCheck weight="fill" className={styles.ribbonIcon} aria-hidden />
      <strong>{copy.ribbon}</strong>
      <span>{copy.ribbonSub}</span>
      <span className={styles.ribbonEnd}>
        <Countdown variant="chip" to={r.countdownTo} label={copy.cdLabel} onElapsed={() => void qc.invalidateQueries({ queryKey: preorderKeys.all })} />
        <span className={styles.ribbonView}>{t('preorder.view')}</span>
      </span>
    </Link>
  );
}

/** Pre-order and coverage banners between the header and the page. */
export function PreorderBanners() {
  const t = useT();
  const fmt = useFormatters();
  const pathname = usePathname();
  const { data: r } = usePreorderResolution();
  const live = useLivePreorder(r);
  const { data: open } = useOpenPreorders();
  if (!r) return null;
  if (live) return <PreorderRibbon live={live} />;
  if (!r.metro) {
    // Covered city where the pre-order is off: the platform fallback (ship by UPS Ground, or waitlist only).
    return (
      <div className={styles.offRibbon} role="note" data-testid="preorder-off-ribbon">
        <Package size={18} aria-hidden />
        {r.fallback === 'WAITLIST' ? t('preorder.off.ribbonWaitlist', { city: r.place.city }) : t('preorder.off.ribbonShip', { city: r.place.city })}
      </div>
    );
  }
  const windows = open?.windows ?? [];
  if (!windows.length || !r.placements.family || pathname === PREORDER_PATH) return null;
  const first = windows[0];
  return (
    <Link href={PREORDER_PATH} className={styles.family} onClick={() => setAttribution('page')} data-testid="preorder-family">
      <CalendarCheck weight="fill" className={styles.familyIcon} aria-hidden />
      <strong>{t('preorder.family.text', { cities: familyCities(t, windows.map((w) => w.place.city)) })}</strong>
      {first && <span className={styles.familySub}>{t('preorder.family.sub', { fee: fmt.money(first.doorFeeMinor, first.currency) })}</span>}
      <span className={styles.familyCta}>{t('preorder.family.cta')}</span>
    </Link>
  );
}
