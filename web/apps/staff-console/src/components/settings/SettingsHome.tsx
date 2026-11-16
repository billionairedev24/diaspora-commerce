'use client';

import { staffAccessQueries, staffAiSpendQueries, staffMarketQueries, staffTaxQueries, usdTextFromMicros } from '@afrotransact/api-client';
import { localeInfo } from '@afrotransact/i18n';
import { useI18n } from '@afrotransact/i18n/react';
import { cx } from '@afrotransact/ui';
import { CaretRight } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { PageHeader } from '@/components/ui/PageHeader';
import { type MessageKey, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { useStaff } from '@/lib/session';
import { canEditSection, levelOn, SETTINGS_GROUPS, type SettingsKey, type SettingsSection, type SettingsStatus, visibleSections } from '@/lib/settings-sections';

const TONE = { good: 'text-success', warn: 'text-warning', neutral: 'text-muted' } as const;

/** One status per section, from saved state only (never invented): undefined while loading, unavailable, or when there is nothing to say. */
function useStatuses(sections: SettingsSection[]): Partial<Record<SettingsKey, SettingsStatus>> {
  const api = staffApi();
  const { session } = useStaff();
  const { locale } = useI18n();
  const has = (k: SettingsKey) => sections.some((s) => s.key === k);
  const roles = useQuery({ ...staffAccessQueries.roles(api.access, 'platform'), enabled: has('access') });
  const countries = useQuery({ ...staffMarketQueries.countries(api.markets), enabled: has('markets') });
  const review = useQuery({ ...staffTaxQueries.reviewSettings(api.tax), enabled: has('tax-review') });
  const aiCap = useQuery({ ...staffAiSpendQueries.cap(api.aiSpend), enabled: has('ai') });
  const out: Partial<Record<SettingsKey, SettingsStatus>> = {};
  if (has('language')) out.language = { key: 'st.sx.status.language', vars: { language: localeInfo(locale).name }, tone: 'neutral' };
  // the cap in force, read from the AI service (never a number typed here); nothing is said while it loads or cannot be read
  if (aiCap.data) out.ai = aiCap.data.capMicros === 0 ? { key: 'st.sx.status.aiOff', tone: 'warn' } : { key: 'st.sx.status.ai', vars: { amount: usdTextFromMicros(aiCap.data.capMicros) }, tone: 'neutral' };
  if (roles.data) out.access = { key: 'st.sx.status.access', vars: { count: roles.data.items.length }, tone: 'neutral' };
  if (countries.data) {
    const live = countries.data.items.filter((c) => c.status === 'LIVE').length;
    out.markets = live === 0 ? { key: 'st.sx.status.marketsNone', tone: 'warn' } : { key: 'st.sx.status.markets', vars: { count: live }, tone: 'good' };
  }
  if (review.data) out['tax-review'] = review.data.mode === 'RISK_BASED' ? { key: 'st.sx.status.taxRisk', tone: 'neutral' } : { key: 'st.sx.status.taxAll', tone: 'neutral' };
  // Everything else says whether the staff member can only look.
  sections.forEach((s) => {
    if (out[s.key] === undefined && s.own && !canEditSection(session, s) && levelOn(session, s) !== null) out[s.key] = { key: 'st.sx.status.viewOnly', tone: 'neutral' };
  });
  return out;
}

/** Settings home: a flat list of sections, each with one line about it and its status. No cards, no second sidebar. */
export function SettingsHome() {
  const t = useT();
  const { session } = useStaff();
  const sections = visibleSections(session);
  const statuses = useStatuses(sections);
  return (
    <>
      <PageHeader title={t('st.sx.title')} description={t('st.sx.description')} tour="settings-hub" />
      <div className="flex flex-col gap-6" data-testid="settings-list" data-tour="settings-list">
        {SETTINGS_GROUPS.map((g) => {
          const items = sections.filter((s) => s.group === g.key);
          if (items.length === 0) return null;
          return (
            <section key={g.key} aria-labelledby={`settings-group-${g.key}`} className="flex flex-col">
              <h2 id={`settings-group-${g.key}`} className="m-0 pb-2 text-2xs font-bold tracking-[0.08em] text-muted uppercase">
                {t(g.labelKey)}
              </h2>
              <ul className="m-0 flex list-none flex-col p-0">
                {items.map((s) => {
                  const st = statuses[s.key];
                  return (
                    <li key={s.key} className="border-b border-border first:border-t">
                      <Link
                        href={s.href}
                        data-tour={`settings-card-${s.key}`}
                        data-testid={`settings-row-${s.key}`}
                        className="flex min-h-14 items-center gap-4 py-3 text-text no-underline hover:bg-surface-hover hover:text-text hover:no-underline"
                      >
                        <span className="flex min-w-0 flex-1 flex-col">
                          <strong className="text-md">{t(`st.sx.${s.key}.title` as MessageKey)}</strong>
                          <span className="text-sm text-muted">{t(`st.sx.${s.key}.description` as MessageKey)}</span>
                        </span>
                        {st ? (
                          <span className={cx('max-w-[40%] text-end text-sm font-semibold', TONE[st.tone])} data-testid={`settings-status-${s.key}`}>
                            {t(st.key, st.vars)}
                          </span>
                        ) : null}
                        <CaretRight size={16} aria-hidden className="flex-none text-muted rtl:rotate-180" />
                      </Link>
                    </li>
                  );
                })}
              </ul>
            </section>
          );
        })}
      </div>
    </>
  );
}
