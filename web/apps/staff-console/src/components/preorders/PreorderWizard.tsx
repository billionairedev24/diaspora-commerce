'use client';

import { staffPreorderKeys, staffPreorderQueries } from '@afrotransact/api-client';
import {
  COMMS,
  CREATIVE_SLOTS,
  currencyOfTargets,
  DEFAULT_PAYMENT,
  effectiveFees,
  PREORDER_CATEGORIES,
  preorderChecks,
  type PreorderPaymentSettings,
  type PreorderWindow,
  type PreorderWindowInput,
  SMS_LINK_TOKEN,
  smsPreview
} from '@afrotransact/api-client/staff-preorders';
import { useToast } from '@afrotransact/ui';
import { CheckCircle, MapPin, X, XCircle } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { useState } from 'react';

import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { useStorefrontUrl } from '@/lib/runtime-config';

import { AreaPicker } from './AreaPicker';
import { CreativesPanel } from './CreativesPanel';
import { amountText, fD, fDT, fromLocalInput, href, money2, NO_EDIT_TITLE, parseAmount, toLocalInput } from './model';
import { Chip, controlClass, labelClass, Toggle, usePreorderAccess, usePreordersApi } from './primitives';

/** The wizard's editable copy of a window (inputs keep what people type; amounts are dollars text). */
export interface WizardForm {
  name: string;
  targets: { key: string; label: string }[];
  opens: string;
  closes: string;
  delivers: string;
  ribbon: string;
  headline: string;
  smsCopy: string;
  categories: string[];
  door: boolean;
  pickup: boolean;
  pickupPoint: string;
  doorFee: string;
  pickupFee: string;
  showCount: boolean;
  budget: string;
  comms: PreorderWindowInput['comms'];
}

const p2 = (n: number) => String(n).padStart(2, '0');
const localDay = (t: number) => {
  const d = new Date(t);
  return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}`;
};

/** A new draft (the design's newDraft(), with dates relative to today). */
export function newForm(now = Date.now()): WizardForm {
  const day = 86_400_000;
  return {
    name: 'New pre-order', targets: [], opens: `${localDay(now + 10 * day)}T09:00`, closes: `${localDay(now + 22 * day)}T23:59`, delivers: localDay(now + 25 * day),
    ribbon: 'Pre-order is open', headline: '', smsCopy: 'AfroTransact: pre-order open. {link} Reply STOP to opt out', categories: ['Pantry', 'Spices'],
    door: true, pickup: false, pickupPoint: '', doorFee: '', pickupFee: '', showCount: true, budget: '200', comms: COMMS.map((c) => c.kind)
  };
}

export const formOf = (w: PreorderWindow): WizardForm => ({
  name: w.name, targets: w.targets, opens: toLocalInput(w.opensAt), closes: toLocalInput(w.closesAt), delivers: w.deliversOn, ribbon: w.ribbon, headline: w.headline,
  smsCopy: w.smsCopy, categories: w.categories, door: w.door, pickup: w.pickup, pickupPoint: w.pickupPoint ?? '', doorFee: amountText(w.doorFeeOverride),
  pickupFee: amountText(w.pickupFeeOverride), showCount: w.showCount, budget: String(Math.round(w.socialBudget / 100)), comms: w.comms
});

/** The API body of a form; null + the message when an amount isn't one. */
export function inputOf(f: WizardForm): { input: PreorderWindowInput | null; error: string | null } {
  const door = parseAmount(f.doorFee);
  const pickup = parseAmount(f.pickupFee);
  const budget = parseAmount(f.budget);
  if (Number.isNaN(door) || Number.isNaN(pickup)) return { input: null, error: 'Enter fee overrides as amounts like 7.99, or leave them blank for the platform default.' };
  if (Number.isNaN(budget)) return { input: null, error: 'Enter the social budget as an amount.' };
  return {
    error: null,
    input: {
      name: f.name.trim(), targets: f.targets.map((t) => t.key), opensAt: fromLocalInput(f.opens), closesAt: fromLocalInput(f.closes), deliversOn: f.delivers, ribbon: f.ribbon.trim(),
      headline: f.headline.trim(), smsCopy: f.smsCopy, categories: f.categories, door: f.door, pickup: f.pickup, pickupPoint: f.pickup && f.pickupPoint.trim() ? f.pickupPoint.trim() : null,
      doorFeeOverride: door, pickupFeeOverride: pickup, showCount: f.showCount, socialBudget: budget ?? 0, comms: f.comms
    }
  };
}

const STEPS = ['Details', 'Advertorials', 'Comms', 'Review'] as const;

function Details({ f, set, payment, currency }: { f: WizardForm; set: (p: Partial<WizardForm>) => void; payment: PreorderPaymentSettings; currency: string }) {
  const api = usePreordersApi();
  const areas = useQuery(staffPreorderQueries.areas(api, { page: 0 }));
  const saved = (areas.data?.saved ?? []).filter((a) => !f.targets.some((t) => t.key === a.key));
  const fees = effectiveFees({ doorFeeOverride: null, pickupFeeOverride: null }, payment, currency);
  const add = (key: string, label?: string) => {
    if (f.targets.some((t) => t.key === key)) return;
    const [c, r, city] = key.split('/');
    set({ targets: [...f.targets, { key, label: label ?? (city ? `${city}, ${r ?? ''}${c !== 'US' ? ` · ${c ?? ''}` : ''}` : `All of ${r ?? ''}${c !== 'US' ? ` · ${c ?? ''}` : ''}`) }] });
  };
  return (
    <>
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,240px),1fr))] gap-4">
        <label className={labelClass}>
          Name
          <input value={f.name} onChange={(e) => set({ name: e.currentTarget.value })} maxLength={120} className={controlClass} />
        </label>
        <label className={labelClass}>
          Opens
          <input type="datetime-local" value={f.opens} onChange={(e) => set({ opens: e.currentTarget.value })} className={controlClass} />
        </label>
        <label className={labelClass}>
          Closes
          <input type="datetime-local" value={f.closes} onChange={(e) => set({ closes: e.currentTarget.value })} className={controlClass} />
        </label>
        <label className={labelClass}>
          Delivery date
          <input type="date" value={f.delivers} onChange={(e) => set({ delivers: e.currentTarget.value })} className={controlClass} />
        </label>
        <label className={labelClass}>
          Header ribbon text
          <input value={f.ribbon} onChange={(e) => set({ ribbon: e.currentTarget.value })} maxLength={80} className={controlClass} />
        </label>
      </div>
      <div className="flex flex-col gap-2 rounded-md border border-border bg-ink-25 p-4">
        <div className="flex flex-wrap items-baseline justify-between gap-3">
          <span className="text-sm font-semibold">Where this pre-order runs</span>
          <span className="text-xs text-muted">
            Pick a country, then a state or province, then a city or every city in it. Add as many as you need; each one is also saved to your pre-order areas.
          </span>
        </div>
        <ul aria-label="Pre-order areas" className="m-0 flex list-none flex-wrap gap-2 p-0">
          {f.targets.map((t) => (
            <li key={t.key} className="flex items-center gap-1 rounded-pill bg-ink-1000 py-1 pr-1 pl-3 text-xs font-semibold text-ink-0">
              <MapPin aria-hidden />
              {t.label}
              <button
                type="button"
                onClick={() => set({ targets: f.targets.filter((x) => x.key !== t.key) })}
                aria-label={`Remove ${t.label}`}
                className="grid size-[22px] cursor-pointer place-items-center rounded-pill border-0 bg-ink-0/15 text-ink-0"
              >
                <X size={12} aria-hidden />
              </button>
            </li>
          ))}
          {!f.targets.length ? <li className="text-xs text-red-600">No area chosen yet.</li> : null}
        </ul>
        <AreaPicker onAdd={(k) => add(k)} exclude={(k) => f.targets.some((t) => t.key === k)} addLabel="Add" />
        {saved.length ? (
          <label className="flex max-w-[460px] items-center gap-2 text-xs text-muted">
            Or reuse a saved area
            <select
              value=""
              onChange={(e) => {
                const k = e.currentTarget.value;
                const a = saved.find((x) => x.key === k);
                if (a) add(a.key, a.label);
              }}
              className="h-10 min-w-0 flex-1 rounded-sm border border-border-strong bg-ink-0 px-2 text-sm"
            >
              <option value="">Choose a saved area…</option>
              {saved.map((o) => (
                <option key={o.key} value={o.key}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        ) : null}
      </div>
      <label className={labelClass}>
        Headline (hero and pre-order page)
        <input value={f.headline} onChange={(e) => set({ headline: e.currentTarget.value })} maxLength={140} className={controlClass} />
      </label>
      <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
        <legend className="mb-2 p-0 text-sm font-semibold">Eligible categories</legend>
        <div className="flex flex-wrap gap-2">
          {PREORDER_CATEGORIES.map((c) => {
            const on = f.categories.includes(c);
            return (
              <Chip key={c} on={on} onClick={() => set({ categories: on ? f.categories.filter((x) => x !== c) : [...f.categories, c] })}>
                {c}
              </Chip>
            );
          })}
        </div>
      </fieldset>
      <div className="flex flex-wrap gap-5 text-sm">
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={f.door} onChange={(e) => set({ door: e.currentTarget.checked })} className="size-[18px] accent-green-600" />
          {`Door delivery (${money2(parseAmount(f.doorFee) ?? fees.door, currency)})`}
        </label>
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={f.pickup} onChange={(e) => set({ pickup: e.currentTarget.checked })} className="size-[18px] accent-green-600" />
          {`Pickup point (${(parseAmount(f.pickupFee) ?? fees.pickup) ? money2(parseAmount(f.pickupFee) ?? fees.pickup, currency) : 'free'})`}
        </label>
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={f.showCount} onChange={(e) => set({ showCount: e.currentTarget.checked })} className="size-[18px] accent-green-600" />
          Show pre-order count to shoppers
        </label>
      </div>
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,200px),1fr))] gap-4 rounded-md border border-border p-4">
        <span className="col-span-full text-xs text-muted">{`Fee overrides for this pre-order only (${currency}). Leave blank to use the platform default from Areas & settings.`}</span>
        <label className={labelClass}>
          Door fee override
          <input inputMode="decimal" value={f.doorFee} onChange={(e) => set({ doorFee: e.currentTarget.value })} placeholder={`Default ${amountText(fees.door)}`} className={controlClass} />
        </label>
        <label className={labelClass}>
          Pickup fee override
          <input inputMode="decimal" value={f.pickupFee} onChange={(e) => set({ pickupFee: e.currentTarget.value })} placeholder={`Default ${amountText(fees.pickup)}`} className={controlClass} />
        </label>
        {f.pickup ? (
          <label className={`${labelClass} col-span-full`}>
            Pickup point address
            <input value={f.pickupPoint} onChange={(e) => set({ pickupPoint: e.currentTarget.value })} maxLength={160} placeholder={`Street, city · ${payment.pickupWindow}`} className={controlClass} />
          </label>
        ) : null}
      </div>
    </>
  );
}

function Comms({ f, set, code }: { f: WizardForm; set: (p: Partial<WizardForm>) => void; code: string | null }) {
  const sms = smsPreview(f.smsCopy, useStorefrontUrl(), code);
  return (
    <div className="flex flex-col gap-3">
      <span className="text-sm text-muted">Messages are timed against the opening and closing dates. SMS only goes to customers who have opted in (Twilio).</span>
      {COMMS.map((m) => {
        const on = f.comms.includes(m.kind);
        return (
          <div key={m.kind} className="flex items-center gap-3 rounded-md border border-border p-3">
            <Toggle on={on} label={m.title} onClick={() => set({ comms: on ? f.comms.filter((k) => k !== m.kind) : [...f.comms, m.kind] })} />
            <span className="flex flex-1 flex-col">
              <strong className="text-sm">{m.title}</strong>
              <span className="text-xs text-muted">{m.when}</span>
            </span>
            <span className="text-right text-xs text-subtle">{m.channels}</span>
          </div>
        );
      })}
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,260px),1fr))] gap-4">
        <label className={labelClass}>
          Paid social budget (whole window)
          <input type="number" min={0} value={f.budget} onChange={(e) => set({ budget: e.currentTarget.value })} className={controlClass} />
        </label>
        <label className={labelClass}>
          <span>
            SMS copy{' '}
            <span
              className={`font-normal ${sms.fits ? 'text-subtle' : 'text-red-600'}`}
              data-testid="sms-count"
              title={sms.usesLink ? `${SMS_LINK_TOKEN} counts as the ${sms.linkLength}-character tracked link` : undefined}
            >
              {`${sms.length}/${sms.limit}`}
            </span>
          </span>
          <textarea
            value={f.smsCopy}
            onChange={(e) => set({ smsCopy: e.currentTarget.value })}
            rows={3}
            aria-describedby="po-sms-help po-sms-preview"
            aria-invalid={sms.fits ? undefined : true}
            className="resize-y rounded-sm border border-border-strong px-3 py-2 text-sm"
          />
          <span id="po-sms-help" className="text-xs font-normal text-muted">
            {sms.usesLink
              ? `${SMS_LINK_TOKEN} becomes the tracked link (${sms.linkLength} characters, counted).`
              : `Add ${SMS_LINK_TOKEN} where the tracked pre-order link should go.`}
            {sms.encoding === 'UCS-2' ? ` Emoji or special symbols make it a ${sms.limit}-character message.` : ''}
          </span>
        </label>
        <div className="flex flex-col gap-1 text-sm">
          <span className="font-bold">As sent</span>
          <p id="po-sms-preview" data-testid="sms-preview" className="m-0 rounded-md border border-border bg-surface-muted p-3 text-sm break-words whitespace-pre-wrap">
            {sms.text}
          </p>
        </div>
      </div>
    </div>
  );
}

/** Open a pre-order / Edit: Details → Advertorials → Comms → Review, with the seven publish checks. */
export function PreorderWizard({ id }: { id?: string }) {
  const api = usePreordersApi();
  const access = usePreorderAccess();
  const existing = useQuery({ ...staffPreorderQueries.window(api, id ?? ''), enabled: !!id });
  if (!access.canEdit) {
    return (
      <div role="alert" className="p-5 text-sm text-muted">
        {NO_EDIT_TITLE}.
      </div>
    );
  }
  if (id && existing.isPending) return <Spinner />;
  if (id && existing.isError) return <ErrorState error={existing.error} onRetry={() => void existing.refetch()} />;
  return <WizardBody key={id ?? 'new'} initial={existing.data ?? null} />;
}

function WizardBody({ initial }: { initial: PreorderWindow | null }) {
  const api = usePreordersApi();
  const qc = useQueryClient();
  const toast = useToast();
  const router = useRouter();
  const [f, setF] = useState<WizardForm>(() => (initial ? formOf(initial) : newForm()));
  const [step, setStep] = useState(0);
  const [id, setId] = useState<string | null>(initial?.id ?? null);
  const set = (p: Partial<WizardForm>) => setF((cur) => ({ ...cur, ...p }));
  const settings = useQuery(staffPreorderQueries.settings(api));
  const saved = useQuery({ ...staffPreorderQueries.window(api, id ?? ''), enabled: !!id });
  const w = saved.data ?? null;
  const payment = settings.data?.payment ?? DEFAULT_PAYMENT;
  const currency = currencyOfTargets(f.targets.map((t) => t.key));
  const hasHero = !!w?.creatives.some((c) => c.slot === 'HERO');
  const checks = preorderChecks({ ...f, targets: f.targets.map((t) => t.key), opensAt: fromLocalInput(f.opens), closesAt: fromLocalInput(f.closes), deliversOn: f.delivers }, hasHero);
  const blocking = checks.filter((c) => !c.ok).length;
  const isDraft = !w || w.draft;

  /** Creates the draft or saves the edits (If-Match with the version on screen). */
  const persist = async (): Promise<PreorderWindow> => {
    const { input, error } = inputOf(f);
    if (!input) throw new Error(error ?? 'Check the form');
    const next = id && w ? await api.update(id, input, w.version) : await api.create(input);
    qc.setQueryData(staffPreorderKeys.window(next.id), next);
    void qc.invalidateQueries({ queryKey: staffPreorderKeys.lists() });
    void qc.invalidateQueries({ queryKey: staffPreorderKeys.areas() });
    setId(next.id);
    return next;
  };
  const go = useMutation({
    mutationFn: async (to: number) => {
      // Creatives attach to a saved window, so a new pre-order is saved as a draft before Advertorials.
      if (to >= 1 && !id) {
        await persist();
        toast.show('Draft saved');
      }
      return to;
    },
    onSuccess: (to) => setStep(to),
    onError: (e) => toast.show(e instanceof Error && !('problem' in e) ? e.message : problemMessage(e), 'error')
  });
  const saveDraft = useMutation({
    mutationFn: persist,
    onSuccess: (next) => {
      toast.show(next.draft ? 'Draft saved' : 'Changes saved');
      router.push(href.window(next.id));
    },
    onError: (e) => toast.show(e instanceof Error && !('problem' in e) ? e.message : problemMessage(e), 'error')
  });
  const publish = useMutation({
    mutationFn: async () => {
      const next = await persist();
      return next.draft ? api.publish(next.id) : next;
    },
    onSuccess: (next) => {
      qc.setQueryData(staffPreorderKeys.window(next.id), next);
      void qc.invalidateQueries({ queryKey: staffPreorderKeys.all });
      toast.show(`${next.name} published. Placements and comms will follow its dates.`);
      router.push(href.window(next.id));
    },
    onError: (e) => toast.show(e instanceof Error && !('problem' in e) ? e.message : problemMessage(e), 'error')
  });
  const busy = go.isPending || saveDraft.isPending || publish.isPending;
  const fees = effectiveFees({ doorFeeOverride: parseAmount(f.doorFee), pickupFeeOverride: parseAmount(f.pickupFee) }, { ...payment }, currency);
  const validFees = !Number.isNaN(fees.door) && !Number.isNaN(fees.pickup);
  const summary: [string, string][] = [
    ['Where', f.targets.length ? (f.targets.length <= 3 ? f.targets.map((t) => t.label).join(' · ') : `${f.targets.slice(0, 2).map((t) => t.label).join(' · ')} + ${f.targets.length - 2} more`) : 'No area chosen'],
    ['Opens', f.opens ? fDT(fromLocalInput(f.opens) || f.opens) : '–'],
    ['Closes', f.closes ? fDT(fromLocalInput(f.closes) || f.closes) : '–'],
    ['Delivers', f.delivers ? fD(f.delivers) : '–'],
    ['Categories', f.categories.join(', ') || '–'],
    ['Fulfilment', validFees ? [f.door && `Door ${money2(fees.door, currency)}`, f.pickup && `Pickup ${fees.pickup ? money2(fees.pickup, currency) : 'free'}`].filter(Boolean).join(' · ') || '–' : 'Check the fee overrides'],
    ...(f.pickup ? ([['Pickup point', f.pickupPoint.trim() ? `${f.pickupPoint.trim()}` : `Not set · ${payment.pickupWindow}`]] as [string, string][]) : []),
    ['Creatives', `${w?.creatives.length ?? 0} of ${CREATIVE_SLOTS.length}`],
    ['Comms on', String(f.comms.length)],
    ['Social budget', `$${f.budget || '0'}`]
  ];
  const nextLabel = step < 3 ? 'Continue' : blocking ? `${blocking} check${blocking > 1 ? 's' : ''} to fix` : 'Publish pre-order';
  return (
    <div className="flex flex-col">
      <div className="flex flex-wrap items-center gap-3 border-b border-border px-5 py-4">
        <h2 className="m-0 text-lg font-bold">{initial ? `Edit · ${f.name}` : 'Open a pre-order'}</h2>
        <ol aria-label="Steps" className="m-0 ml-auto flex list-none flex-wrap gap-1 p-0">
          {STEPS.map((label, i) => (
            <li key={label}>
              <button
                type="button"
                aria-current={i === step ? 'step' : undefined}
                onClick={() => go.mutate(i)}
                disabled={busy}
                className={`flex cursor-pointer items-center gap-2 rounded-pill border-0 px-3 py-1 text-xs font-bold ${i === step ? 'bg-ink-1000 text-ink-0' : 'bg-transparent text-ink-700'}`}
              >
                <span aria-hidden className={`grid size-[18px] place-items-center rounded-pill text-[10px] text-ink-1000 ${i <= step ? 'bg-gold-500' : 'bg-ink-200'}`}>
                  {i < step ? '✓' : String(i + 1)}
                </span>
                {label}
              </button>
            </li>
          ))}
        </ol>
      </div>
      <div className="flex flex-col gap-5 p-5">
        {step === 0 ? <Details f={f} set={set} payment={payment} currency={currency} /> : null}
        {step === 1 ? w ? <CreativesPanel w={w} canEdit /> : <Spinner /> : null}
        {step === 2 ? <Comms f={f} set={set} code={w?.code ?? null} /> : null}
        {step === 3 ? (
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,300px),1fr))] gap-5">
            <dl className="m-0 flex flex-col">
              {summary.map(([k, v]) => (
                <div key={k} className="flex justify-between gap-3 border-b border-border py-2 text-sm">
                  <dt className="text-muted">{k}</dt>
                  <dd className="m-0 text-right font-bold">{v}</dd>
                </div>
              ))}
            </dl>
            <div className="flex flex-col gap-2">
              <strong className="text-sm">Checks</strong>
              <ul aria-label="Checks" className="m-0 flex list-none flex-col gap-2 p-0">
                {checks.map((c) => (
                  <li key={c.key} data-ok={c.ok} className={`flex items-center gap-2 text-sm ${c.ok ? 'text-green-700' : 'text-red-600'}`}>
                    {c.ok ? <CheckCircle size={18} weight="fill" aria-label="Passed" /> : <XCircle size={18} weight="fill" aria-label="To fix" />}
                    {c.label}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        ) : null}
      </div>
      <div className="flex flex-wrap gap-2 border-t border-border bg-ink-25 px-5 py-4">
        <button type="button" onClick={() => router.push(id ? href.window(id) : href.list())} className="h-10 cursor-pointer border-0 bg-transparent px-4 font-semibold">
          Cancel
        </button>
        <button type="button" onClick={() => setStep(Math.max(0, step - 1))} disabled={step === 0 || busy} className="ml-auto h-10 cursor-pointer rounded-md border border-border-strong bg-ink-0 px-4 font-semibold disabled:opacity-45">
          Back
        </button>
        <button type="button" onClick={() => saveDraft.mutate()} disabled={busy} className="h-10 cursor-pointer rounded-md border border-border-strong bg-ink-0 px-4 font-semibold disabled:opacity-45">
          {isDraft ? 'Save draft' : 'Save changes'}
        </button>
        <button
          type="button"
          onClick={() => (step < 3 ? go.mutate(step + 1) : !blocking && publish.mutate())}
          disabled={(step === 3 && blocking > 0) || busy}
          className="h-10 cursor-pointer rounded-md border-0 bg-green-600 px-5 font-bold text-ink-0 disabled:cursor-not-allowed disabled:opacity-45"
        >
          {nextLabel}
        </button>
      </div>
    </div>
  );
}
