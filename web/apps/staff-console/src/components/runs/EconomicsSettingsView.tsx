'use client';

import {
  ECONOMICS_LIMITS,
  type EconomicsChange,
  type EconomicsField,
  economicsInputProblems,
  type EconomicsNumbers,
  type EconomicsSettings,
  PLATFORM_FEE_MAX_MINOR,
  runBreakEven,
  runCostModel,
  staffRunKeys,
  staffRunQueries
} from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, Checkbox, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { ErrorState, Spinner } from '@/components/ui/States';
import { type MessageKey, type Translator, useT } from '@/i18n/t';

import { CARD, ReasonDialog, useRunsApi } from './shared';

type T = Translator<MessageKey>;

/** Money fields are typed in major units ("25.00"), time fields in minutes ("6.5"). */
type Draft = Record<Exclude<EconomicsField, 'reason' | 'roundTrip'>, string> & { roundTrip: boolean };

const MONEY_FIELDS = ['driverCostPerHourMinor', 'driverCostPerMileMinor'] as const;
type MinuteField = 'collectionMinutes' | 'perStopMinutes' | 'pickupPointStopMinutes' | 'handoverMinutesPerOrder';

const majorText = (minor: number): string => `${Math.floor(minor / 100)}.${String(minor % 100).padStart(2, '0')}`;

const draftOf = (s: EconomicsNumbers): Draft => ({
  driverCostPerHourMinor: majorText(s.driverCostPerHourMinor),
  driverCostPerMileMinor: majorText(s.driverCostPerMileMinor),
  collectionMinutes: String(s.collectionMinutes),
  perStopMinutes: String(s.perStopMinutes),
  pickupPointStopMinutes: String(s.pickupPointStopMinutes),
  handoverMinutesPerOrder: String(s.handoverMinutesPerOrder),
  platformFeePerOrderMinor: majorText(s.platformFeePerOrderMinor),
  roundTrip: s.roundTrip
});

/** An amount people type ("25", "25.5", "$0.70") to minor units; NaN when it is not an amount. */
function parseMajor(text: string): number {
  const m = /^\$?\s*(\d{1,4})(?:\.(\d{0,2}))?$/.exec(text.trim());
  return m ? Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0')) : Number.NaN;
}

function parseMinutes(text: string): number {
  return /^\d{1,4}(?:\.\d{1,2})?$/.test(text.trim()) ? Number(text.trim()) : Number.NaN;
}

/** The numbers a draft describes (NaN where a field is not a number), for the checks and for the BFF. */
function numbersOf(d: Draft): Record<keyof typeof ECONOMICS_LIMITS | 'platformFeePerOrderMinor', number> & { roundTrip: boolean } {
  return {
    driverCostPerHourMinor: parseMajor(d.driverCostPerHourMinor),
    driverCostPerMileMinor: parseMajor(d.driverCostPerMileMinor),
    collectionMinutes: parseMinutes(d.collectionMinutes),
    perStopMinutes: parseMinutes(d.perStopMinutes),
    pickupPointStopMinutes: parseMinutes(d.pickupPointStopMinutes),
    handoverMinutesPerOrder: parseMinutes(d.handoverMinutesPerOrder),
    // Blank means no estimate (0).
    platformFeePerOrderMinor: d.platformFeePerOrderMinor.trim() === '' ? 0 : parseMajor(d.platformFeePerOrderMinor),
    roundTrip: d.roundTrip
  };
}

function problemText(t: T, kind: 'required' | 'range' | 'whole' | 'tooLong', field: keyof typeof ECONOMICS_LIMITS, money: (minor: number) => string): string {
  const { min, max } = ECONOMICS_LIMITS[field];
  const isMoney = (MONEY_FIELDS as readonly string[]).includes(field);
  if (kind === 'required') return t(isMoney ? 'st.runs.settings.problem.notAmount' : 'st.runs.settings.problem.notMinutes');
  return t('st.runs.settings.problem.range', { min: isMoney ? money(min) : String(min), max: isMoney ? money(max) : String(max) });
}

/** What the draft would change for a location: break-even before and after, at its own route and fee. */
function Effect({ draft, valid }: { draft: EconomicsNumbers; valid: boolean }) {
  const t = useT();
  const fmt = useFormatters();
  const api = useRunsApi();
  const locations = useQuery(staffRunQueries.locations(api));
  const [chosen, setChosen] = useState('');
  const items = locations.data?.items ?? [];
  const locationId = chosen || items[0]?.id || '';
  const mode0 = items.find((l) => l.id === locationId)?.dropMode ?? 'DOORSTEP';
  const now = useQuery({
    queryKey: [...staffRunKeys.all, 'effect', locationId],
    queryFn: ({ signal }) => api.previewRun({ locationId, minOrders: 12, dropMode: mode0 }, signal),
    enabled: locationId !== ''
  });
  if (locations.isPending) return null;
  if (items.length === 0) return null;
  const e = now.data;
  const after = e && valid ? runCostModel(draft, e.route, mode0) : null;
  const afterBreakEven = e && after ? runBreakEven(e.feeMinor, after) : null;
  return (
    <section aria-labelledby="runs-effect-title" className={CARD} data-testid="runs-effect">
      <div className="flex flex-col gap-1">
        <h3 id="runs-effect-title" className="m-0 text-md font-bold">
          {t('st.runs.settings.effect.title')}
        </h3>
        <p className="m-0 text-xs text-muted">{t('st.runs.settings.effect.help')}</p>
      </div>
      <Select
        label={t('st.runs.settings.effect.location')}
        value={locationId}
        onChange={(ev) => setChosen(ev.currentTarget.value)}
        options={items.map((l) => ({ value: l.id, label: l.name }))}
        className="max-w-xs"
      />
      {now.isPending ? <Spinner /> : null}
      {e ? (
        <dl className="m-0 grid gap-x-6 gap-y-1 text-sm sm:grid-cols-[auto_1fr]">
          <dt className="text-muted">{t('st.runs.settings.effect.fee')}</dt>
          <dd className="m-0">{fmt.money(e.feeMinor, e.currency)}</dd>
          <dt className="text-muted">{t('st.runs.settings.effect.saved')}</dt>
          <dd className="m-0" data-testid="effect-saved">
            {e.breakEvenOrders === null ? t('st.runs.breakEven.never') : t('st.runs.breakEven.at', { n: e.breakEvenOrders })}
          </dd>
          <dt className="text-muted">{t('st.runs.settings.effect.draft')}</dt>
          <dd className="m-0 font-semibold" data-testid="effect-draft">
            {!valid ? t('st.runs.settings.effect.invalid') : after && afterBreakEven === null ? t('st.runs.breakEven.never') : t('st.runs.breakEven.at', { n: afterBreakEven ?? 0 })}
          </dd>
        </dl>
      ) : null}
    </section>
  );
}

function History({ changes }: { changes: EconomicsChange[] }) {
  const t = useT();
  const fmt = useFormatters();
  const money = (m: number) => fmt.money(m, 'USD');
  const line = (n: EconomicsNumbers) =>
    t('st.runs.settings.summary', {
      hour: money(n.driverCostPerHourMinor),
      mile: money(n.driverCostPerMileMinor),
      sweep: n.collectionMinutes,
      stop: n.perStopMinutes,
      point: n.pickupPointStopMinutes,
      handover: n.handoverMinutesPerOrder,
      trip: n.roundTrip ? t('st.runs.settings.trip.round') : t('st.runs.settings.trip.oneWay')
    }) + (n.platformFeePerOrderMinor > 0 ? `, ${t('st.runs.settings.summary.platform', { fee: money(n.platformFeePerOrderMinor) })}` : '');
  return (
    <section aria-labelledby="runs-history-title" className={CARD}>
      <h3 id="runs-history-title" className="m-0 text-md font-bold">
        {t('st.runs.settings.history.title')}
      </h3>
      {changes.length === 0 ? <p className="m-0 text-sm text-muted">{t('st.runs.settings.history.empty')}</p> : null}
      {changes.length > 0 ? (
        <ul aria-label={t('st.runs.settings.history.title')} className="m-0 flex list-none flex-col gap-2 p-0">
          {changes.map((c) => (
            <li key={c.id ?? c.version} className="flex flex-col gap-0.5 border-b border-border pb-2 text-xs">
              <strong>{t('st.runs.settings.history.line', { version: c.version, who: c.actor, at: fmt.dateTime(c.at) })}</strong>
              <span>{t('st.runs.settings.history.before', { numbers: line(c.before) })}</span>
              <span>{t('st.runs.settings.history.after', { numbers: line(c.after) })}</span>
              <span className="text-muted">{t('st.runs.settings.meta.reason', { reason: c.reason })}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}

function Editor({ settings }: { settings: EconomicsSettings }) {
  const t = useT();
  const fmt = useFormatters();
  const toast = useToast();
  const qc = useQueryClient();
  const api = useRunsApi();
  const initial = useMemo(() => draftOf(settings), [settings]);
  const [d, setD] = useState<Draft>(initial);
  const [shown, setShown] = useState(false);
  const [asking, setAsking] = useState(false);
  const canManage = settings.can.manage;
  const money = (m: number) => fmt.money(m, 'USD');
  const nums = numbersOf(d);
  const problems = economicsInputProblems({ ...nums, reason: 'x' });
  const valid = Object.keys(problems).length === 0;
  const dirty = JSON.stringify(d) !== JSON.stringify(initial);
  const set = (patch: Partial<Draft>) => {
    setD((cur) => ({ ...cur, ...patch }));
    setShown(true);
  };
  const err = (f: keyof typeof ECONOMICS_LIMITS) => {
    const p = shown ? problems[f] : undefined;
    return p ? problemText(t, p, f, money) : null;
  };
  const platformProblem = shown && problems.platformFeePerOrderMinor ? t('st.runs.settings.problem.range', { min: money(0), max: money(PLATFORM_FEE_MAX_MINOR) }) : null;
  const save = useMutation({
    mutationFn: ({ reason }: { reason: string }) => api.updateSettings({ ...nums, reason }, settings.version),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: staffRunKeys.all });
      toast.show(t('st.runs.settings.toast.saved'));
    },
    onError: () => void qc.invalidateQueries({ queryKey: staffRunKeys.all })
  });
  const draftNumbers: EconomicsNumbers = { ...nums };
  const field = (f: (typeof MONEY_FIELDS)[number] | MinuteField, label: MessageKey, help: MessageKey) => (
    <TextField label={t(label)} help={t(help)} inputMode="decimal" value={d[f]} error={err(f)} onChange={(e) => set({ [f]: e.currentTarget.value })} />
  );
  return (
    <div className="flex flex-col gap-4" data-testid="run-economics">
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.runs.settings.intro')}</p>
      {!canManage ? (
        <p role="note" className="m-0 rounded-md bg-ink-50 px-3 py-2 text-xs text-muted" data-testid="economics-read-only">
          {t('st.runs.settings.readOnly')}
        </p>
      ) : null}
      <section aria-labelledby="runs-model-title" className={CARD}>
        <h3 id="runs-model-title" className="m-0 text-md font-bold">
          {t('st.runs.settings.model.title')}
        </h3>
        <fieldset disabled={!canManage} className="m-0 grid gap-3 border-0 p-0 sm:grid-cols-2">
          {field('driverCostPerHourMinor', 'st.runs.settings.perHour', 'st.runs.settings.perHour.help')}
          {field('driverCostPerMileMinor', 'st.runs.settings.perMile', 'st.runs.settings.perMile.help')}
          {field('collectionMinutes', 'st.runs.settings.collection', 'st.runs.settings.collection.help')}
          {field('perStopMinutes', 'st.runs.settings.perStop', 'st.runs.settings.perStop.help')}
          {field('pickupPointStopMinutes', 'st.runs.settings.pickupStop', 'st.runs.settings.pickupStop.help')}
          {field('handoverMinutesPerOrder', 'st.runs.settings.handover', 'st.runs.settings.handover.help')}
          <TextField
            label={t('st.runs.settings.platformFee')}
            help={t('st.runs.settings.platformFee.help')}
            inputMode="decimal"
            value={d.platformFeePerOrderMinor}
            error={platformProblem}
            onChange={(e) => set({ platformFeePerOrderMinor: e.currentTarget.value })}
          />
          <Checkbox label={t('st.runs.settings.roundTrip')} description={t('st.runs.settings.roundTrip.help')} checked={d.roundTrip} onChange={(e) => set({ roundTrip: e.currentTarget.checked })} />
        </fieldset>
        <div className="flex flex-col gap-0.5 text-xs text-muted" data-testid="economics-meta">
          {settings.source === 'CONFIG' ? (
            <span>{t('st.runs.settings.meta.defaults')}</span>
          ) : (
            <>
              <span>{t('st.runs.settings.meta', { version: settings.version, at: settings.updatedAt ? fmt.dateTime(settings.updatedAt) : '-', who: settings.updatedBy ?? '-' })}</span>
              {settings.reason ? <span>{t('st.runs.settings.meta.reason', { reason: settings.reason })}</span> : null}
            </>
          )}
        </div>
        {canManage ? (
          <div className="flex flex-wrap gap-2">
            <Button disabled={!dirty || !valid} onClick={() => setAsking(true)}>
              {t('st.runs.settings.save')}
            </Button>
            <Button
              variant="neutral"
              disabled={!dirty}
              onClick={() => {
                setD(initial);
                setShown(false);
              }}
            >
              {t('st.runs.settings.discard')}
            </Button>
          </div>
        ) : null}
      </section>
      <Effect draft={draftNumbers} valid={valid} />
      <ReasonDialog
        open={asking}
        title={t('st.runs.settings.dialog.title')}
        help={t('st.runs.settings.dialog.help')}
        confirm={t('st.runs.settings.dialog.confirm')}
        onClose={() => setAsking(false)}
        onSubmit={async (reason) => {
          await save.mutateAsync({ reason });
        }}
      />
    </div>
  );
}

/**
 * Run economics settings (S265, ADR 0005): the driver cost model every break-even and run P&L is calculated from. Staff
 * edit it with a reason; each change keeps who, when, why and the numbers before and after (delivery-runs audit trail).
 * Those who may only view see the same numbers without Save.
 */
export function EconomicsSettingsView() {
  const api = useRunsApi();
  const settings = useQuery(staffRunQueries.settings(api));
  const changes = useQuery(staffRunQueries.settingChanges(api, 50));
  if (settings.isPending) return <Spinner />;
  if (settings.isError) return <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />;
  return (
    <div className="flex flex-col gap-4">
      <Editor key={settings.data.version} settings={settings.data} />
      {changes.isError ? <ErrorState error={changes.error} onRetry={() => void changes.refetch()} /> : <History changes={changes.data ?? []} />}
    </div>
  );
}

