'use client';

import {
  PICTURE_BOOL_KNOBS,
  PICTURE_INT_BOUNDS,
  PICTURE_INT_KNOBS,
  PICTURE_KNOB_ORDER,
  PICTURE_REASON_MAX,
  PICTURE_REASON_MIN,
  type PictureKnobName,
  type PictureKnobs,
  type PictureOverrides,
  type PictureProfile,
  type PictureRules,
  type PictureRulesView as View,
  staffPictureRulesKeys,
  staffPictureRulesQueries
} from '@afrotransact/api-client';
import { Alert, Button, Checkbox, Dialog, InfoTip, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';

type T = Translator<MessageKey>;

const PROFILES: PictureProfile[] = ['strict', 'startup', 'testing'];
const isInt = (k: PictureKnobName): k is (typeof PICTURE_INT_KNOBS)[number] => (PICTURE_INT_KNOBS as readonly string[]).includes(k);

const profileName = (t: T, p: PictureProfile) => t(`st.pr.profile.${p}.name` as MessageKey);
const knobLabel = (t: T, k: PictureKnobName) => t(`st.pr.knob.${k}.label` as MessageKey);

/** A knob's value in words: "500 px", "8 MB", "1 photo", "On". */
function valueText(t: T, k: PictureKnobName, v: number | boolean): string {
  if (typeof v === 'boolean') return v ? t('st.pr.value.on') : t('st.pr.value.off');
  if (k === 'minImages') return t('st.pr.unit.photos', { count: v });
  return k === 'maxMegabytes' ? t('st.pr.unit.mb', { n: v }) : t('st.pr.unit.px', { n: v });
}

/**
 * Settings > Picture rules: the ONE setting that decides how many photos a product needs, how large they must be and which checks
 * apply, for every seller, the storefront, search and the apps. At the top a warning whenever the rules are not the strict standard.
 * Then what is in force (the profile, who saved it last by name, what was changed from the profile), the editor for staff with the
 * "Change the picture rules" permission (a profile, then single rules, each explained; a confirm step with a required reason),
 * the three profiles side by side and the history of changes.
 */
export function PictureRulesView() {
  const t = useT();
  const api = staffApi().pictureRules;
  const q = useQuery(staffPictureRulesQueries.get(api));
  if (q.isPending) return <Spinner center={false} />;
  if (q.isError || !q.data) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const view = q.data;
  return (
    <>
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.pr.intro')}</p>
      {view.relaxed ? <RelaxedBanner view={view} /> : <p role="note" className="m-0 text-sm text-muted" data-testid="pr-strict">{t('st.pr.strict.note')}</p>}
      <InForce view={view} />
      {view.canChange ? (
        <Editor key={view.rules.version} view={view} />
      ) : (
        <p role="note" className="m-0 text-xs text-muted" data-testid="pr-read-only">
          {t('st.pr.edit.readOnly')}
        </p>
      )}
      <Comparison profiles={view.profiles} current={view.rules.profile} />
      <History view={view} />
      <p className="m-0 text-sm text-muted">{t('st.pr.docs')}</p>
    </>
  );
}

function RelaxedBanner({ view }: { view: View }) {
  const t = useT();
  const { profile, knobs } = view.rules;
  return (
    <div data-testid="pr-warning">
      <Alert variant="warning" title={t('st.pr.warn.title')} fill>
        {t('st.pr.warn.body', { profile: profileName(t, profile) })}
        {knobs.minImages === 0 ? <> {t('st.pr.warn.noPhoto')}</> : null}
        {!knobs.requireRealPhotoForStorefront ? <> {t('st.pr.warn.storefront')}</> : null}
      </Alert>
    </div>
  );
}

function InForce({ view }: { view: View }) {
  const t = useT();
  const { rules } = view;
  const last = view.history[0];
  const rows: [string, string][] = [
    [t('st.pr.force.profile'), profileName(t, rules.profile)],
    [
      t('st.pr.force.source'),
      view.updatedBy && last
        ? t('st.pr.force.source.saved', { name: view.updatedBy, when: dateTime(view.updatedAt ?? last.changedAt), reason: last.reason })
        : t('st.pr.force.source.default', { profile: profileName(t, view.environmentDefault) })
    ],
    [
      t('st.pr.force.overrides'),
      rules.overridden.length === 0 ? t('st.pr.force.overrides.none') : t('st.pr.force.overrides.some', { count: rules.overridden.length, names: rules.overridden.map((n) => knobLabel(t, n as PictureKnobName)).join(', ') })
    ],
    [t('st.pr.force.formats'), t('st.pr.force.formats.value')]
  ];
  return (
    <section aria-labelledby="pr-force-title" className="flex flex-col gap-2">
      <h2 id="pr-force-title" className="m-0 text-md font-bold">
        {t('st.pr.force.title')}
      </h2>
      <dl className="m-0 flex flex-col" data-testid="pr-in-force">
        {rows.map(([name, value]) => (
          <div key={name} className="grid grid-cols-1 gap-1 border-b border-border py-3 first:border-t sm:grid-cols-[minmax(0,14rem)_1fr] sm:gap-6">
            <dt className="text-sm font-semibold">{name}</dt>
            <dd className="m-0 text-sm text-muted">{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

type Draft = Record<string, number | boolean | undefined>;

function Editor({ view }: { view: View }) {
  const t = useT();
  const toast = useToast();
  const qc = useQueryClient();
  const api = staffApi().pictureRules;
  const [profile, setProfile] = useState<PictureProfile>(view.rules.profile);
  const [draft, setDraft] = useState<Draft>({ ...view.overrides });
  const [typed, setTyped] = useState<Record<string, string>>({});
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState('');
  const [reasonProblem, setReasonProblem] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const defaults: PictureKnobs = view.profiles.find((p) => p.profile === profile)?.knobs ?? view.rules.knobs;
  const valueOf = (k: PictureKnobName): number | boolean => (draft[k] ?? defaults[k]) as number | boolean;
  const changedFromSaved = profile !== view.rules.profile || JSON.stringify(sortKeys(draft)) !== JSON.stringify(sortKeys(view.overrides));
  const problems = knobProblems(t, profile, draft, defaults, typed);
  const hasProblem = Object.keys(problems).length > 0;
  const set = (k: PictureKnobName, v: number | boolean) => setDraft((d) => (v === defaults[k] ? omit(d, k) : { ...d, [k]: v }));
  const save = useMutation({
    mutationFn: (input: { profile: PictureProfile; overrides: PictureOverrides; reason: string }) => api.set(input, view.rules.version),
    onSuccess: (next) => {
      void qc.invalidateQueries({ queryKey: staffPictureRulesKeys.all });
      toast.show(t('st.pr.saved', { profile: profileName(t, next.rules.profile) }));
    },
    // a stale version or a refusal: show what is current again
    onError: () => void qc.invalidateQueries({ queryKey: staffPictureRulesKeys.all })
  });
  const close = () => {
    setAsking(false);
    setReason('');
    setReasonProblem(null);
    setFailure(null);
  };
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const why = reason.trim();
    if (why.length < PICTURE_REASON_MIN) return setReasonProblem(t('st.pr.dialog.reason.required'));
    if (why.length > PICTURE_REASON_MAX) return setReasonProblem(t('st.pr.dialog.reason.tooLong', { max: PICTURE_REASON_MAX }));
    setReasonProblem(null);
    setFailure(null);
    try {
      await save.mutateAsync({ profile, overrides: draft as PictureOverrides, reason: why });
      close();
    } catch (err) {
      setFailure(problemMessage(err));
    }
  };
  const next = { ...defaults, ...draft } as PictureKnobs;
  const relaxed = profile !== 'strict' || Object.keys(draft).length > 0;
  return (
    <section aria-labelledby="pr-edit-title" className="flex flex-col gap-4" data-testid="pr-editor">
      <h2 id="pr-edit-title" className="m-0 text-md font-bold">
        {t('st.pr.profile.title')}
      </h2>
      <div role="radiogroup" aria-labelledby="pr-edit-title" className="flex flex-col gap-2">
        {PROFILES.map((p) => (
          <label key={p} className={`flex cursor-pointer items-start gap-3 rounded-md border p-3 ${profile === p ? 'border-ink-1000 bg-surface-muted' : 'border-border'}`}>
            <input
              type="radio"
              name="picture-profile"
              value={p}
              checked={profile === p}
              className="mt-1"
              onChange={() => {
                setProfile(p);
                setDraft({});
                setTyped({});
              }}
            />
            <span className="flex flex-col gap-0.5">
              <span className="text-sm font-semibold">
                {profileName(t, p)}
                {p === view.environmentDefault ? <span className="ml-2 text-xs font-normal text-muted">{t('st.pr.profile.envdefault')}</span> : null}
              </span>
              <span className="text-sm text-muted">{t(`st.pr.profile.${p}.desc` as MessageKey)}</span>
            </span>
          </label>
        ))}
      </div>
      <h2 className="m-0 text-md font-bold">{t('st.pr.rules.title')}</h2>
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.pr.rules.help')}</p>
      <ul className="m-0 flex list-none flex-col p-0" data-testid="pr-rules">
        {PICTURE_KNOB_ORDER.map((k) => {
          const overridden = draft[k] !== undefined;
          const label = knobLabel(t, k);
          return (
            <li key={k} className="grid grid-cols-1 gap-2 border-b border-border py-3 first:border-t sm:grid-cols-[minmax(0,18rem)_1fr] sm:gap-6" data-testid={`pr-rule-${k}`}>
              <span className="flex items-center gap-1 text-sm font-semibold">
                {label}
                <InfoTip label={t('st.pr.tip.about', { rule: label })}>{t(`st.pr.knob.${k}.tip` as MessageKey)}</InfoTip>
              </span>
              <span className="flex flex-col gap-1">
                <span className="flex flex-wrap items-center gap-3">
                  {isInt(k) ? (
                    <TextField
                      className="w-32 [&>p]:hidden"
                      label={label}
                      aria-label={label}
                      value={typed[k] ?? String(valueOf(k))}
                      inputMode="numeric"
                      aria-invalid={problems[k] !== undefined}
                      onChange={(e) => {
                        const text = e.currentTarget.value;
                        setTyped((s) => ({ ...s, [k]: text }));
                        const n = Number(text);
                        if (/^\d+$/.test(text.trim()) && Number.isInteger(n)) set(k, n);
                      }}
                    />
                  ) : (
                    <Checkbox label={t('st.pr.value.on')} aria-label={label} checked={valueOf(k) === true} onChange={(e) => set(k, e.currentTarget.checked)} />
                  )}
                  {overridden ? (
                    <Button
                      variant="neutral"
                      onClick={() => {
                        setDraft((d) => omit(d, k));
                        setTyped((s) => omit(s, k) as Record<string, string>);
                      }}
                    >
                      {t('st.pr.value.reset')}
                    </Button>
                  ) : null}
                </span>
                <span className="text-xs text-muted">{t('st.pr.value.profile', { value: valueText(t, k, defaults[k]) })}</span>
                {problems[k] ? (
                  <span role="alert" className="text-xs text-danger">
                    {problems[k]}
                  </span>
                ) : null}
              </span>
            </li>
          );
        })}
      </ul>
      <div className="flex items-center gap-3">
        <Button disabled={!changedFromSaved || hasProblem} onClick={() => setAsking(true)}>
          {t('st.pr.edit.review')}
        </Button>
        {!changedFromSaved ? <span className="text-xs text-muted">{t('st.pr.edit.unchanged')}</span> : hasProblem ? <span className="text-xs text-danger">{t('st.pr.edit.invalid')}</span> : null}
      </div>
      <Dialog open={asking} onOpenChange={(o) => !o && close()} title={t('st.pr.dialog.title')} description={t('st.pr.dialog.help', { from: profileName(t, view.rules.profile), to: profileName(t, profile) })} width={520} closeLabel={t('st.pr.dialog.close')}>
        <form noValidate className="flex flex-col gap-3" onSubmit={(e) => void submit(e)}>
          {relaxed ? (
            <p role="note" className="m-0 text-sm text-danger" data-testid="pr-dialog-relaxed">
              {t('st.pr.dialog.relaxed')} {next.minImages === 0 ? t('st.pr.dialog.nophoto') : ''}
            </p>
          ) : null}
          <TextField label={t('st.pr.dialog.reason')} value={reason} maxLength={PICTURE_REASON_MAX + 50} error={reasonProblem} onChange={(e) => setReason(e.currentTarget.value)} />
          {failure ? (
            <p role="alert" className="m-0 text-sm text-danger">
              {failure}
            </p>
          ) : null}
          <div className="flex justify-end gap-2">
            <Button variant="neutral" onClick={close}>
              {t('st.pr.dialog.cancel')}
            </Button>
            <Button type="submit" loading={save.isPending}>
              {t('st.pr.dialog.confirm')}
            </Button>
          </div>
        </form>
      </Dialog>
    </section>
  );
}

function sortKeys(d: Draft): Draft {
  return Object.fromEntries(Object.entries(d).filter(([, v]) => v !== undefined).sort(([a], [b]) => a.localeCompare(b)));
}

function omit<T extends Record<string, unknown>>(o: T, key: string): T {
  const { [key]: _gone, ...rest } = o;
  return rest as T;
}

/** What is wrong with the numbers typed so far (the server checks the same bounds again). */
function knobProblems(t: T, profile: PictureProfile, draft: Draft, defaults: PictureKnobs, typed: Record<string, string>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const k of PICTURE_INT_KNOBS) {
    const b = PICTURE_INT_BOUNDS[k];
    const text = typed[k];
    const n = text !== undefined ? (/^\d+$/.test(text.trim()) ? Number(text) : Number.NaN) : ((draft[k] ?? defaults[k]) as number);
    if (!Number.isInteger(n) || n < b.min || n > b.max) out[k] = t('st.pr.edit.int.range', { min: b.min, max: b.max });
  }
  const long = (draft.minLongSidePx ?? defaults.minLongSidePx) as number;
  const short = (draft.minShortSidePx ?? defaults.minShortSidePx) as number;
  if (!out.minShortSidePx && short > long) out.minShortSidePx = t('st.pr.edit.short');
  void profile;
  return out;
}

function Comparison({ profiles, current }: { profiles: PictureRules[]; current: PictureProfile }) {
  const t = useT();
  return (
    <section aria-labelledby="pr-compare-title" className="flex flex-col gap-2">
      <h2 id="pr-compare-title" className="m-0 text-md font-bold">
        {t('st.pr.compare.title')}
      </h2>
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-sm" data-testid="pr-compare">
          <thead>
            <tr className="text-left">
              <th scope="col" className="border-b border-border py-2 pr-4">
                {t('st.pr.compare.rule')}
              </th>
              {profiles.map((p) => (
                <th key={p.profile} scope="col" className="border-b border-border py-2 pr-4">
                  {profileName(t, p.profile)}
                  {p.profile === current ? <span className="ml-2 text-xs font-normal text-muted">{t('st.pr.compare.current')}</span> : null}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {[...PICTURE_INT_KNOBS, ...PICTURE_BOOL_KNOBS].map((k) => (
              <tr key={k}>
                <th scope="row" className="border-b border-border py-2 pr-4 text-left font-normal">
                  {knobLabel(t, k)}
                </th>
                {profiles.map((p) => (
                  <td key={p.profile} className="border-b border-border py-2 pr-4 text-muted">
                    {valueText(t, k, p.knobs[k])}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function History({ view }: { view: View }) {
  const t = useT();
  return (
    <section aria-labelledby="pr-history-title" className="flex flex-col gap-2" data-testid="pr-history">
      <h2 id="pr-history-title" className="m-0 text-md font-bold">
        {t('st.pr.history.title')}
      </h2>
      {view.history.length === 0 ? (
        <p className="m-0 text-sm text-muted">{t('st.pr.history.none')}</p>
      ) : (
        <ul className="m-0 flex list-none flex-col p-0">
          {view.history.map((c) => (
            <li key={c.version} className="grid grid-cols-1 gap-1 border-b border-border py-3 first:border-t sm:grid-cols-[minmax(0,14rem)_1fr] sm:gap-6" data-testid="pr-change">
              <span className="text-sm font-semibold">{t('st.pr.history.by', { name: c.changedBy, when: dateTime(c.changedAt) })}</span>
              <span className="flex min-w-0 flex-col gap-0.5 text-sm">
                <span>
                  {t('st.pr.history.change', { from: c.previousProfile ? profileName(t, c.previousProfile) : t('st.pr.history.fromDefault'), to: profileName(t, c.profile) })}
                </span>
                {c.overridden.length > 0 ? <span className="text-muted">{t('st.pr.history.overrides', { names: c.overridden.map((n) => knobLabel(t, n as PictureKnobName)).join(', ') })}</span> : null}
                <span className="break-words text-muted">{c.reason}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
