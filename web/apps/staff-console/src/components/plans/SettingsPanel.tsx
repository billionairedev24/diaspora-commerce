'use client';

import { type StaffPlanFeeMode, staffPlanKeys, staffPlanQueries } from '@afrotransact/api-client';
import { Button, Select, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { Card } from '@/components/ui/Card';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { dateTime } from '@/lib/format';

import { plansApi, usePlanRights } from './kit';

/**
 * Plans & billing › Settings (S298): whether sellers may pay their plan from their sales (the deployment's kill switch,
 * read-only here) and how new sellers pay unless they choose on the onboarding Plan step (plans:manage).
 */
export function SettingsPanel() {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { manage } = usePlanRights();
  const q = useQuery(staffPlanQueries.settings(plansApi()));
  const [draft, setDraft] = useState<StaffPlanFeeMode | null>(null);
  const save = useMutation({
    mutationFn: (mode: StaffPlanFeeMode) => plansApi().updateBillingSettings(mode),
    onSuccess: (s) => {
      qc.setQueryData(staffPlanKeys.settings(), s);
      setDraft(null);
      toast.show(t('st.plans.settings.saved'));
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const s = q.data;
  const value = draft ?? s.planFeeCollectionDefault;
  return (
    <Card title={t('st.plans.settings.title')}>
      <div className="flex flex-col gap-4 text-sm">
        <div className="flex flex-col gap-1" data-testid="kill-switch">
          <span className="text-xs font-semibold text-muted">{t('st.plans.settings.killSwitch')}</span>
          <span className="flex items-center gap-2">
            <StatusPill tone={s.earningsCollectionEnabled ? 'good' : 'warn'}>{s.earningsCollectionEnabled ? t('st.plans.settings.on') : t('st.plans.settings.off')}</StatusPill>
          </span>
          <span className="text-xs text-muted">{t('st.plans.settings.killSwitchHelp')}</span>
        </div>
        <form className="flex max-w-xl flex-col gap-2" onSubmit={(e) => { e.preventDefault(); save.mutate(value); }}>
          <Select id="settings-plan-fee-default" label={t('st.plans.settings.default')} value={value} disabled={!manage}
            help={t('st.plans.settings.defaultHelp')} onChange={(e) => setDraft(e.currentTarget.value as StaffPlanFeeMode)}
            options={(['EARNINGS_FIRST', 'CARD'] as const).map((m) => ({ value: m, label: t(`st.plans.planFee.${m}`) }))} />
          {s.updatedAt ? <span className="text-xs text-muted">{t('st.plans.settings.updated', { who: s.updatedBy ?? '—', date: dateTime(s.updatedAt) })}</span> : null}
          {manage ? (
            <div>
              <Button type="submit" disabled={value === s.planFeeCollectionDefault} loading={save.isPending}>{t('st.plans.settings.save')}</Button>
            </div>
          ) : null}
        </form>
      </div>
    </Card>
  );
}
