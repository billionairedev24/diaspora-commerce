'use client';

import { type HomeBlock, type HomeModuleDecision } from '@afrotransact/api-client';
import { type HomePreviewQuery, type PreviewCatalogue, SIMULATED_CATALOGUES, staffHomeQueries, type StaffLocale } from '@afrotransact/api-client/staff-home';
import { Button, Select, TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useEffect, useState } from 'react';

import { Toggle } from '@/components/preorders/primitives';
import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { EN_MESSAGES, type MessageKey, type Translator, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';

import { reasonTone } from './model';

const LOCALES: readonly StaffLocale[] = ['en', 'fr', 'es', 'ar', 'yo'];
const has = (k: string): k is MessageKey => Object.prototype.hasOwnProperty.call(EN_MESSAGES, k);
const todayIso = () => new Date().toISOString().slice(0, 10);
const randomSeed = () => `visit-${Math.random().toString(36).slice(2, 8)}`;

/** Names of the things a block shows (never their ids): products, departments, communities, services. */
function itemNames(b: HomeBlock): string[] {
  return [...(b.rail?.items ?? []).map((p) => p.title), ...(b.cells ?? []).map((c) => c.labels?.en ?? c.label), ...(b.services ?? []).map((s) => s.title), ...(b.products ?? []).map((p) => p.title)];
}

/**
 * Marketing > Home preview (S418, S430): the home as shoppers would see it for a ZIP, signed-in state, language and date, with
 * every module listed by its name as shown or hidden, the plain reason and the live count against its threshold. Read only: it
 * changes nothing and holds no shopper data. The variety seed is shown so a view can be reproduced.
 */
export function HomePreviewView() {
  const t = useT();
  const api = staffApi().home;
  const [zip, setZip] = useState('');
  const [signedIn, setSignedIn] = useState(false);
  const [locale, setLocale] = useState<StaffLocale>('en');
  const [asOf, setAsOf] = useState(todayIso());
  const [catalog, setCatalog] = useState<PreviewCatalogue>('live');
  const [seed, setSeed] = useState('');
  const [deterministic, setDeterministic] = useState(false);
  const [applied, setApplied] = useState<HomePreviewQuery>({ signedIn: false, locale: 'en', asOf: todayIso() });
  // A ZIP applies once it is whole (5 digits) or cleared; everything else applies at once.
  const zipOk = zip === '' || /^\d{5}$/.test(zip);
  useEffect(() => {
    if (!zipOk) return;
    setApplied({ ...(zip ? { zip } : {}), signedIn, locale, ...(asOf ? { asOf } : {}), ...(catalog !== 'live' ? { catalog } : {}), ...(seed.trim() && !deterministic ? { seed: seed.trim() } : {}), ...(deterministic ? { deterministic } : {}) });
  }, [zip, zipOk, signedIn, locale, asOf, catalog, seed, deterministic]);
  const preview = useQuery(staffHomeQueries.preview(api, applied));
  const data = preview.data;

  return (
    <div className="flex flex-col gap-5" data-testid="home-preview" data-tour="home-preview">
      <Notice tone="info" icon="eye" title={t('st.home.preview.readOnly')}>
        {t('st.home.preview.readOnlyHelp')}
      </Notice>

      <Card title={t('st.home.preview.controls')}>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <TextField
            label={t('st.home.preview.zip')}
            inputMode="numeric"
            maxLength={5}
            value={zip}
            error={zipOk ? null : t('st.home.preview.zipInvalid')}
            help={t('st.home.preview.zipHelp')}
            onChange={(e) => setZip(e.currentTarget.value.replace(/\D/g, ''))}
          />
          <Select label={t('st.home.preview.language')} options={LOCALES.map((l) => ({ value: l, label: t(`st.home.locale.${l}` as MessageKey) }))} value={locale} onChange={(e) => setLocale(e.currentTarget.value as StaffLocale)} />
          <TextField label={t('st.home.preview.asOf')} type="date" value={asOf} help={t('st.home.preview.asOfHelp')} onChange={(e) => setAsOf(e.currentTarget.value)} />
          <Select
            label={t('st.home.preview.catalogue')}
            help={t('st.home.preview.catalogueHelp')}
            options={[{ value: 'live', label: t('st.home.preview.live') }, ...SIMULATED_CATALOGUES.map((n) => ({ value: n, label: t('st.home.preview.simulated', { n: Number(n).toLocaleString('en-US') }) }))]}
            value={catalog}
            onChange={(e) => setCatalog(e.currentTarget.value as PreviewCatalogue)}
          />
        </div>
        <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
          <label className="flex items-center gap-2 text-sm font-semibold">
            <Toggle on={signedIn} label={t('st.home.preview.signedIn')} onClick={() => setSignedIn((v) => !v)} />
            {t('st.home.preview.signedIn')}
          </label>
          <label className="flex items-center gap-2 text-sm font-semibold">
            <Toggle on={deterministic} label={t('st.home.preview.deterministic')} onClick={() => setDeterministic((v) => !v)} />
            <span>
              {t('st.home.preview.deterministic')}
              <span className="block text-xs font-normal text-muted">{t('st.home.preview.deterministicHelp')}</span>
            </span>
          </label>
          <div className="flex items-end gap-2">
            <TextField label={t('st.home.preview.seed')} value={seed} maxLength={80} disabled={deterministic} placeholder={data?.seed ?? ''} help={t('st.home.preview.seedHelp')} onChange={(e) => setSeed(e.currentTarget.value)} />
            <Button type="button" variant="neutral" disabled={deterministic} onClick={() => setSeed(randomSeed())}>
              {t('st.home.preview.another')}
            </Button>
          </div>
        </div>
      </Card>

      {preview.isPending ? (
        <Spinner label={t('st.home.loading')} />
      ) : preview.isError || !data ? (
        <ErrorState error={preview.error} onRetry={() => void preview.refetch()} />
      ) : (
        <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
          <Card title={t('st.home.preview.page')}>
            <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm" data-testid="preview-facts">
              <dt className="font-semibold">{t('st.home.preview.factsAsOf')}</dt>
              <dd className="m-0">{dateTime(data.asOf)}</dd>
              <dt className="font-semibold">{t('st.home.preview.factsFor')}</dt>
              <dd className="m-0">{[data.deliverTo?.city, data.deliverTo?.zip, signedIn ? t('st.home.preview.asSignedIn') : t('st.home.preview.asGuest')].filter(Boolean).join(' · ')}</dd>
              <dt className="font-semibold">{t('st.home.preview.factsSeed')}</dt>
              <dd className="m-0">
                <code data-testid="preview-seed">{data.seed}</code>{' '}
                <span className="text-muted">{data.seed === 'default' ? t('st.home.preview.seedDefault') : t('st.home.preview.seedReplay')}</span>
              </dd>
              <dt className="font-semibold">{t('st.home.preview.factsSeason')}</dt>
              <dd className="m-0">{data.season ? t('st.home.preview.season', { name: data.season.name, modules: data.season.boosted.join(', ') || '-' }) : t('st.home.preview.noSeason')}</dd>
              {data.catalog !== 'live' ? (
                <>
                  <dt className="font-semibold">{t('st.home.preview.catalogue')}</dt>
                  <dd className="m-0">{t('st.home.preview.simulated', { n: Number(data.catalog).toLocaleString('en-US') })}</dd>
                </>
              ) : null}
            </dl>
            <PageWireframe t={t} blocks={data.blocks} decisions={data.decisions} />
          </Card>

          <Card title={t('st.home.preview.modules')}>
            <p className="m-0 text-sm text-muted">{t('st.home.preview.modulesHelp')}</p>
            <ul className="m-0 flex list-none flex-col gap-3 p-0" data-testid="preview-decisions">
              {data.decisions.map((d) => (
                <Decision key={d.module} d={d} t={t} />
              ))}
            </ul>
          </Card>
        </div>
      )}
    </div>
  );
}

function PageWireframe({ t, blocks, decisions }: { t: Translator<MessageKey>; blocks: HomeBlock[]; decisions: HomeModuleDecision[] }) {
  const nameOf = (b: HomeBlock): string => {
    const m = b.tracking?.module ?? b.module;
    const decision = decisions.find((d) => d.module === m);
    if (decision) return decision.label;
    if (b.sponsored) return t('st.home.preview.sponsored', { name: b.advertiser ?? '' });
    const k = `st.home.blockType.${b.type}`;
    return has(k) ? t(k) : (b.title ?? t('st.home.blockType.other'));
  };
  if (blocks.length === 0) return <p className="m-0 text-sm text-muted">{t('st.home.preview.empty')}</p>;
  return (
    <ol className="m-0 flex list-none flex-col gap-2 p-0" aria-label={t('st.home.preview.page')} data-testid="preview-page">
      {blocks.map((b, i) => {
        const names = itemNames(b);
        return (
          <li key={b.id} className="flex flex-col gap-0.5 border-b border-border pb-2 text-sm">
            <span className="flex flex-wrap items-baseline gap-2">
              <span className="text-xs text-muted">{i + 1}</span>
              <strong>{nameOf(b)}</strong>
              {b.textOnly ? <StatusPill tone="info">{t('st.home.preview.textOnly')}</StatusPill> : null}
              {b.sponsored ? <StatusPill tone="warn">{t('st.home.preview.paid')}</StatusPill> : null}
            </span>
            {names.length > 0 ? (
              <span className="text-xs text-muted">
                {names.slice(0, 5).join(', ')}
                {names.length > 5 ? ` ${t('st.home.preview.more', { n: names.length - 5 })}` : ''}
              </span>
            ) : null}
          </li>
        );
      })}
    </ol>
  );
}

function Decision({ d, t }: { d: HomeModuleDecision; t: Translator<MessageKey> }) {
  const reasonKey = `st.home.reason.${d.reason}`;
  const anchor = d.editRule ?? `rule-${d.module}`;
  return (
    <li className="flex flex-col gap-1 border-b border-border pb-3 text-sm" data-testid={`decision-${d.shown ? 'shown' : 'hidden'}`}>
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="m-0 text-sm font-bold">{d.label}</h3>
        <StatusPill tone={d.shown ? 'good' : 'info'}>{d.shown ? t('st.home.preview.shown') : t('st.home.preview.hidden')}</StatusPill>
        {d.shown && d.order ? <span className="text-xs text-muted">{t('st.home.preview.position', { n: d.order })}</span> : null}
        {!d.shown && d.reason !== 'disabled-by-staff' ? <StatusPill tone={reasonTone(d.reason)}>{has(reasonKey) ? t(reasonKey) : d.reason}</StatusPill> : null}
        {d.reason === 'disabled-by-staff' ? <StatusPill tone="info">{t('st.home.reason.disabled-by-staff')}</StatusPill> : null}
        {d.pinned ? <StatusPill tone="brand">{t('st.home.preview.pinned')}</StatusPill> : null}
      </div>
      <p className="m-0">{d.detail}</p>
      {d.need !== undefined && d.need > 0 ? (
        <span className="text-xs text-muted" data-testid="decision-count">
          {t('st.home.preview.count', { have: d.have ?? 0, need: d.need })}
        </span>
      ) : null}
      {d.units && d.units.length > 0 ? <span className="text-xs text-muted">{t('st.home.preview.units', { list: d.units.join(', ') })}</span> : null}
      <Link className="text-xs" href={`?tab=home#${anchor}`}>
        {t('st.home.preview.editRule', { name: d.label })}
      </Link>
    </li>
  );
}
