'use client';

import { preorderKeys } from '@afrotransact/api-client';
import { CalendarCheck, Timer } from '@afrotransact/ui';
import { useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';

import { useT } from '@/i18n/t';
import { setAttribution } from '@/lib/preorder/attribution';
import { type Availability } from '@/lib/preorder/useAvailability';
import { type LivePreorder } from '@/lib/preorder/usePreorder';

import { Countdown } from './Countdown';
import styles from './Preorder.module.css';
import { PREORDER_PATH } from './PreorderBanners';

/** PDP delivery box for a pre-order item: badge with stage, delivery day, note (charged at checkout), countdown. */
export function PdpPreorderBox({ live }: { live: LivePreorder }) {
  const t = useT();
  const qc = useQueryClient();
  const { copy, dates, r, w } = live;
  return (
    <div className={styles.pdpBox} data-testid="pdp-preorder">
      <span className={styles.pdpBadge}>
        <CalendarCheck weight="fill" aria-hidden />
        {t('preorder.pdp.badge', { stage: copy.stageLabel })}
      </span>
      <p className={styles.pdpDelivered}>
        {t('preorder.hero.delivered')} <strong>{dates.deliversT}</strong>
      </p>
      <p className={styles.pdpNote}>{copy.pdpNote}</p>
      {r.countdownTo && (
        <span className={styles.pdpTimer}>
          <Timer className={styles.pdpTimerIcon} aria-hidden />
          <Countdown variant="inline" to={r.countdownTo} label={copy.cdLabel} onElapsed={() => void qc.invalidateQueries({ queryKey: preorderKeys.all })} />
        </span>
      )}
      <Link href={PREORDER_PATH} onClick={() => setAttribution('pdp', { po: w.code })}>
        {t('preorder.pdp.banner', { name: w.name })}
      </Link>
    </div>
  );
}

/** PDP banner creative below the delivery box (placement `pdp`), linking to the pre-order page. */
export function PdpPreorderBanner({ live }: { live: LivePreorder }) {
  const t = useT();
  const creative = live.w.creatives.pdp;
  if (!live.r.placements.pdp || !creative) return null;
  return (
    <Link
      href={PREORDER_PATH}
      className={styles.pdpBanner}
      style={{ backgroundImage: `url("${creative.url}")` }}
      aria-label={t('preorder.pdp.banner', { name: live.w.name })}
      onClick={() => setAttribution('pdp', { po: live.w.code })}
      data-testid="pdp-preorder-banner"
    />
  );
}

/** Delivery text for items that are not pre-order where a pre-order city is covered (ships now / waitlist). */
export function PdpStandardDelivery({ availability, eligible }: { availability: Availability; eligible: boolean }) {
  const t = useT();
  const kind = availability.state.kind;
  if (kind === 'waitlist')
    return (
      <p className={styles.stdArrive} data-testid="pdp-delivery-off">
        <strong>{t('preorder.pdp.waitlist')}</strong>
      </p>
    );
  return (
    <div className={styles.pdpBox} data-testid="pdp-delivery-off">
      <p className={styles.stdArrive}>
        <strong>{t('preorder.pdp.shipsNow')}</strong>
      </p>
      <p className={styles.pdpNote}>{eligible ? t('preorder.pdp.offNote') : t('preorder.pdp.shipsNowNote')}</p>
    </div>
  );
}
