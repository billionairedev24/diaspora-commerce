'use client';

import { type AccountPreferences, DATE_FORMATS, NUMBER_FORMATS, settingsKeys, settingsQueries, TABLE_DENSITIES } from '@afrotransact/api-client';
import { writeDefaultDensity } from '@afrotransact/data-table';
import { createFormatters, type FormatPreferences, intlTag, isLocale, type Locale, localeCookie, LOCALES, matchLocale } from '@afrotransact/i18n';
import { FormatPreferencesProvider, useI18n } from '@afrotransact/i18n/react';
import { Button, Select, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Fragment, type ReactNode, useEffect, useMemo, useState } from 'react';

import { useAccountEnv, useOptionalAccountEnv } from './context';
import { type AccountMessageKey, useAccountT } from './i18n/t';
import { Card, errorText, LoadError, Loading, PageHead, styles } from './parts';

const SAMPLE_DATE = '2026-10-31T18:45:00Z';
const SAMPLE_NUMBER = 1234567.89;

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

function Choices<T extends string>({ legend, name, values, value, onChange, title, sub }: {
  legend: string;
  name: string;
  values: readonly T[];
  value: T;
  onChange: (v: T) => void;
  title: (v: T) => string;
  sub?: (v: T) => ReactNode;
}) {
  return (
    <fieldset className={styles.choices}>
      <legend>{legend}</legend>
      {values.map((v) => (
        <label key={v} className={styles.choice}>
          <input type="radio" name={name} value={v} checked={value === v} onChange={() => onChange(v)} />
          <span className={styles.choiceTitle}>{title(v)}</span>
          {sub ? <span className={styles.choiceSub}>{sub(v)}</span> : null}
        </label>
      ))}
    </fieldset>
  );
}

function PreferencesForm({ data, showDensity, onLanguageChanged }: { data: AccountPreferences; showDensity: boolean; onLanguageChanged?: (locale: Locale) => void }) {
  const t = useAccountT();
  const { api } = useAccountEnv();
  const { locale: uiLocale } = useI18n();
  const qc = useQueryClient();
  const toast = useToast();
  const [language, setLanguage] = useState<Locale>(() => (data.locale ? (matchLocale([data.locale]) ?? uiLocale) : uiLocale));
  const [zone, setZone] = useState(data.timeZone ?? '');
  const [date, setDate] = useState(data.dateFormat);
  const [num, setNum] = useState(data.numberFormat);
  const [density, setDensity] = useState(data.tableDensity);
  const [error, setError] = useState<string | null>(null);
  const device = useMemo(deviceZone, []);
  const zoneOptions = useMemo(() => [{ value: '', label: t('prefs.timeZoneDevice', { zone: device }) }, ...zones().map((z) => ({ value: z, label: z.replace(/_/g, ' ') }))], [t, device]);
  const sample = (d: AccountPreferences['dateFormat'], n: AccountPreferences['numberFormat']) => createFormatters(language, { timeZone: zone || null, dateFormat: d, numberFormat: n });
  const save = useMutation({
    mutationFn: () => api.saveAccountPreferences({ locale: intlTag(language), timeZone: zone || null, dateFormat: date, numberFormat: num, tableDensity: density }),
    onSuccess: (saved) => {
      qc.setQueryData(settingsKeys.preferences(), saved);
      writeDefaultDensity(saved.tableDensity === 'COMPACT' ? 'compact' : 'comfortable');
      setError(null);
      toast.show(t('prefs.saved'));
      if (language !== uiLocale) {
        document.cookie = localeCookie(language);
        onLanguageChanged?.(language);
      }
    },
    onError: (e) => setError(errorText(t, e, 'timeZone'))
  });
  return (
    <Card id="preferences" testId="preferences" title={t('prefs.title')} lead={t('prefs.lead')}>
      <form className={`${styles.form} ${styles.formFirst}`} onSubmit={(e) => { e.preventDefault(); save.mutate(); }} noValidate>
        <div className={styles.grid2}>
          <Select
            label={t('prefs.language')}
            help={t('prefs.languageHelp')}
            value={language}
            onChange={(e) => isLocale(e.target.value) && setLanguage(e.target.value)}
            options={LOCALES.map((l) => ({ value: l.code, label: l.code === 'en' ? l.name : `${l.name} · ${l.english}` }))}
          />
          <Select label={t('prefs.timeZone')} help={t('prefs.timeZoneHelp')} value={zone} onChange={(e) => setZone(e.target.value)} options={zoneOptions} error={error} />
        </div>
        <Choices
          legend={t('prefs.dateFormat')}
          name="dateFormat"
          values={DATE_FORMATS}
          value={date}
          onChange={setDate}
          title={(v) => t(`prefs.date.${v}` as AccountMessageKey)}
          sub={(v) => t('prefs.example', { value: sample(v, num).date(SAMPLE_DATE, 'short') })}
        />
        <Choices
          legend={t('prefs.numberFormat')}
          name="numberFormat"
          values={NUMBER_FORMATS}
          value={num}
          onChange={setNum}
          title={(v) => (v === 'AUTO' ? t('prefs.number.AUTO') : t(`prefs.number.${v}` as AccountMessageKey))}
          sub={(v) => t('prefs.example', { value: sample(date, v).money(SAMPLE_NUMBER * 100) })}
        />
        {showDensity ? (
          <>
            <Choices legend={t('prefs.density')} name="tableDensity" values={TABLE_DENSITIES} value={density} onChange={setDensity} title={(v) => t(`prefs.density.${v}` as AccountMessageKey)} />
            <p className={styles.muted}>{t('prefs.densityHelp')}</p>
          </>
        ) : null}
        <div className={`${styles.actions} ${styles.actionsEnd}`}>
          <Button type="submit" loading={save.isPending}>
            {t('common.save')}
          </Button>
        </div>
      </form>
    </Card>
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
      {q.isError ? (
        <div className={styles.card}>
          <LoadError onRetry={() => void q.refetch()} />
        </div>
      ) : null}
      {q.data ? <PreferencesForm key={JSON.stringify(q.data)} data={q.data} showDensity={showDensity} onLanguageChanged={onLanguageChanged} /> : null}
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
