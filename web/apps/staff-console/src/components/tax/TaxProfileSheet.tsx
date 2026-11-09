'use client';

import { isApiError, staffTaxKeys, staffTaxQueries, type TaxProfile, type TaxProfileAction } from '@afrotransact/api-client';
import { Button, Sheet, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';

import { ActionDialog, Facts, GatePill, mono, Section } from './shared';

/**
 * One category profile: the facts sellers answer, the decision table read as sentences, the notes flagged for the tax
 * professional, who last changed it, and the status actions (mark approved with the sign-off, reopen for approval).
 * Read-only where the engine owns the gate (REFINE_CODE) or the person lacks tax:classify-admin.
 */
export function TaxProfileSheet({ code, action, onClose, onAction }: { code: string | null; action: string | null; onClose: () => void; onAction: (action: string | null) => void }) {
  const api = staffApi();
  const q = useQuery({ ...staffTaxQueries.profile(api.tax, code ?? ''), enabled: code !== null });
  const p = q.data;
  const chosen = p?.actions.find((a) => a.key === action && a.available) ?? null;
  return (
    <Sheet open={code !== null} onOpenChange={(o) => !o && onClose()} title={p ? p.name : 'Tax profile'} description={p ? `${p.departmentName} · rule version ${p.ruleVersion}` : undefined} closeLabel="Close">
      {q.isPending && code !== null ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {p ? (
        <div className="flex flex-col gap-5">
          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="m-0 flex-1 text-xl font-bold">{p.name}</h2>
              <GatePill status={p.status} label={p.statusLabel} />
            </div>
            <p className="m-0 text-sm text-muted">{p.statusExplain}</p>
            {p.readOnlyReason ? (
              <Notice tone="info" icon="eye" title="Read only">
                {p.readOnlyReason}
              </Notice>
            ) : null}
            {p.actions.some((a) => a.available) ? (
              <div role="group" aria-label="Change the status" className="flex flex-wrap gap-2">
                {p.actions
                  .filter((a) => a.available)
                  .map((a) => (
                    <Button key={a.key} size="sm" variant={a.key === 'approve' ? 'primary' : 'neutral'} onClick={() => onAction(a.key)}>
                      {a.label}…
                    </Button>
                  ))}
              </div>
            ) : null}
          </div>
          <Notes p={p} />
          <Section title="What sellers are asked">
            {p.facts.length === 0 ? (
              <p className="m-0 text-sm text-muted">No questions: every product in this category is decided by the category alone.</p>
            ) : (
              <table className="w-full border-collapse text-sm">
                <caption className="at-visually-hidden">Questions for this category</caption>
                <thead>
                  <tr className="text-left text-muted">
                    <th scope="col" className="py-1 pe-3 font-normal">Question</th>
                    <th scope="col" className="py-1 pe-3 font-normal">Who answers</th>
                    <th scope="col" className="py-1 font-normal">When it is needed</th>
                  </tr>
                </thead>
                <tbody>
                  {p.facts.map((f) => (
                    <tr key={f.id} className="border-t border-border align-top">
                      <th scope="row" className="py-1.5 pe-3 text-left font-semibold">
                        {f.label}
                        {f.unit ? <span className="font-normal text-muted"> ({f.unit})</span> : null}
                        {f.evidence ? <span className="block text-xs font-normal text-muted">Needs a photo or PDF as proof</span> : null}
                      </th>
                      <td className="py-1.5 pe-3">{f.ownerLabel}</td>
                      <td className="py-1.5">{f.needed}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Section>
          <Section title="How the answers decide the tax" aside={<span className="text-xs text-muted">First matching rule wins</span>}>
            <ol className="m-0 flex list-none flex-col gap-3 p-0">
              {p.decisions.map((d) => (
                <li key={d.id} className="flex flex-col gap-0.5 border-t border-border pt-2 text-sm">
                  <span className="[overflow-wrap:anywhere]">
                    <span className={`${mono} text-muted`}>{d.id}</span> {d.sentence}.
                  </span>
                  <span className="text-xs text-muted">
                    {d.outcomeLabel}
                    {d.rateDisplay ? ` · ${d.rateDisplay}` : ''}
                    {d.stripeCode ? <> · Stripe <span className={mono}>{d.stripeCode}</span></> : null}
                    {' · '}
                    <span className={mono}>{d.internalTaxCode}</span>
                  </span>
                </li>
              ))}
            </ol>
          </Section>
          <Section title="History">
            {p.history.length > 0 ? (
              <ul className="m-0 flex list-none flex-col gap-2 p-0 text-sm">
                {p.history.map((h, i) => (
                  <li key={`${h.at ?? h.changedAt}-${i}`}>
                    <strong className="font-semibold">{h.status}</strong> by {h.changedByName ?? 'someone'}, {dateTime(h.at ?? h.changedAt)}
                    {h.reason ? <span className="block text-muted">“{h.reason}”</span> : null}
                    {h.approvedBy ? <span className="block text-muted">Signed off by {h.approvedBy}{h.decisionRef ? `, ${h.decisionRef}` : ''}</span> : null}
                  </li>
                ))}
              </ul>
            ) : (
              <Facts rows={[['Last changed by', p.changedByName || '—'], ['When', p.changedAt ? dateTime(p.changedAt) : '—'], ['Rule version', p.ruleVersion]]} />
            )}
          </Section>
        </div>
      ) : null}
      {p && chosen ? <StatusDialog p={p} action={chosen} onClose={() => onAction(null)} /> : null}
    </Sheet>
  );
}

function Notes({ p }: { p: TaxProfile }) {
  if (p.notes.length === 0) return null;
  return (
    <Section title="Flagged for the tax professional">
      <div className="flex flex-col gap-2">
        {p.notes.map((n, i) => (
          <Notice key={`${n.title}-${i}`} tone="warning" title={n.title}>
            {n.text}
            {n.decision ? ` (Open decision ${n.decision}.)` : ''}
          </Notice>
        ))}
      </div>
    </Section>
  );
}

function StatusDialog({ p, action, onClose }: { p: TaxProfile; action: TaxProfileAction; onClose: () => void }) {
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [reason, setReason] = useState('');
  const [approvedBy, setApprovedBy] = useState('');
  const [decisionRef, setDecisionRef] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const lifting = action.needsSignOff && p.status !== 'ACTIVE';
  const run = useMutation({
    mutationFn: () =>
      api.tax.changeProfileStatus(p.categoryCode, { status: action.status, reason: reason.trim(), approvedBy: lifting ? approvedBy.trim() : undefined, decisionRef: lifting ? decisionRef.trim() : undefined }),
    retry: false,
    onSuccess: (done) => {
      qc.setQueryData(staffTaxKeys.profile(done.categoryCode), done);
      void qc.invalidateQueries({ queryKey: staffTaxKeys.all });
      toast.show(action.key === 'approve' ? `${done.name} is marked approved.` : `${done.name} is back to waiting for sign-off.`);
      onClose();
    },
    onError: (e) => {
      setFields(isApiError(e) ? e.fieldErrors : {});
      setError(problemMessage(e));
    }
  });
  return (
    <ActionDialog
      open
      title={action.key === 'approve' ? `Mark ${p.name} approved?` : `Reopen ${p.name} for approval?`}
      intro={<p className="m-0">{action.explain}</p>}
      submitLabel={action.key === 'approve' ? 'Mark approved' : 'Reopen for approval'}
      reasonLabel="Reason"
      reasonHelp="Kept with your name in the history."
      reason={reason}
      onReason={setReason}
      extraValid={!lifting || (approvedBy.trim() !== '' && decisionRef.trim() !== '')}
      pending={run.isPending}
      error={error}
      onSubmit={() => run.mutate()}
      onClose={onClose}
    >
      {lifting ? (
        <>
          <TextField label="Signed off by" help="The Texas tax professional or firm who approved this category." value={approvedBy} maxLength={200} error={fields.approvedBy} onChange={(e) => setApprovedBy(e.currentTarget.value)} />
          <TextField label="Reference" help="The signed-off decision or opinion letter, e.g. “Opinion letter 2026-11-09”." value={decisionRef} maxLength={200} error={fields.decisionRef} onChange={(e) => setDecisionRef(e.currentTarget.value)} />
        </>
      ) : null}
    </ActionDialog>
  );
}
