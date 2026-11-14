'use client';

import { sellerQueries } from '@afrotransact/api-client';
import { localeInfo } from '@afrotransact/i18n';
import { useI18n } from '@afrotransact/i18n/react';
import { cx } from '@afrotransact/ui';
import { CaretRight } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { PageHeader } from '@/components/ui/PageHeader';
import { type MessageKey, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';
import {
  documentsStatus,
  locationsStatus,
  payoutsStatus,
  pickupStatus,
  type SettingsKey,
  type SettingsStatus,
  shippingStatus,
  visibleSections
} from '@/lib/settings-sections';

type SectionCopy = Extract<MessageKey, `sc.settings.${SettingsKey}.${'title' | 'description'}`>;

const TONE = { good: 'text-success', warn: 'text-warning', neutral: 'text-muted' } as const;

/** One status per section, from what the section's own page saves (never invented): undefined while loading or unavailable. */
function useStatuses(): Partial<Record<SettingsKey, SettingsStatus>> {
  const api = sellerApi();
  const { orgId, level, isProvider } = useSeller();
  const { locale } = useI18n();
  const canOrders = level('orders') !== null && !isProvider;
  const shipFrom = useQuery({ ...sellerQueries.shipFrom(api, orgId), enabled: canOrders });
  const compliance = useQuery({ ...sellerQueries.compliance(api, orgId), enabled: level('account-health') !== null || level('payouts') !== null });
  const team = useQuery({ ...sellerQueries.team(api, orgId), enabled: level('team') !== null });
  const page = useQuery({ ...sellerQueries.businessPage(api, orgId), enabled: level('team') !== null });
  const onboarding = useQuery({ ...sellerQueries.onboarding(api, orgId), enabled: level('payouts') !== null });
  const out: Partial<Record<SettingsKey, SettingsStatus>> = {
    notifications: { key: 'sc.settings.status.notifications', tone: 'neutral' },
    language: { key: 'sc.settings.status.language', vars: { language: localeInfo(locale).name }, tone: 'neutral' }
  };
  if (shipFrom.data) {
    out.locations = locationsStatus(shipFrom.data);
    out.pickup = pickupStatus(shipFrom.data);
    out.shipping = shippingStatus(shipFrom.data);
  }
  if (compliance.data) {
    out.payouts = payoutsStatus(compliance.data);
    out.documents = documentsStatus(compliance.data);
  }
  if (team.data) out.team = { key: 'sc.settings.status.team', vars: { count: team.data.members.filter((m) => m.status === 'ACTIVE').length }, tone: 'neutral' };
  if (page.data) out.business = page.data.description && page.data.logoUrl ? { key: 'sc.settings.status.businessDone', tone: 'good' } : { key: 'sc.settings.status.businessTodo', tone: 'warn' };
  const tax = onboarding.data?.form.details;
  if (tax) out.tax = tax.taxIdMasked ? { key: 'sc.settings.status.taxOnFile', vars: { type: tax.taxIdType ?? '', last: tax.taxIdMasked.slice(-4) }, tone: 'good' } : { key: 'sc.settings.status.taxMissing', tone: 'warn' };
  return out;
}

/** Settings home: a flat list of sections, each with one line about it and its status. No cards. */
export function SettingsLanding() {
  const t = useT();
  const { level, isProvider } = useSeller();
  const statuses = useStatuses();
  const sections = visibleSections(isProvider, level);
  return (
    <>
      <PageHeader title={t('sc.settings.title')} description={t('sc.settings.description')} tour="page-settings" />
      <ul className="m-0 flex list-none flex-col p-0" data-testid="settings-list" data-tour="settings-list">
        {sections.map((s) => {
          const st = statuses[s.key];
          return (
            <li key={s.key} className="border-b border-border first:border-t">
              <Link href={s.href} className="flex min-h-14 items-center gap-4 py-3 text-text no-underline hover:bg-surface-hover hover:text-text hover:no-underline">
                <span className="flex min-w-0 flex-1 flex-col">
                  <strong className="text-md">{t(`sc.settings.${s.key}.title` as SectionCopy)}</strong>
                  <span className="text-sm text-muted">{t(`sc.settings.${s.key}.description` as SectionCopy)}</span>
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
    </>
  );
}
