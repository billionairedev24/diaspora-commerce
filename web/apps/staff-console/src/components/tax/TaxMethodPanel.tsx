'use client';

import { isApiError, staffTaxKeys, staffTaxQueries, type TaxMethod, type TaxMethodKey, type TaxProvider } from '@afrotransact/api-client';
import { Button, Select, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';

import { ActionDialog, Facts, RunbookLink, Section } from './shared';
import { CompletenessMeter } from './TaxCodeMapPanel';

/**
 * Who calculates sales tax: our own rules, Stripe Tax or Avalara, each explained in plain words, with the SHADOW provider
 * that is quoted next to it on real orders and never charged. A provider that is not set up, or whose codes are not all
 * verified, cannot be chosen (the reason is shown). The cutover runbook says how to move from one to the other.
 */
export function TaxMethodPanel() {
  const api = staffApi();
  const q = useQuery(staffTaxQueries.method(api.tax));
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  return <Loaded m={q.data} />;
}

function Loaded({ m }: { m: TaxMethod }) {
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [method, setMethod] = useState<TaxMethodKey>(m.method);
  const [shadow, setShadow] = useState<'' | TaxProvider>(m.shadowProvider === 'STRIPE' || m.shadowProvider === 'AVALARA' ? m.shadowProvider : '');
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const changed = method !== m.method || shadow !== (m.shadowProvider || '');
  const chosen = m.options.find((o) => o.method === method);
  const save = useMutation({
    mutationFn: () => api.tax.changeMethod({ method, shadowProvider: shadow, reason: reason.trim() }),
    retry: false,
    onSuccess: (done) => {
      qc.setQueryData(staffTaxKeys.method(), done);
      void qc.invalidateQueries({ queryKey: staffTaxKeys.all });
      toast.show(`Sales tax is now worked out by ${done.methodLabel.toLowerCase()}. Shadow mode: ${done.shadowLabel.toLowerCase()}.`);
      setAsking(false);
      setReason('');
      setError(null);
    },
    onError: (e) => {
      setFields(isApiError(e) ? e.fieldErrors : {});
      setError(problemMessage(e));
    }
  });
  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h3 className="m-0 text-md">{m.methodLabel} works out the tax now</h3>
        <p className="m-0 max-w-[80ch] text-sm text-muted">{m.methodExplain}</p>
        <p className="m-0 text-sm">
          Shadow mode: <strong className="font-semibold">{m.shadowLabel}</strong>. Last changed by {m.changedByName || 'the deployment default'}
          {m.changedAt ? `, ${dateTime(m.changedAt)}` : ''}. A change applies to new quotes; orders already placed keep the tax they were charged.
        </p>
      </div>
      {m.draftNotice ? (
        <Notice tone="warning" title="Draft rules">
          {m.draftNotice}
        </Notice>
      ) : null}
      {!m.canConfigure ? (
        <Notice tone="info" icon="eye" title="Read only">
          You can read these settings. Changing them needs the tax:configure permission.
        </Notice>
      ) : null}

      <fieldset className="m-0 flex max-w-[80ch] flex-col gap-3 border-0 p-0" disabled={!m.canConfigure}>
        <legend className="mb-1 p-0 text-sm font-bold">Who calculates the tax</legend>
        {m.options.map((o) => (
          <label key={o.method} className="flex items-start gap-3 text-sm" aria-disabled={!o.available && !o.current}>
            <input type="radio" name="tax-method" className="mt-1" checked={method === o.method} disabled={!o.available && !o.current} onChange={() => setMethod(o.method)} />
            <span className="flex flex-col gap-0.5">
              <strong className="font-semibold">
                {o.label}
                {o.current ? ' (in use now)' : ''}
              </strong>
              <span className="text-muted">{o.explain}</span>
              {!o.available ? <span className="text-warning">Can’t be chosen yet: {o.blockedReason}</span> : null}
            </span>
          </label>
        ))}
      </fieldset>

      <div className="flex max-w-[80ch] flex-col gap-2">
        <Select
          label="Shadow mode"
          value={shadow}
          disabled={!m.canConfigure}
          options={m.shadowOptions.map((o) => ({ value: o.value, label: o.available || o.value === shadow ? o.label : `${o.label} (not set up)`, disabled: !o.available && o.value !== shadow }))}
          onChange={(e) => setShadow(e.currentTarget.value === 'STRIPE' || e.currentTarget.value === 'AVALARA' ? e.currentTarget.value : '')}
          help={m.shadowOptions.find((o) => o.value === shadow)?.explain}
          error={fields.shadowProvider}
          className="max-w-72"
        />
        {m.shadowOptions.filter((o) => !o.available).map((o) => (
          <p key={o.value} className="m-0 text-xs text-muted">
            {o.label}: {o.blockedReason}
          </p>
        ))}
      </div>

      {m.canConfigure ? (
        <div className="flex flex-col gap-2">
          {chosen && !chosen.available && method !== m.method ? <Notice tone="warning" title="Not available">{chosen.blockedReason}</Notice> : null}
          <div>
            <Button disabled={!changed || (!!chosen && !chosen.available && method !== m.method)} onClick={() => setAsking(true)}>
              Save changes…
            </Button>
          </div>
        </div>
      ) : null}

      <Section title="Provider codes">
        <div className="flex flex-col gap-4">
          {m.providers.map((p) =>
            p.completeness ? (
              <div key={p.provider} className="flex flex-col gap-1">
                <CompletenessMeter c={p.completeness} label={p.label} />
                {!p.configured ? <p className="m-0 text-xs text-warning">{p.label} isn’t set up for this environment.</p> : null}
              </div>
            ) : null
          )}
          <p className="m-0 text-sm">
            <Link href="/tax?tab=tax-codes">Open the provider codes</Link>
          </p>
        </div>
      </Section>

      <Section title="Moving to a provider">
        <p className="m-0 max-w-[80ch] text-sm">
          The steps (map every code, run shadow mode for a full filing month, resolve each difference, verify, dry run, switch, keep shadow on) are in the cutover runbook:{' '}
          <RunbookLink path={m.runbookPath} />
          .
        </p>
      </Section>

      <Section title="History">
        {m.history.length === 0 ? (
          <p className="m-0 text-sm text-muted">The method has not been changed yet.</p>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-2 p-0 text-sm">
            {m.history.map((h, i) => (
              <li key={`${h.at}-${i}`}>
                <strong className="font-semibold">
                  {h.fromLabel} → {h.toLabel}
                </strong>{' '}
                by {h.changedByName || 'someone'}, {dateTime(h.at)}
                {h.reason ? <span className="block text-muted">“{h.reason}”</span> : null}
              </li>
            ))}
          </ul>
        )}
      </Section>
      <Facts rows={[['Rule version in force', 'Shown on each profile']]} />

      {asking ? (
        <ActionDialog
          open
          title="Change who calculates sales tax?"
          intro={
            <p className="m-0">
              {method !== m.method ? `${chosen?.label ?? method} will work out the tax for new quotes. ` : ''}
              Shadow mode will be {shadow ? m.shadowOptions.find((o) => o.value === shadow)?.label : 'off'}. Orders already placed keep the tax they were charged.
            </p>
          }
          submitLabel="Change"
          reasonLabel="Reason"
          reasonHelp="Kept with your name in the history. Link the adviser’s note if there is one."
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
