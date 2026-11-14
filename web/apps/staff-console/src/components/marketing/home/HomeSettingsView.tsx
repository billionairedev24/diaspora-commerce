'use client';

import {
  HOME_BANDS,
  type HomeBand,
  type HomeCaps,
  type HomeDynamicMode,
  type HomeModuleRule,
  type HomeSettings,
  type HomeSettingsInput,
  homeSettingsProblems,
  type HomeSettingsVersion,
  MAX_REASON_LENGTH,
  staffHomeKeys,
  staffHomeQueries,
  type StaffLocale,
  type StaffLocaleLabels
} from '@afrotransact/api-client/staff-home';
import { Button, Dialog, DialogActions, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useMemo, useState } from 'react';

import { Toggle } from '@/components/preorders/primitives';
import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { MIN_REASON, ReasonField, reasonRule } from '@/components/ui/ReasonField';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { EN_MESSAGES, type MessageKey, type Translator, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';
import { useStaff } from '@/lib/session';

import { CommunityRegistry } from './CommunityRegistry';
import { CAP_KEYS, type CapKey, changesOf, cleanLabels, fieldsOf, inputOf, orderedModules, type RuleField } from './model';

const LOCALES: readonly StaffLocale[] = ['en', 'fr', 'es', 'ar', 'yo'];
const has = (k: string): k is MessageKey => Object.prototype.hasOwnProperty.call(EN_MESSAGES, k);

/** A label that can be specific to one module ("Providers needed" for Services) and falls back to the field's own. */
const fieldLabel = (t: Translator<MessageKey>, module: string, field: string): string => {
  const specific = `st.home.f.${module}.${field}`;
  return t(has(specific) ? specific : (`st.home.f.${field}` as MessageKey));
};

/** The rule of a module in plain words, from the values on screen. */
function ruleSentence(t: Translator<MessageKey>, key: string, r: HomeModuleRule): string | null {
  const k = `st.home.rule.${key}`;
  if (!has(k)) return null;
  return t(k, { products: r.minProducts, sellers: r.minSellers, units: r.minUnits, unitProducts: r.unitMinProducts, unitSellers: r.unitMinSellers, days: r.maxAgeDays });
}

/** Another language is set but English is empty: English is the fallback for every locale, so it is required. */
const englishMissing = (l: StaffLocaleLabels | undefined): boolean => !!l && [l.fr, l.es, l.ar, l.yo].some((v) => !!v?.trim()) && !(l.en ?? '').trim();

const num = (v: string): number => (v.trim() === '' ? 0 : Math.max(0, Math.trunc(Number(v))));

function NumberField({ id, label, value, onChange, min = 0, max, step = 1, disabled }: { id: string; label: string; value: number; onChange: (n: number) => void; min?: number; max?: number; step?: number; disabled?: boolean }) {
  return (
    <TextField
      id={id}
      label={label}
      type="number"
      inputMode={step < 1 ? 'decimal' : 'numeric'}
      min={min}
      max={max}
      step={step}
      value={String(value)}
      disabled={disabled}
      onChange={(e) => onChange(step < 1 ? Number(e.currentTarget.value || 0) : num(e.currentTarget.value))}
    />
  );
}

/** Marketing > Home settings (S417, S418): thresholds, caps, order bands and department labels of the dynamic home, with a reason for every change. */
export function HomeSettingsView() {
  const t = useT();
  const api = staffApi().home;
  const settings = useQuery(staffHomeQueries.settings(api));
  const departments = useQuery(staffHomeQueries.departments(api));
  if (settings.isPending) return <Spinner label={t('st.home.loading')} />;
  if (settings.isError) return <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />;
  // A saved version remounts the form, so what is on screen is always the stored settings.
  return <SettingsForm key={settings.data.version} settings={settings.data} departmentNames={Object.fromEntries((departments.data ?? []).map((d) => [d.key, d.name]))} departments={departments.data ?? []} />;
}

function SettingsForm({ settings, departments, departmentNames }: { settings: HomeSettings; departments: Array<{ key: string; name: string; count: number }>; departmentNames: Record<string, string> }) {
  const t = useT();
  const api = staffApi().home;
  const qc = useQueryClient();
  const toast = useToast();
  const { can } = useStaff();
  const canEdit = can('marketing', 'EDIT');
  const original = useMemo(() => inputOf(settings), [settings]);
  const [draft, setDraft] = useState<HomeSettingsInput>(() => inputOf(settings));
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);

  const changes = changesOf(original, { ...draft, departmentLabels: cleanLabels(draft.departmentLabels) }, settings.moduleNames, departmentNames);
  const rule = reasonRule(t, reason, MIN_REASON);
  const problems = homeSettingsProblems({ ...draft, departmentLabels: cleanLabels(draft.departmentLabels) }, reason);

  const save = useMutation({
    mutationFn: () => api.saveSettings({ ...draft, departmentLabels: cleanLabels(draft.departmentLabels) }, settings.version, reason),
    onSuccess: (next) => {
      qc.setQueryData(staffHomeKeys.settings(), next);
      void qc.invalidateQueries({ queryKey: staffHomeKeys.versions() });
      void qc.invalidateQueries({ queryKey: staffHomeKeys.all });
      toast.show(t('st.home.saved'));
    },
    onError: (e) => {
      toast.show(problemMessage(e), 'error');
      void qc.invalidateQueries({ queryKey: staffHomeKeys.settings() });
    }
  });

  const setRule = (key: string, patch: Partial<HomeModuleRule>) => setDraft((d) => ({ ...d, modules: { ...d.modules, [key]: { ...(d.modules[key] as HomeModuleRule), ...patch } } }));
  const setCap = (key: CapKey, value: number) => setDraft((d) => ({ ...d, caps: { ...d.caps, [key]: value } as HomeCaps }));
  const setLabel = (dept: string, locale: StaffLocale, value: string) =>
    setDraft((d) => ({ ...d, departmentLabels: { ...d.departmentLabels, [dept]: { ...(d.departmentLabels[dept] ?? { en: '' }), [locale]: value } as StaffLocaleLabels } }));

  const ready = canEdit && changes.length > 0 && rule.ok && problems.length === 0;
  const submit = () => {
    setSubmitted(true);
    if (ready) save.mutate();
  };
  const modeOptions = [
    { value: '', label: t('st.home.mode.default') },
    { value: 'off', label: t('st.home.mode.off') },
    { value: 'shadow', label: t('st.home.mode.shadow') },
    { value: 'on', label: t('st.home.mode.on') }
  ];
  const bandOptions = HOME_BANDS.map((b) => ({ value: b, label: t(`st.home.band.${b}` as MessageKey) }));
  const field = 'min-w-0';

  return (
    <div className="flex flex-col gap-5" data-testid="home-settings" data-tour="home-settings">
      <Card title={t('st.home.status.title')}>
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <StatusPill tone={settings.effectiveDynamic === 'on' ? 'good' : settings.effectiveDynamic === 'shadow' ? 'warn' : 'info'}>{t(`st.home.mode.${settings.effectiveDynamic}` as MessageKey)}</StatusPill>
          <span className="text-muted">
            {settings.updatedBy && settings.updatedAt ? t('st.home.status.changed', { name: settings.updatedBy, when: dateTime(settings.updatedAt) }) : t('st.home.status.defaults')}
          </span>
          <span className="text-muted">{t('st.home.status.version', { n: settings.version })}</span>
        </div>
        <fieldset disabled={!canEdit} className="m-0 max-w-md border-0 p-0">
          <Select label={t('st.home.mode.label')} help={t('st.home.mode.help')} options={modeOptions} value={draft.dynamic ?? ''} onChange={(e) => {
            const dynamic = (e.currentTarget.value || null) as HomeDynamicMode | null;
            setDraft((d) => ({ ...d, dynamic }));
          }} />
        </fieldset>
      </Card>

      <section aria-labelledby="home-modules" className="flex flex-col gap-3">
        <div className="flex flex-col gap-1">
          <h2 id="home-modules" className="m-0 text-md font-semibold">
            {t('st.home.modules.title')}
          </h2>
          <p className="m-0 text-sm text-muted">{t('st.home.modules.help')}</p>
        </div>
        {orderedModules(settings).map((key) => {
          const r = draft.modules[key] as HomeModuleRule;
          const name = settings.moduleNames[key] ?? key;
          const sentence = ruleSentence(t, key, r);
          const fields = fieldsOf(key);
          return (
            <Card key={key} className="scroll-mt-24">
              <fieldset disabled={!canEdit} className="m-0 flex flex-col gap-3 border-0 p-0">
                <legend className="sr-only">{name}</legend>
                <div id={`rule-${key}`} className="flex flex-wrap items-center gap-3">
                  <Toggle on={r.enabled} label={t('st.home.module.enabled', { name })} onClick={() => setRule(key, { enabled: !r.enabled })} />
                  <h3 className="m-0 flex-1 text-sm font-bold">{name}</h3>
                  <StatusPill tone="info">{t(`st.home.band.${(r.band ?? 'discovery') as HomeBand}` as MessageKey)}</StatusPill>
                </div>
                {sentence ? <p className="m-0 text-sm text-muted">{sentence}</p> : null}
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
                  {fields.map((f: RuleField) => (
                    <div key={f} className={field}>
                      <NumberField id={`${key}-${f}`} label={`${name}: ${fieldLabel(t, key, f)}`} value={r[f]} onChange={(n) => setRule(key, { [f]: n })} />
                    </div>
                  ))}
                  <div className={field}>
                    <Select id={`${key}-band`} label={`${name}: ${t('st.home.f.band')}`} options={bandOptions} value={r.band ?? 'discovery'} onChange={(e) => setRule(key, { band: e.currentTarget.value as HomeBand })} />
                  </div>
                  <div className={field}>
                    <NumberField id={`${key}-weight`} label={`${name}: ${t('st.home.f.weight')}`} value={r.weight} max={100} onChange={(n) => setRule(key, { weight: n })} />
                  </div>
                  {r.show !== undefined ? (
                    <div className={field}>
                      <NumberField id={`${key}-show`} label={`${name}: ${t('st.home.f.show')}`} value={r.show} min={1} max={24} onChange={(n) => setRule(key, { show: n })} />
                    </div>
                  ) : null}
                </div>
              </fieldset>
            </Card>
          );
        })}
      </section>

      <Card title={t('st.home.caps.title')}>
        <p className="m-0 text-sm text-muted">{t('st.home.caps.help')}</p>
        <fieldset disabled={!canEdit} className="m-0 grid grid-cols-1 gap-3 border-0 p-0 sm:grid-cols-2 lg:grid-cols-4">
          <legend className="sr-only">{t('st.home.caps.title')}</legend>
          {CAP_KEYS.map((k) => (
            <NumberField key={k} id={`cap-${k}`} label={t(`st.home.cap.${k}` as MessageKey)} value={draft.caps[k]} step={k === 'noveltyPenalty' ? 0.05 : 1} max={k === 'noveltyPenalty' ? 1 : undefined} onChange={(n) => setCap(k, n)} />
          ))}
        </fieldset>
      </Card>

      <Card title={t('st.home.labels.title')}>
        <p className="m-0 text-sm text-muted">{t('st.home.labels.help')}</p>
        {departments.length === 0 ? (
          <p className="m-0 text-sm text-muted">{t('st.home.labels.none')}</p>
        ) : (
          <fieldset disabled={!canEdit} className="m-0 overflow-x-auto border-0 p-0">
            <legend className="sr-only">{t('st.home.labels.title')}</legend>
            <table className="w-full min-w-[720px] border-collapse text-sm">
              <thead>
                <tr className="text-start text-xs text-muted">
                  <th scope="col" className="p-2 text-start font-semibold">
                    {t('st.home.labels.department')}
                  </th>
                  {LOCALES.map((l) => (
                    <th key={l} scope="col" className="p-2 text-start font-semibold">
                      {t(`st.home.locale.${l}` as MessageKey)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {departments.map((d) => (
                  <tr key={d.key} className="border-t border-border">
                    <th scope="row" className="p-2 text-start font-semibold">
                      {d.name}
                      <span className="block text-xs font-normal text-muted">{t('st.home.labels.count', { n: d.count })}</span>
                      {englishMissing(draft.departmentLabels[d.key]) ? (
                        <span className="block text-xs font-semibold text-danger" role="alert">
                          {t('st.home.labels.englishFirst')}
                        </span>
                      ) : null}
                    </th>
                    {LOCALES.map((l) => (
                      <td key={l} className="p-1">
                        <input
                          aria-invalid={l === 'en' && englishMissing(draft.departmentLabels[d.key]) ? true : undefined}
                          aria-label={`${d.name}, ${t(`st.home.locale.${l}` as MessageKey)}`}
                          lang={l}
                          dir={l === 'ar' ? 'rtl' : 'ltr'}
                          maxLength={80}
                          className="h-9 w-full rounded-sm border border-border-strong bg-ink-0 px-2 text-sm"
                          value={draft.departmentLabels[d.key]?.[l] ?? ''}
                          onChange={(e) => setLabel(d.key, l, e.currentTarget.value)}
                        />
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </fieldset>
        )}
      </Card>

      <Card title={t('st.home.schedule.title')}>
        <p className="m-0 text-sm text-muted">{t('st.home.schedule.help')}</p>
        <div className="flex flex-wrap gap-4 text-sm">
          <Link href="?tab=placements">{t('st.home.schedule.placements')}</Link>
          <Link href="?tab=layout">{t('st.home.schedule.layout')}</Link>
          <Link href="?tab=preview">{t('st.home.schedule.preview')}</Link>
        </div>
      </Card>

      <Card title={t('st.home.save.title')}>
        {!canEdit ? (
          <Notice tone="info" icon="eye" title={t('st.home.save.readOnly')} />
        ) : (
          <>
            {changes.length === 0 ? (
              <p className="m-0 text-sm text-muted">{t('st.home.save.nothing')}</p>
            ) : (
              <div className="flex flex-col gap-1">
                <strong className="text-sm">{t('st.home.save.changes', { n: changes.length })}</strong>
                <ul className="m-0 flex list-disc flex-col gap-0.5 ps-5 text-sm" data-testid="home-changes">
                  {changes.map((c, i) => (
                    <li key={`${c.area}-${c.subject}-${c.field}-${i}`}>{describeChange(t, c)}</li>
                  ))}
                </ul>
              </div>
            )}
            <ReasonField
              id="home-reason"
              label={t('st.home.save.reason')}
              value={reason}
              onChange={setReason}
              min={MIN_REASON}
              max={MAX_REASON_LENGTH}
              single
              help={t('st.home.save.reasonHelp')}
              error={submitted && !rule.ok ? t('st.home.save.reasonNeeded') : null}
            />
            <div className="flex flex-wrap items-center gap-3">
              <Button type="button" onClick={submit} disabled={!ready || save.isPending}>
                {t('st.home.save.button')}
              </Button>
              <Button type="button" variant="neutral" onClick={() => setDraft(inputOf(settings))} disabled={changes.length === 0}>
                {t('st.home.save.discard')}
              </Button>
            </div>
          </>
        )}
      </Card>

      <CommunityRegistry canEdit={canEdit} />
      <History canEdit={canEdit} currentVersion={settings.version} />
    </div>
  );
}

function describeChange(t: Translator<MessageKey>, c: ReturnType<typeof changesOf>[number]): string {
  const to = c.to === '' ? t('st.home.change.empty') : c.to;
  const from = c.from === '' ? t('st.home.change.empty') : c.from;
  if (c.area === 'mode') return t('st.home.change.mode', { from: modeWord(t, c.from), to: modeWord(t, c.to) });
  if (c.area === 'module') return t('st.home.change.module', { subject: c.subject, field: fieldWord(t, c.module ?? '', c.field), from, to });
  if (c.area === 'cap') return t('st.home.change.cap', { field: t(`st.home.cap.${c.field}` as MessageKey), from, to });
  return t('st.home.change.label', { subject: c.subject, locale: t(`st.home.locale.${c.field}` as MessageKey), from, to });
}
const modeWord = (t: Translator<MessageKey>, v: string) => t(`st.home.mode.${v === 'default' || v === '' ? 'default' : v}` as MessageKey);
const fieldWord = (t: Translator<MessageKey>, module: string, f: string) => {
  const k = `st.home.f.${module}.${f}`;
  const g = `st.home.f.${f}`;
  return has(k) ? t(k) : has(g) ? t(g) : f;
};

/** Who changed what, when and why, newest first, with a way back to any earlier version (a new version with its own reason). */
function History({ canEdit, currentVersion }: { canEdit: boolean; currentVersion: number }) {
  const t = useT();
  const api = staffApi().home;
  const qc = useQueryClient();
  const toast = useToast();
  const versions = useQuery(staffHomeQueries.versions(api));
  const [target, setTarget] = useState<HomeSettingsVersion | null>(null);
  const [reason, setReason] = useState('');
  const revert = useMutation({
    mutationFn: (v: number) => api.revert(v, reason),
    onSuccess: (next) => {
      qc.setQueryData(staffHomeKeys.settings(), next);
      void qc.invalidateQueries({ queryKey: staffHomeKeys.all });
      toast.show(t('st.home.history.reverted'));
      setTarget(null);
      setReason('');
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const rule = reasonRule(t, reason, MIN_REASON);
  return (
    <Card title={t('st.home.history.title')}>
      {versions.isPending ? (
        <Spinner label={t('st.home.loading')} />
      ) : versions.isError ? (
        <ErrorState error={versions.error} onRetry={() => void versions.refetch()} />
      ) : versions.data.length === 0 ? (
        <p className="m-0 text-sm text-muted">{t('st.home.history.none')}</p>
      ) : (
        <ol className="m-0 flex list-none flex-col gap-2 p-0" data-testid="home-history">
          {versions.data.map((v) => (
            <li key={v.version} className="flex flex-wrap items-baseline gap-x-3 gap-y-1 border-b border-border pb-2 text-sm">
              <strong>{t('st.home.history.version', { n: v.version })}</strong>
              <span className="text-muted">{t('st.home.history.by', { name: v.changedBy, when: dateTime(v.changedAt) })}</span>
              <span className="min-w-0 flex-1">{v.reason}</span>
              {v.revertOf != null ? <StatusPill tone="info">{t('st.home.history.revertOf', { n: v.revertOf })}</StatusPill> : null}
              {canEdit && v.version !== currentVersion ? (
                <Button type="button" size="sm" variant="neutral" onClick={() => setTarget(v)}>
                  {t('st.home.history.restore')}
                </Button>
              ) : null}
            </li>
          ))}
        </ol>
      )}
      <Dialog open={target !== null} onOpenChange={(o) => !o && setTarget(null)} title={t('st.home.history.restoreTitle', { n: target?.version ?? 0 })} description={t('st.home.history.restoreHelp')} closeLabel={t('st.home.close')}>
        <ReasonField label={t('st.home.save.reason')} value={reason} onChange={setReason} single min={MIN_REASON} max={MAX_REASON_LENGTH} />
        <DialogActions>
          <Button type="button" variant="neutral" onClick={() => setTarget(null)}>
            {t('st.home.cancel')}
          </Button>
          <Button type="button" disabled={!rule.ok || revert.isPending} onClick={() => target && revert.mutate(target.version)}>
            {t('st.home.history.restoreButton')}
          </Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}
