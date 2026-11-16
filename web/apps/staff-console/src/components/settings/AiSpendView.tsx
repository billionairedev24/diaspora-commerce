'use client';

import {
  AI_CAP_REASON_MAX,
  AI_CAP_REASON_MIN,
  type AiSpendCap,
  microsFromUsdText,
  staffAiSpendKeys,
  staffAiSpendQueries,
  usdFromMicros,
  usdTextFromMicros
} from '@afrotransact/api-client';
import { Button, Dialog, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { count, dateTime } from '@/lib/format';

const CALLER_CAP_USD = '0.50';

type T = Translator<MessageKey>;

/**
 * AI and MCP spend cap. At the top: what AI has cost so far today (UTC day) against the daily cap, by feature, read from the AI
 * service's usage ledger; with no call recorded it says so and shows zero, never a guess. Then what is in force: the cap, where it
 * comes from (the starting value set on the deployment, or saved by a named person with a reason), the ceiling it cannot pass, and
 * what is left today. Staff with ai:cap.manage change it here (a confirm step with a required reason, against the version they
 * loaded); everyone else reads the same text. The history of changes follows, people by name.
 */
export function AiSpendView() {
  const t = useT();
  const api = staffApi();
  const q = useQuery(staffAiSpendQueries.today(api.aiSpend));
  const capQ = useQuery(staffAiSpendQueries.cap(api.aiSpend));
  const today = q.data;
  const cap = capQ.data;
  const off = today ? today.capMicros === 0 : false;
  const percent = today && today.capMicros > 0 ? Math.min(100, Math.round((today.spentMicros / today.capMicros) * 100)) : 0;
  return (
    <>
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.sx.ai.intro')}</p>
      <section aria-labelledby="ai-today-title" className="flex flex-col gap-3" data-testid="ai-today">
        <h2 id="ai-today-title" className="m-0 text-md font-bold">
          {t('st.sx.ai.today.title')}
        </h2>
        {q.isPending ? (
          <Spinner center={false} />
        ) : q.isError || !today ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : (
          <>
            <p className="m-0 text-2xl font-bold" data-testid="ai-spent">
              {off ? t('st.sx.ai.today.spentOff', { spent: usdFromMicros(today.spentMicros) }) : t('st.sx.ai.today.spent', { spent: usdFromMicros(today.spentMicros), cap: usdFromMicros(today.capMicros), percent })}
            </p>
            {!off ? <progress className="h-2 w-full max-w-[40rem] accent-[var(--at-color-brand)]" value={today.spentMicros} max={today.capMicros} aria-label={t('st.sx.ai.today.meter', { percent })} /> : null}
            <p className="m-0 text-sm text-muted">
              {today.hasData ? t('st.sx.ai.today.calls', { calls: count(today.calls), tokens: count(today.tokens) }) : t('st.sx.ai.today.none')} {t('st.sx.ai.today.resets', { when: dateTime(today.resetsAt) })}
            </p>
            {today.hasData ? (
              <ul className="m-0 flex list-none flex-col p-0" aria-label={t('st.sx.ai.today.byFeature')} data-testid="ai-by-feature">
                {today.byFeature.map((f) => (
                  <li key={f.feature} className="grid min-h-11 grid-cols-[1fr_auto] items-center gap-x-6 border-b border-border py-1 first:border-t sm:grid-cols-[minmax(0,14rem)_1fr_auto]">
                    <span className="text-sm font-semibold">{f.label}</span>
                    <span className="text-sm text-muted max-sm:hidden">{t('st.sx.ai.today.featureCalls', { calls: f.calls })}</span>
                    <span className="text-sm tabular-nums">{usdFromMicros(f.costMicros)}</span>
                  </li>
                ))}
              </ul>
            ) : null}
            <p className="m-0 text-xs text-muted">{t('st.sx.ai.today.estimate')}</p>
          </>
        )}
      </section>
      <section aria-labelledby="ai-force-title" className="flex flex-col gap-2">
        <h2 id="ai-force-title" className="m-0 text-md font-bold">
          {t('st.sx.ai.force.title')}
        </h2>
        {capQ.isPending ? (
          <Spinner center={false} />
        ) : capQ.isError || !cap ? (
          <ErrorState error={capQ.error} onRetry={() => void capQ.refetch()} />
        ) : (
          <>
            <dl className="m-0 flex flex-col" data-testid="ai-spend">
              {inForce(cap, t).map(([name, value]) => (
                <div key={name} className="grid grid-cols-1 gap-1 border-b border-border py-3 first:border-t sm:grid-cols-[minmax(0,14rem)_1fr] sm:gap-6">
                  <dt className="text-sm font-semibold">{name}</dt>
                  <dd className="m-0 text-sm text-muted">{value}</dd>
                </div>
              ))}
            </dl>
            {!cap.canChange ? (
              <p role="note" className="m-0 text-xs text-muted" data-testid="ai-cap-read-only">
                {t('st.sx.ai.edit.readOnly')}
              </p>
            ) : null}
          </>
        )}
      </section>
      {cap?.canChange ? <CapEditor key={cap.version} cap={cap} spentMicros={today?.spentMicros ?? cap.spentMicros} /> : null}
      {cap ? <History cap={cap} /> : null}
      <p className="m-0 text-sm text-muted">{t('st.sx.ai.runbook')}</p>
    </>
  );
}

/** The rows of "What is in force": the cap, its source, the ceiling, what is left today, then the rules that do not change here. */
function inForce(cap: AiSpendCap, t: T): [string, string][] {
  const off = cap.capMicros === 0;
  const starting = usdTextFromMicros(cap.defaultMicros);
  return [
    [t('st.sx.ai.row.total'), off ? t('st.sx.ai.row.totalOff') : t('st.sx.ai.row.totalValue', { amount: usdTextFromMicros(cap.capMicros) })],
    [
      t('st.sx.ai.row.source'),
      cap.source === 'default' || !cap.changedByName
        ? t('st.sx.ai.row.sourceDefault', { amount: starting })
        : t('st.sx.ai.row.sourceSaved', { name: cap.changedByName, when: dateTime(cap.changedAt ?? ''), reason: cap.reason ?? '', amount: starting })
    ],
    [t('st.sx.ai.row.ceiling'), t('st.sx.ai.row.ceilingValue', { amount: usdTextFromMicros(cap.ceilingMicros) })],
    [t('st.sx.ai.row.remaining'), cap.remainingMicros === 0 ? t('st.sx.ai.row.remainingNone') : t('st.sx.ai.row.remainingValue', { amount: usdFromMicros(cap.remainingMicros) })],
    [t('st.sx.ai.row.caller'), t('st.sx.ai.row.callerValue', { amount: CALLER_CAP_USD })],
    [t('st.sx.ai.row.rate'), t('st.sx.ai.row.rateValue')],
    [t('st.sx.ai.row.atCap'), t('st.sx.ai.row.atCapValue')],
    [t('st.sx.ai.row.mcp'), t('st.sx.ai.row.mcpValue')],
    [t('st.sx.ai.row.changed'), t('st.sx.ai.row.changedValue')]
  ];
}

/** The change form for staff with ai:cap.manage: a dollar field, then a confirm step that needs a reason. Remounted after each save. */
function CapEditor({ cap, spentMicros }: { cap: AiSpendCap; spentMicros: number }) {
  const t = useT();
  const toast = useToast();
  const qc = useQueryClient();
  const api = staffApi().aiSpend;
  const [text, setText] = useState(usdTextFromMicros(cap.capMicros));
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState('');
  const [reasonProblem, setReasonProblem] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const micros = microsFromUsdText(text);
  const max = usdTextFromMicros(cap.ceilingMicros);
  const touched = text.trim() !== usdTextFromMicros(cap.capMicros);
  const problem = micros === null ? t('st.sx.ai.edit.invalid') : micros > cap.ceilingMicros ? t('st.sx.ai.edit.tooHigh', { max }) : null;
  const unchanged = micros === cap.capMicros;
  const save = useMutation({
    mutationFn: (input: { capMicros: number; reason: string }) => api.setCap(input, cap.version),
    onSuccess: (next) => {
      void qc.invalidateQueries({ queryKey: staffAiSpendKeys.all });
      toast.show(t('st.sx.ai.edit.saved', { amount: usdTextFromMicros(next.capMicros) }));
    },
    // a stale version or a refusal: show what is current again
    onError: () => void qc.invalidateQueries({ queryKey: staffAiSpendKeys.all })
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
    if (why.length < AI_CAP_REASON_MIN) return setReasonProblem(t('st.sx.ai.edit.reason.required'));
    if (why.length > AI_CAP_REASON_MAX) return setReasonProblem(t('st.sx.ai.edit.reason.tooLong', { max: AI_CAP_REASON_MAX }));
    if (micros === null) return;
    setReasonProblem(null);
    setFailure(null);
    try {
      await save.mutateAsync({ capMicros: micros, reason: why });
      close();
    } catch (err) {
      setFailure(problemMessage(err));
    }
  };
  return (
    <section aria-labelledby="ai-edit-title" className="flex flex-col gap-3" data-testid="ai-cap-editor">
      <h2 id="ai-edit-title" className="m-0 text-md font-bold">
        {t('st.sx.ai.edit.title')}
      </h2>
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.sx.ai.edit.help', { max })}</p>
      <form
        noValidate
        className="flex flex-col items-stretch gap-3 sm:flex-row sm:items-end"
        onSubmit={(e) => {
          e.preventDefault();
          if (!problem && !unchanged) setAsking(true);
        }}
      >
        {/* The field's own message row is hidden: the note under the form says what is wrong, so the button lines up with the input itself. */}
        <TextField
          className="w-full sm:w-64 [&>p]:hidden"
          label={t('st.sx.ai.edit.label')}
          value={text}
          inputMode="decimal"
          aria-invalid={touched && problem !== null}
          aria-describedby="ai-edit-note"
          onChange={(e) => setText(e.currentTarget.value)}
        />
        <Button type="submit" disabled={problem !== null || unchanged}>
          {t('st.sx.ai.edit.review')}
        </Button>
      </form>
      <p id="ai-edit-note" role={touched && problem ? 'alert' : undefined} className={`m-0 min-h-5 text-xs ${touched && problem ? 'text-danger' : 'text-muted'}`} data-testid="ai-edit-note">
        {touched && problem ? problem : touched && unchanged ? t('st.sx.ai.edit.unchanged') : null}
      </p>
      <Dialog open={asking} onOpenChange={(o) => !o && close()} title={t('st.sx.ai.edit.dialog.title')} description={t('st.sx.ai.edit.dialog.help', { from: usdTextFromMicros(cap.capMicros), to: micros === null ? '' : usdTextFromMicros(micros) })} width={480} closeLabel={t('st.sx.ai.edit.close')}>
        <form noValidate className="flex flex-col gap-3" onSubmit={(e) => void submit(e)}>
          {micros !== null && micros === 0 ? (
            <p role="note" className="m-0 text-sm text-danger" data-testid="ai-warn-off">
              {t('st.sx.ai.edit.warn.off')}
            </p>
          ) : micros !== null && micros < spentMicros ? (
            <p role="note" className="m-0 text-sm text-danger" data-testid="ai-warn-below">
              {t('st.sx.ai.edit.warn.below', { spent: usdFromMicros(spentMicros) })}
            </p>
          ) : null}
          <TextField label={t('st.sx.ai.edit.reason.label')} value={reason} maxLength={AI_CAP_REASON_MAX + 50} error={reasonProblem} onChange={(e) => setReason(e.currentTarget.value)} />
          {failure ? (
            <p role="alert" className="m-0 text-sm text-danger">
              {failure}
            </p>
          ) : null}
          <div className="flex justify-end gap-2">
            <Button variant="neutral" onClick={close}>
              {t('st.sx.ai.edit.cancel')}
            </Button>
            <Button type="submit" loading={save.isPending}>
              {t('st.sx.ai.edit.confirm')}
            </Button>
          </div>
        </form>
      </Dialog>
    </section>
  );
}

/** Who changed the cap, when, from what to what and why: newest first, people by name. */
function History({ cap }: { cap: AiSpendCap }) {
  const t = useT();
  const money = (m: number) => (m === 0 ? `${usdFromMicros(m)} (${t('st.sx.ai.history.aiOff')})` : usdFromMicros(m));
  return (
    <section aria-labelledby="ai-history-title" className="flex flex-col gap-2" data-testid="ai-cap-history">
      <h2 id="ai-history-title" className="m-0 text-md font-bold">
        {t('st.sx.ai.history.title')}
      </h2>
      {cap.history.length === 0 ? (
        <p className="m-0 text-sm text-muted">{t('st.sx.ai.history.none')}</p>
      ) : (
        <ul className="m-0 flex list-none flex-col p-0">
          {cap.history.map((c) => (
            <li key={c.version} className="grid grid-cols-1 gap-1 border-b border-border py-3 first:border-t sm:grid-cols-[minmax(0,14rem)_1fr] sm:gap-6" data-testid="ai-cap-change">
              <span className="text-sm font-semibold">{t('st.sx.ai.history.by', { name: c.changedByName, when: dateTime(c.changedAt) })}</span>
              <span className="flex min-w-0 flex-col gap-0.5 text-sm">
                <span>
                  {t('st.sx.ai.history.change', {
                    from: c.previousMicros === null ? t('st.sx.ai.history.startingValue', { amount: usdTextFromMicros(cap.defaultMicros) }) : money(c.previousMicros),
                    to: money(c.newMicros)
                  })}
                </span>
                <span className="break-words text-muted">{c.reason}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
