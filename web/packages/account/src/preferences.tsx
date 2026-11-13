'use client';

import { type AccountPreferences, DATE_FORMATS, NUMBER_FORMATS, settingsKeys, settingsQueries, TABLE_DENSITIES } from '@afrotransact/api-client';
import { writeDefaultDensity } from '@afrotransact/data-table';
import { createFormatters, type FormatPreferences, intlTag, isLocale, type Locale, localeCookie, LOCALES, matchLocale } from '@afrotransact/i18n';
import { FormatPreferencesProvider, useI18n } from '@afrotransact/i18n/react';
import { useMutation, useMutationState, useQuery, useQueryClient } from '@tanstack/react-query';
import { Fragment, type ReactNode, useEffect, useMemo, useState } from 'react';

import { useAccountEnv, useOptionalAccountEnv } from './context';
import { type AccountMessageKey, useAccountT } from './i18n/t';
import { Block, errorText, LoadError, Loading, PageHead, styles } from './parts';
import { SaveBar, SegmentedToggle, SettingRow, SettingSelect } from './settings';

const SAMPLE_DATE = '2026-10-31T18:45:00Z';
const SAMPLE_NUMBER = 1234567.89;
const SAMPLE_AMOUNT_MINOR = 129900;
const SAVE_KEY = ['account', 'preferences', 'save'] as const;

function deviceZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone;
  } catch {
    return 'UTC';
  }
}

function zones(): string[] {
  try {
    return Intl.supportedValuesOf('timeZone');
  } catch {
    return ['UTC', 'America/New_York', 'America/Chicago', 'America/Denver', 'America/Los_Angeles', 'Europe/London', 'Africa/Lagos', 'Africa/Accra', 'Africa/Nairobi'];
  }
}

function PreferencesForm({ data, showDensity, onLanguageChanged }: { data: AccountPreferences; showDensity: boolean; onLanguageChanged?: (locale: Locale) => void }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const { locale: uiLocale } = useI18n();
  const qc = useQueryClient();
  const languageOf = (d: AccountPreferences): Locale => (d.locale ? (matchLocale([d.locale]) ?? uiLocale) : uiLocale);
  const [language, setLanguage] = useState<Locale>(() => languageOf(data));
  const [zone, setZone] = useState(data.timeZone ?? '');
  const [date, setDate] = useState(data.dateFormat);
  const [num, setNum] = useState(data.numberFormat);
  const [density, setDensity] = useState(data.tableDensity);
  const [error, setError] = useState<string | null>(null);
  const device = useMemo(deviceZone, []);
  const zoneOptions = useMemo(() => [{ value: '', label: t('prefs.timeZoneDevice', { zone: device }) }, ...zones().map((z) => ({ value: z, label: z.replace(/_/g, ' ') }))], [t, device]);
  const formatters = (d: AccountPreferences['dateFormat'], n: AccountPreferences['numberFormat']) => createFormatters(language, { timeZone: zone || null, dateFormat: d, numberFormat: n });
  const dirty = language !== languageOf(data) || zone !== (data.timeZone ?? '') || date !== data.dateFormat || num !== data.numberFormat || (showDensity && density !== data.tableDensity);
  // A saved date or number style re-mounts the pages (so every figure re-formats): the cached mutation keeps "Saved" on screen.
  const saved = useMutationState({ filters: { mutationKey: SAVE_KEY, status: 'success' } }).length > 0;
  const save = useMutation({
    mutationKey: SAVE_KEY,
    gcTime: 15_000,
    mutationFn: () => api.saveAccountPreferences({ locale: intlTag(language), timeZone: zone || null, dateFormat: date, numberFormat: num, tableDensity: density }),
    onSuccess: (done) => {
      qc.setQueryData(settingsKeys.preferences(), done);
      writeDefaultDensity(done.tableDensity === 'COMPACT' ? 'compact' : 'comfortable');
      setError(null);
      if (language !== uiLocale) {
        document.cookie = localeCookie(language);
        onLanguageChanged?.(language);
      }
    },
    onError: (e) => setError(errorText(t, e, 'timeZone'))
  });
  const chosen = formatters(date, num);
  const withExample = (name: string, example: string) => t('prefs.withExample', { name, example });
  return (
    <Block id="preferences" testId="preferences" title={t('prefs.title')} lead={t('prefs.lead')}>
      <form className={styles.settings} onSubmit={(e) => { e.preventDefault(); if (dirty) save.mutate(); }} noValidate>
        <SettingRow label={t('prefs.language')} hint={t('prefs.languageHelp')}>
          {(c) => (
            <SettingSelect
              control={c}
              value={language}
              onChange={(v) => isLocale(v) && setLanguage(v)}
              options={LOCALES.map((l) => ({ value: l.code, label: l.code === 'en' ? l.name : `${l.name} · ${l.english}` }))}
            />
          )}
        </SettingRow>
        <SettingRow label={t('prefs.timeZone')} hint={t('prefs.timeZoneHelp')} error={error}>
          {(c) => <SettingSelect control={c} value={zone} onChange={setZone} options={zoneOptions} />}
        </SettingRow>
        <SettingRow label={t('prefs.dateFormat')}>
          {(c) => (
            <SettingSelect
              control={c}
              value={date}
              onChange={(v) => setDate(v as typeof date)}
              options={DATE_FORMATS.map((v) => ({ value: v, label: withExample(t(`prefs.date.${v}` as AccountMessageKey), formatters(v, num).date(SAMPLE_DATE, 'short')) }))}
            />
          )}
        </SettingRow>
        <SettingRow label={t('prefs.numberFormat')}>
          {(c) => (
            <SettingSelect
              control={c}
              value={num}
              onChange={(v) => setNum(v as typeof num)}
              options={NUMBER_FORMATS.map((v) => ({ value: v, label: v === 'AUTO' ? withExample(t('prefs.number.AUTO'), formatters(date, v).number(SAMPLE_NUMBER)) : formatters(date, v).number(SAMPLE_NUMBER) }))}
            />
          )}
        </SettingRow>
        {showDensity ? (
          <SettingRow label={t('prefs.density')} hint={t('prefs.densityHelp')} group>
            {(c) => <SegmentedToggle control={c} value={density} onChange={setDensity} options={TABLE_DENSITIES.map((v) => ({ value: v, label: t(`prefs.density.${v}` as AccountMessageKey) }))} />}
          </SettingRow>
        ) : null}
        <p className={styles.preview} data-testid="prefs-preview">
          {t('prefs.preview', { date: chosen.date(SAMPLE_DATE, 'short'), number: chosen.number(SAMPLE_NUMBER), amount: chosen.money(SAMPLE_AMOUNT_MINOR) })}
        </p>
        <SaveBar dirty={dirty} saving={save.isPending} saved={saved} />
      </form>
    </Block>
  );
}

/** Preferences: language, time zone, date and number format, and (back offices) the default table density. */
export function PreferencesScreen({ showDensity, onLanguageChanged, heading = true }: { showDensity: boolean; onLanguageChanged?: (locale: Locale) => void; heading?: boolean }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const q = useQuery(settingsQueries.preferences(api));
  return (
    <div className={styles.page} data-testid="preferences-screen">
      {heading ? <PageHead title={t('prefs.title')} lead={t('prefs.lead')} /> : null}
      {q.isPending ? <Loading height={320} /> : null}
      {q.isError ? <LoadError onRetry={() => void q.refetch()} /> : null}
      {q.data ? <PreferencesForm data={q.data} showDensity={showDensity} onLanguageChanged={onLanguageChanged} /> : null}
    </div>
  );
}

/**
 * Applies the signed-in person's saved preferences (time zone, date order, number style) to every formatter below it
 * and keeps the default table density in step. `apply` hands them to an app's own formatting module before the pages
 * render; the pages re-mount once when the preferences arrive.
 */
export function AccountFormatPreferences({ apply, children }: { apply?: (prefs: FormatPreferences) => void; children: ReactNode }) {
  const env = useOptionalAccountEnv();
  const q = useQuery({
    queryKey: settingsKeys.preferences(),
    queryFn: ({ signal }) => (env ? env.api.getAccountPreferences(signal) : Promise.reject(new Error('outside AccountProvider'))),
    enabled: env !== null,
    retry: false,
    staleTime: 5 * 60_000
  });
  const p = q.data;
  const prefs: FormatPreferences | undefined = p ? { timeZone: p.timeZone, dateFormat: p.dateFormat, numberFormat: p.numberFormat } : undefined;
  if (prefs) apply?.(prefs);
  const density = p?.tableDensity;
  useEffect(() => {
    if (density) writeDefaultDensity(density === 'COMPACT' ? 'compact' : 'comfortable');
  }, [density]);
  const signature = prefs ? `${prefs.timeZone ?? ''}|${prefs.dateFormat}|${prefs.numberFormat}` : 'default';
  return (
    <FormatPreferencesProvider prefs={prefs}>
      <Fragment key={signature}>{children}</Fragment>
    </FormatPreferencesProvider>
  );
}
