'use client';

import {
  cartKeys,
  isApiError,
  preorderKeys,
  type PreorderListItem,
  type PreorderPlace,
  productKeys,
  type ProductSummary,
  searchKeys,
  SearchQuerySchema,
  siteKeys
} from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import {
  CalendarCheck,
  CheckCircle,
  Circle,
  CircleHalf,
  CreditCard,
  cx,
  Headset,
  type Icon,
  ImagePlaceholder,
  MapPin,
  QuantityStepper,
  Skeleton,
  Truck,
  UsersThree,
  useToast
} from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';

import { useLolaLauncher } from '@/components/shell/LolaLauncher';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { searchQueries } from '@/lib/api/queries';
import { lineForOffer, useCart, useCartMutation } from '@/lib/cart/useCart';
import { productHref } from '@/lib/links';
import { setAttribution } from '@/lib/preorder/attribution';
import { preorderDates, preorderRules } from '@/lib/preorder/copy';
import { type LivePreorder, useLivePreorder, useOpenPreorders, usePreorderResolution } from '@/lib/preorder/usePreorder';

import { Countdown } from './Countdown';
import styles from './Preorder.module.css';
import { PREORDER_PATH } from './PreorderBanners';
import { PreorderOffCard } from './PreorderHome';
import { PreorderPreview } from './PreorderPreview';

const PILL = { OPEN: styles.pillOpen, UPCOMING: styles.pillUpcoming, CLOSED: styles.pillClosed } as const;
const RULE_ICONS: Icon[] = [CalendarCheck, CreditCard, Truck, Headset];

/** Opens → Closes → Delivered, with the passed steps green and the next one gold. */
export function PreorderTimeline({ live }: { live: LivePreorder }) {
  const t = useT();
  const idx = { UPCOMING: -1, OPEN: 0, CLOSED: 1 }[live.stage];
  const steps = [
    [t('preorder.timeline.opens'), live.dates.opensT],
    [t('preorder.timeline.closes'), live.dates.closesT],
    [t('preorder.timeline.delivered'), live.dates.deliversT]
  ] as const;
  return (
    <ol className={styles.timeline} aria-label={t('preorder.timeline.label')} data-testid="preorder-timeline">
      {steps.map(([label, date], i) => {
        const done = i <= idx;
        const current = i === idx + 1;
        const StepIcon = done ? CheckCircle : current ? CircleHalf : Circle;
        return (
          <li key={label} className={cx(styles.step, done && styles.stepDone, current && styles.stepCurrent)} aria-current={current ? 'step' : undefined}>
            <span className={styles.stepLabel}>
              <StepIcon size={16} weight={done || current ? 'fill' : 'regular'} aria-hidden />
              {label}
            </span>
            <strong className={styles.stepDate}>{date}</strong>
          </li>
        );
      })}
    </ol>
  );
}

function ProductRow({ p, live }: { p: ProductSummary; live: LivePreorder }) {
  const t = useT();
  const fmt = useFormatters();
  const { data: cart } = useCart();
  const m = useCartMutation();
  const line = lineForOffer(cart, p.offerId);
  const locked = live.copy.addLabel;
  return (
    <li className={styles.row}>
      <span className={styles.rowThumb}>
        <ImagePlaceholder src={p.imageUrl} alt="" />
      </span>
      <span className={styles.rowText}>
        <Link href={productHref(p.atin)} className={styles.rowName}>
          {p.title}
        </Link>
        <span className={styles.rowSeller}>{p.sellerName}</span>
      </span>
      <strong className={styles.rowPrice}>{fmt.money(p.priceMinor, p.currency)}</strong>
      {line ? (
        <QuantityStepper
          value={line.quantity}
          max={locked ? line.quantity : line.maxQuantity}
          busy={m.isPending}
          itemLabel={p.title}
          onChange={(q) => m.mutate({ kind: 'quantity', lineId: line.lineId, quantity: q, title: p.title })}
          onRemove={() => m.mutate({ kind: 'remove', lineId: line.lineId, title: p.title })}
        />
      ) : (
        <button
          type="button"
          className={styles.addPill}
          disabled={!!locked || !p.inStock}
          onClick={() => m.mutate({ kind: 'add', offerId: p.offerId, quantity: 1, title: p.title })}
          aria-label={`${locked ?? (p.inStock ? t('card.addToCart') : t('card.unavailable'))}: ${p.title}`}
        >
          {locked ?? (p.inStock ? t('card.addToCart') : t('card.unavailable'))}
        </button>
      )}
    </li>
  );
}

/** One window: banner, stage pill, name, headline, timeline, countdown, "missed it" support, rules and its products. */
export function PreorderWindowPage({ live }: { live: LivePreorder }) {
  const t = useT();
  const fmt = useFormatters();
  const qc = useQueryClient();
  const lola = useLolaLauncher();
  const { r, w, copy, dates, city } = live;
  const department = w.departments[0] ?? null;
  const { data: results, isPending } = useQuery(searchQueries.results(browserApi(), SearchQuerySchema.parse({ department, size: 24 })));
  const products = (results?.hits ?? []).filter((h) => h.preorderEligible);
  const rules = preorderRules(t, fmt, dates, w, r.supportPhone);
  const hero = w.creatives.hero;
  return (
    <div className={styles.page} data-testid="preorder-page">
      {hero && (
        <div className={styles.banner}>
          <div role="img" aria-label={t('preorder.page.banner', { name: w.name })} className={styles.bannerImage} style={{ backgroundImage: `url("${hero.url}")` }} />
        </div>
      )}
      <div className={styles.pageGrid}>
        <div className={styles.col}>
          <div className={styles.titleBlock}>
            <span className={cx(styles.pill, PILL[live.stage])}>{copy.stageLabel}</span>
            <h1 className={styles.pageTitle}>{w.name}</h1>
            <p className={styles.muted}>{copy.headline}</p>
          </div>
          <PreorderTimeline live={live} />
          {r.countdownTo && (
            <div className={styles.cdBarWrap}>
              <Countdown variant="bar" to={r.countdownTo} label={copy.cdLabel} onElapsed={() => void qc.invalidateQueries({ queryKey: preorderKeys.all })} />
              {w.showCount && w.count !== null && live.stage !== 'UPCOMING' && (
                <span className={styles.cdBarCount}>
                  <UsersThree weight="fill" aria-hidden /> {t('preorder.count', { count: fmt.number(w.count), city })}
                </span>
              )}
            </div>
          )}
          {live.stage === 'CLOSED' && (
            <div className={styles.missed} data-testid="preorder-missed">
              <Headset weight="fill" className={styles.missedIcon} aria-hidden />
              <span className={styles.missedBody}>
                <strong>{t('preorder.missed.title')}</strong>
                <span className={styles.missedText}>{t('preorder.missed.body', { phone: r.supportPhone })}</span>
                <span className={styles.missedActions}>
                  <button type="button" className={styles.inkButton} onClick={() => lola.open()}>
                    {t('preorder.missed.chat')}
                  </button>
                  <a href={`tel:${r.supportPhone.replace(/[^+\d]/g, '')}`}>
                    <strong>{r.supportPhone}</strong>
                  </a>
                </span>
              </span>
            </div>
          )}
          <ul className={styles.rules} aria-label={t('preorder.rules.label')}>
            {rules.map((text, i) => {
              const RuleIcon = RULE_ICONS[i] ?? CalendarCheck;
              return (
                <li key={text} className={styles.rule}>
                  <RuleIcon className={styles.ruleIcon} aria-hidden />
                  <span>{text}</span>
                </li>
              );
            })}
          </ul>
        </div>
        <section className={styles.col} aria-labelledby="preorder-products">
          <div className={styles.listHead}>
            <h2 id="preorder-products" className={styles.h3}>
              {t('preorder.page.inThis')}
            </h2>
            <span className={styles.cats}>{w.categories.join(' · ')}</span>
          </div>
          {isPending ? (
            <Skeleton height={240} />
          ) : products.length ? (
            <ul className={styles.rows}>
              {products.map((p) => (
                <ProductRow key={p.atin} p={p} live={live} />
              ))}
            </ul>
          ) : (
            <p className={styles.muted}>{t('preorder.page.noProducts')}</p>
          )}
        </section>
      </div>
    </div>
  );
}

function OffCard({ w, onShop, busy }: { w: PreorderListItem; onShop: (w: PreorderListItem) => void; busy: boolean }) {
  const t = useT();
  const fmt = useFormatters();
  const d = preorderDates(w, fmt.tag);
  const open = w.stage === 'OPEN';
  return (
    <li className={styles.offCardItem} data-testid="preorder-card">
      <div role="img" aria-label={w.name} className={styles.offImage} style={w.heroUrl ? { backgroundImage: `url("${w.heroUrl}")` } : undefined} />
      <div className={styles.offCardBody}>
        <span className={cx(styles.offPill, open ? styles.pillOpen : styles.pillUpcoming)}>{open ? t('preorder.stage.open') : t('preorder.stage.upcoming')}</span>
        <strong className={styles.offName}>{w.name}</strong>
        <span className={styles.offWhere}>
          <MapPin aria-hidden /> {w.where}
        </span>
        <span className={styles.offLabel}>
          {open ? t('preorder.off.labelOpen', { closes: d.closesS, delivers: d.deliversS }) : t('preorder.off.labelUpcoming', { opens: d.opensS, delivers: d.deliversS })}
        </span>
        <button type="button" className={cx(styles.inkButton, styles.offCta)} onClick={() => onShop(w)} disabled={busy}>
          {t('preorder.off.shopFor', { city: w.place.city })}
        </button>
      </div>
    </li>
  );
}

/** No live pre-order for the shopper's place: open and upcoming windows elsewhere ("shop one for family"). */
export function PreorderOffPage({ city }: { city: string }) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const router = useRouter();
  const { data: open, isPending } = useOpenPreorders();
  const [busy, setBusy] = useState(false);
  const windows = open?.windows ?? [];
  const shop = async (w: PreorderListItem) => {
    setAttribution('page', { po: w.code });
    if (!w.zip) {
      router.push(`${PREORDER_PATH}?place=${encodeURIComponent(`${w.place.country}/${w.place.region}/${w.place.city}`)}`);
      return;
    }
    setBusy(true);
    try {
      const loc = await browserApi().setDeliveryLocation({ zip: w.zip });
      qc.setQueryData(siteKeys.deliveryLocation(), loc);
      await Promise.all([siteKeys.home(), searchKeys.all, productKeys.all, cartKeys.all, preorderKeys.all].map((queryKey) => qc.invalidateQueries({ queryKey })));
      toast.show(t('preorder.off.switched', { city: w.place.city, region: w.place.region }));
      window.scrollTo({ top: 0 });
    } catch (e) {
      toast.show(isApiError(e) ? e.message : t('auth.genericError'), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className={styles.page} data-testid="preorder-off-page">
      <div className={styles.offHead}>
        <h1 className={styles.offTitle}>{t('preorder.off.title')}</h1>
        <p className={styles.offBody}>{windows.length ? t('preorder.off.some', { city }) : t('preorder.off.none')}</p>
      </div>
      {isPending ? (
        <Skeleton height={220} />
      ) : (
        <ul className={styles.offList}>
          {windows.map((w) => (
            <OffCard key={w.id} w={w} onShop={(x) => void shop(x)} busy={busy} />
          ))}
        </ul>
      )}
    </div>
  );
}

function parsePlace(raw: string | null): PreorderPlace | undefined {
  const [country, region, city] = (raw ?? '').split('/');
  return country && region && city ? { country, region, city } : undefined;
}

/** /pre-orders: the window page for the shopper's place (or ?place=CC/RR/City), else the list of pre-orders elsewhere.
    `preview` adds the dev-only "Preview pre-order city" control (mock BFF). */
export function PreorderView({ placeParam, preview = false }: { placeParam: string | null; preview?: boolean }) {
  return (
    <>
      {preview && <PreorderPreview />}
      <PreorderViewBody placeParam={placeParam} />
    </>
  );
}

function PreorderViewBody({ placeParam }: { placeParam: string | null }) {
  const place = parsePlace(placeParam);
  const { data: r, isPending } = usePreorderResolution(place);
  const live = useLivePreorder(r);
  if (isPending) return <Skeleton height={420} />;
  if (live) return <PreorderWindowPage live={live} />;
  if (r && !r.metro && !place) {
    return (
      <>
        <PreorderOffCard r={r} />
        <PreorderOffPage city={r.place.city} />
      </>
    );
  }
  return <PreorderOffPage city={r?.place.city ?? ''} />;
}
