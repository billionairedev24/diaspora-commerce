'use client';

import {
  cartKeys,
  isApiError,
  preorderFallback,
  preorderKeys,
  type PreorderListItem,
  type PreorderPlace,
  productKeys,
  searchKeys,
  SearchQuerySchema,
  siteKeys
} from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { DefinitionList, FlatPage, PageHeader, Section, Skeleton, StatusText, TextLinkButton, textLinkClassName, useToast } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { type ReactNode, useMemo, useState } from 'react';

import { CartAwareProductCard } from '@/components/common/CartAwareProductCard';
import searchStyles from '@/components/search/Search.module.css';
import { useLolaLauncher } from '@/components/shell/LolaLauncher';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { searchQueries } from '@/lib/api/queries';
import { setAttribution } from '@/lib/preorder/attribution';
import { preorderDates, preorderRules } from '@/lib/preorder/copy';
import { type LivePreorder, useLivePreorder, useOpenPreorders, usePreorderResolution } from '@/lib/preorder/usePreorder';

import { Countdown } from './Countdown';
import styles from './Preorder.module.css';
import { PREORDER_PATH } from './PreorderBanners';
import { PreorderOffCard } from './PreorderHome';

const STAGE_TONE = { OPEN: 'positive', UPCOMING: 'neutral', CLOSED: 'warning' } as const;

/** Opens, closes and delivery dates as plain definition rows, with the stage and its live countdown on top. */
function WindowFacts({ live }: { live: LivePreorder }) {
  const t = useT();
  const fmt = useFormatters();
  const qc = useQueryClient();
  const { r, w, copy, dates, city } = live;
  const items = [
    {
      term: t('preorder.page.status'),
      value: (
        <>
          <StatusText tone={STAGE_TONE[live.stage]}>{copy.stageLabel}</StatusText>
          {r.countdownTo && (
            <>
              {' · '}
              <Countdown variant="inline" to={r.countdownTo} label={copy.cdLabel} onElapsed={() => void qc.invalidateQueries({ queryKey: preorderKeys.all })} />
            </>
          )}
        </>
      )
    },
    { term: t('preorder.timeline.opens'), value: dates.opensT },
    { term: t('preorder.timeline.closes'), value: dates.closesT },
    { term: t('preorder.timeline.delivered'), value: dates.deliversT }
  ];
  if (w.showCount && w.count !== null && live.stage !== 'UPCOMING') items.push({ term: t('preorder.page.orders'), value: t('preorder.count', { count: fmt.number(w.count), city }) });
  return <DefinitionList items={items} testId="preorder-timeline" />;
}

/** The pre-order products: the search grid, with "Reserve" as the add label. */
function WindowProducts({ live }: { live: LivePreorder }) {
  const t = useT();
  const { w } = live;
  const department = w.departments[0] ?? null;
  const { data: results, isPending } = useQuery(searchQueries.results(browserApi(), SearchQuerySchema.parse({ department, size: 24 })));
  const products = (results?.hits ?? []).filter((h) => h.preorderEligible);
  const labels = useMemo(() => ({ addToCart: t('preorder.reserve') }), [t]);
  return (
    <Section title={t('preorder.page.inThis')} lead={w.categories.join(' · ')} id="preorder-products">
      {isPending ? (
        <Skeleton height={240} />
      ) : products.length ? (
        <ul className={searchStyles.grid} data-testid="preorder-grid">
          {products.map((p) => (
            <li key={p.atin} style={{ display: 'grid' }}>
              <CartAwareProductCard product={p} labels={labels} />
            </li>
          ))}
        </ul>
      ) : (
        <p className={styles.muted}>{t('preorder.page.noProducts')}</p>
      )}
    </Section>
  );
}

/** One window: header with the order-by and delivery dates, its dates and countdown, "missed it" support, the rules and its products. */
export function PreorderWindowPage({ live }: { live: LivePreorder }) {
  const t = useT();
  const fmt = useFormatters();
  const lola = useLolaLauncher();
  const { r, w, dates } = live;
  const rules = preorderRules(t, fmt, dates, w, r.supportPhone);
  return (
    <FlatPage width="wide">
      <div data-testid="preorder-page" className={styles.flow}>
        <PageHeader title={w.name} lead={t('preorder.page.lead', { closes: dates.closesT, delivers: dates.deliversT })} />
        <WindowFacts live={live} />
        {live.stage === 'CLOSED' && (
          <Section title={t('preorder.missed.title')} testId="preorder-missed">
            <p className={styles.muted}>{t('preorder.missed.body', { phone: r.supportPhone })}</p>
            <p className={styles.actions}>
              <TextLinkButton onClick={() => lola.open()}>{t('preorder.missed.chat')}</TextLinkButton>
              <a className={textLinkClassName} href={`tel:${r.supportPhone.replace(/[^+\d]/g, '')}`}>
                {r.supportPhone}
              </a>
            </p>
          </Section>
        )}
        <Section title={t('preorder.rules.label')} id="preorder-rules">
          <ul className={styles.rules}>
            {rules.map((text) => (
              <li key={text}>{text}</li>
            ))}
          </ul>
        </Section>
        <WindowProducts live={live} />
        <OtherOpenWindows currentId={w.id} />
      </div>
    </FlatPage>
  );
}

/** Windows by place as plain rows: name, place, order-by and delivery dates, and the "Shop for <city>" action that switches the delivery place. */
function WindowList({ windows, isPending }: { windows: PreorderListItem[]; isPending: boolean }) {
  const t = useT();
  const fmt = useFormatters();
  const qc = useQueryClient();
  const toast = useToast();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
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
  if (isPending) return <Skeleton height={120} />;
  return (
    <ul className={styles.windows}>
      {windows.map((w) => {
        const d = preorderDates(w, fmt.tag);
        const open = w.stage === 'OPEN';
        return (
          <li key={w.id} className={styles.window} data-testid="preorder-card">
            <div className={styles.windowMain}>
              <strong className={styles.windowName}>{w.name}</strong>
              <span className={styles.windowDetail}>
                {w.where} · {open ? t('preorder.off.labelOpen', { closes: d.closesS, delivers: d.deliversS }) : t('preorder.off.labelUpcoming', { opens: d.opensS, delivers: d.deliversS })}
              </span>
            </div>
            <StatusText tone={open ? 'positive' : 'neutral'}>{open ? t('preorder.stage.open') : t('preorder.stage.upcoming')}</StatusText>
            <TextLinkButton onClick={() => void shop(w)} disabled={busy}>
              {t('preorder.off.shopFor', { city: w.place.city })}
            </TextLinkButton>
          </li>
        );
      })}
    </ul>
  );
}

/** Below a live window: the other open windows, by place (nothing when there are none). */
function OtherOpenWindows({ currentId }: { currentId: string }) {
  const t = useT();
  const { data, isPending } = useOpenPreorders();
  const others = (data?.windows ?? []).filter((w) => w.stage === 'OPEN' && w.id !== currentId).sort((x, y) => x.where.localeCompare(y.where));
  if (!isPending && !others.length) return null;
  return (
    <Section title={t('preorder.others.title')} lead={t('preorder.others.body')} id="preorder-others" testId="preorder-other-windows">
      <WindowList windows={others} isPending={isPending} />
    </Section>
  );
}

/** No live pre-order for the shopper's place: the fallback statement (when the city is covered) and the windows elsewhere ("shop one for family"). */
export function PreorderOffPage({ city, fallback }: { city: string; fallback?: ReactNode }) {
  const t = useT();
  const { data: open, isPending } = useOpenPreorders();
  const windows = open?.windows ?? [];
  return (
    <FlatPage width="wide">
      <div data-testid="preorder-off-page" className={styles.flow}>
        <PageHeader title={t('preorder.off.title')} lead={windows.length ? t('preorder.off.some', { city }) : t('preorder.off.none')} />
        {fallback}
        {(isPending || windows.length > 0) && (
          <Section title={t('preorder.windows.title')} id="preorder-windows">
            <WindowList windows={windows} isPending={isPending} />
          </Section>
        )}
      </div>
    </FlatPage>
  );
}

function parsePlace(raw: string | null): PreorderPlace | undefined {
  const [country, region, city] = (raw ?? '').split('/');
  return country && region && city ? { country, region, city } : undefined;
}

/** /pre-orders: the window page for the shopper's place (or ?place=CC/RR/City), else the list of pre-orders elsewhere.
    Everything shown comes from the resolution API (no prototype "preview as" controls). */
export function PreorderView({ placeParam }: { placeParam: string | null }) {
  return <PreorderViewBody placeParam={placeParam} />;
}

function PreorderViewBody({ placeParam }: { placeParam: string | null }) {
  const place = parsePlace(placeParam);
  const { data: r, isPending } = usePreorderResolution(place);
  const live = useLivePreorder(r);
  if (isPending) return <Skeleton height={420} />;
  if (live) return <PreorderWindowPage live={live} />;
  // The ship / waitlist fallback is for a covered city whose pre-order is off; other cities just see the list.
  if (r && preorderFallback(r) && !place) {
    return <PreorderOffPage city={r.place.city} fallback={<PreorderOffCard r={r} />} />;
  }
  return <PreorderOffPage city={r?.place.city ?? ''} />;
}
