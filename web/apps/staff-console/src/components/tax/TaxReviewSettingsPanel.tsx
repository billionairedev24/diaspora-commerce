'use client';

import { isApiError, staffTaxKeys, staffTaxQueries, type TaxReviewMode, type TaxReviewSettings } from '@afrotransact/api-client';
import { Button, Checkbox, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';

import { ActionDialog, Section } from './shared';

/**
 * How tax review works: every item waits for a tax reviewer (the default), or by risk, where the system approves an item
 * itself only when its category is active, the rules give a clear answer, no required proof is missing and the category is
 * not on the always-review list. The screen says in plain words that Review by risk must only be switched on after the
 * Texas tax professional agrees. Categories are shown by name; a category that is gated is always reviewed whatever the
 * list says (shown, not editable).
 */
export function TaxReviewSettingsPanel() {
  const api = staffApi();
  const q = useQuery(staffTaxQueries.reviewSettings(api.tax));
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  return <Loaded s={q.data} />;
}

function Loaded({ s }: { s: TaxReviewSettings }) {
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [mode, setMode] = useState<TaxReviewMode>(s.mode);
  const [always, setAlways] = useState<string[]>(() => s.categories.filter((c) => c.alwaysReview).map((c) => c.code));
  const [percent, setPercent] = useState(String(s.auditPercent));
  const [filter, setFilter] = useState('');
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const percentNumber = Number(percent);
  const percentOk = percent.trim() !== '' && Number.isInteger(percentNumber) && percentNumber >= 0 && percentNumber <= 100;
  const saved = useMemo(() => s.categories.filter((c) => c.alwaysReview).map((c) => c.code).sort().join(','), [s.categories]);
  const changed = mode !== s.mode || percent !== String(s.auditPercent) || [...always].sort().join(',') !== saved;
  const risk = mode === 'RISK_BASED';
  const visible = s.categories.filter((c) => !filter.trim() || `${c.name} ${c.departmentName}`.toLowerCase().includes(filter.trim().toLowerCase()));
  const switchingOn = mode === 'RISK_BASED' && s.mode !== 'RISK_BASED';

  const save = useMutation({
    mutationFn: () => api.tax.changeReviewSettings({ mode, alwaysReview: always, auditPercent: percentNumber, reason: reason.trim() }),
    retry: false,
    onSuccess: (done) => {
      qc.setQueryData(staffTaxKeys.reviewSettings(), done);
      void qc.invalidateQueries({ queryKey: staffTaxKeys.all });
      toast.show(done.mode === 'RISK_BASED' ? 'Tax review now works by risk. New items follow the new rules.' : 'Every item now waits for a tax reviewer.');
      setAsking(false);
      setReason('');
      setError(null);
      setFields({});
    },
    onError: (e) => {
      setFields(isApiError(e) ? e.fieldErrors : {});
      setError(problemMessage(e));
    }
  });

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h3 className="m-0 text-md">{s.modeLabel} is in force</h3>
        <p className="m-0 max-w-[80ch] text-sm text-muted">{s.modeExplain}</p>
        <p className="m-0 text-sm">
          Last changed by {s.changedByName || 'nobody (this is the default)'}
          {s.changedAt ? `, ${dateTime(s.changedAt)}` : ''}. A change applies to items as they are submitted or changed; items already decided keep their status.
        </p>
      </div>
      <Notice tone="warning" title="Switch on only after the tax professional agrees">
        {s.warning}
      </Notice>
      {!s.canChange ? (
        <Notice tone="info" icon="eye" title="Read only">
          You can read these settings. Changing them needs the tax:classify-admin permission.
        </Notice>
      ) : null}

      <fieldset className="m-0 flex max-w-[80ch] flex-col gap-3 border-0 p-0" disabled={!s.canChange}>
        <legend className="mb-1 p-0 text-sm font-bold">How tax review works</legend>
        {s.options.map((o) => (
          <label key={o.mode} className="flex items-start gap-3 text-sm" aria-disabled={!s.canChange}>
            <input type="radio" name="tax-review-mode" className="mt-1" checked={mode === o.mode} onChange={() => setMode(o.mode)} />
            <span className="flex flex-col gap-0.5">
              <strong className="font-semibold">
                {o.label}
                {o.current ? ' (in use now)' : ''}
              </strong>
              <span className="text-muted">{o.explain}</span>
              {o.current && o.mode === 'RISK_BASED' ? <span className="text-warning">Only the tax professional can say this is safe to leave on.</span> : null}
            </span>
          </label>
        ))}
      </fieldset>

      <fieldset className="m-0 flex max-w-[80ch] flex-col gap-2 border-0 p-0" disabled={!s.canChange}>
        <legend className="mb-1 p-0 text-sm font-bold">Categories that always go to a tax reviewer</legend>
        <p className="m-0 text-xs text-muted">
          {risk ? 'In Review by risk, items in these categories always wait for a person.' : 'These apply only when Review by risk is on. Today every item waits for a person.'} A category that needs sign-off, needs a specific code, waits on a decision or is a blocked bundle is
          always reviewed too ({s.alwaysReviewStatuses.map((x) => x.label.toLowerCase()).join('; ')}); those are shown ticked and can’t be unticked here.
        </p>
        <TextField label="Find a category" value={filter} onChange={(e) => setFilter(e.currentTarget.value)} className="max-w-72" />
        <ul className="m-0 flex max-h-72 list-none flex-col gap-1 overflow-y-auto p-0" aria-label="Categories">
          {visible.map((c) => (
            <li key={c.code}>
              <Checkbox
                label={c.name}
                description={c.gated ? `Always reviewed: ${c.gateLabel}` : c.departmentName}
                checked={c.gated || always.includes(c.code)}
                disabled={c.gated || !s.canChange}
                onChange={(e) => {
                  const on = e.currentTarget.checked;
                  setAlways((cur) => (on ? [...cur, c.code] : cur.filter((x) => x !== c.code)));
                }}
              />
            </li>
          ))}
          {visible.length === 0 ? <li className="text-sm text-muted">No category matches.</li> : null}
        </ul>
        {fields.alwaysReview ? <p className="m-0 text-sm text-danger">{fields.alwaysReview}</p> : null}
      </fieldset>

      <div className="flex max-w-[80ch] flex-col gap-1">
        <TextField
          label="Audit sample (% of items the system approves)"
          type="number"
          inputMode="numeric"
          min={0}
          max={100}
          step={1}
          value={percent}
          disabled={!s.canChange}
          onChange={(e) => setPercent(e.currentTarget.value)}
          error={percentOk ? fields.auditPercent : 'Use a whole number from 0 to 100.'}
          help="Which items are picked is fixed by the product’s ATIN, so a retry never picks an item twice."
          className="max-w-72"
        />
        <p className="m-0 text-sm text-muted">{s.auditExplain}</p>
      </div>

      {s.canChange ? (
        <div>
          <Button disabled={!changed || !percentOk} onClick={() => setAsking(true)}>
            Save changes…
          </Button>
        </div>
      ) : null}

      <Section title="History">
        {s.history.length === 0 ? (
          <p className="m-0 text-sm text-muted">The review settings have not been changed yet.</p>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-2 p-0 text-sm">
            {s.history.map((h, i) => (
              <li key={`${h.at}-${i}`}>
                <strong className="font-semibold">{h.summary || h.toLabel}</strong>
                <span className="block text-muted">
                  Before: {h.fromLabel} · by {h.changedByName || 'someone'}, {dateTime(h.at)}
                </span>
                {h.reason ? <span className="block text-muted">“{h.reason}”</span> : null}
              </li>
            ))}
          </ul>
        )}
      </Section>

      {asking ? (
        <ActionDialog
          open
          title="Change how tax review works?"
          intro={
            <div className="flex flex-col gap-2">
              <p className="m-0">
                {mode === 'RISK_BASED' ? 'Review by risk' : 'Review every item'}, {percentNumber}% audit sample, {always.length} categories always reviewed.
              </p>
              {switchingOn ? (
                <Notice tone="warning" title="Has the tax professional agreed?">
                  Switching on lets the system approve items without a person. Put the professional’s name or the reference of their advice in the reason.
                </Notice>
              ) : null}
            </div>
          }
          submitLabel="Change"
          reasonLabel="Reason"
          reasonHelp="Kept with your name in the history. Name the tax professional or link their note."
          reason={reason}
          onReason={setReason}
          pending={save.isPending}
          error={error}
          onSubmit={() => save.mutate()}
          onClose={() => {
            setAsking(false);
            setError(null);
          }}
        />
      ) : null}
    </div>
  );
}
