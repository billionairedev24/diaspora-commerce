'use client';

import { isApiError, staffTaxKeys, staffTaxQueries, type TaxEvidence, type TaxReview } from '@afrotransact/api-client';
import { Button, Select, Sheet, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ReactNode, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';

import { ActionDialog, Facts, mono, RecordPill, Section } from './shared';

type Which = 'approve' | 'confirm' | 'reclassify' | 'info';

/**
 * One variant in tax review: why it needs a person, the proposed decision in words, the facts as entered, the proof the
 * seller attached (thumbnails and links), per-seller answers, the history, and the three things a reviewer can do:
 * approve, move it to another category, or ask the seller for more.
 */
export function TaxReviewSheet({ atin, action, onClose, onAction }: { atin: string | null; action: string | null; onClose: () => void; onAction: (a: string | null) => void }) {
  const api = staffApi();
  const q = useQuery({ ...staffTaxQueries.review(api.tax, atin ?? ''), enabled: atin !== null });
  const r = q.data;
  const which: Which | null = r?.canApprove && (action === 'approve' || action === 'reclassify' || action === 'info') ? action : r?.canConfirm && (action === 'confirm' || action === 'reclassify') ? action : null;
  return (
    <Sheet open={atin !== null} onOpenChange={(o) => !o && onClose()} title={r ? r.title : 'Tax review'} description={r ? `${r.sellers.join(', ') || 'No seller yet'} · ${r.categoryName}` : undefined} closeLabel="Close">
      {q.isPending && atin !== null ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {r ? (
        <div className="flex flex-col gap-5">
          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="m-0 flex-1 text-xl font-bold [overflow-wrap:anywhere]">{r.title}</h2>
              <RecordPill status={r.status} label={r.statusLabel} />
            </div>
            <p className="m-0 text-sm text-muted">
              {r.sellers.length ? `Sold by ${r.sellers.join(', ')}` : 'No seller has listed it yet'} · {r.categoryName} ({r.departmentName})
            </p>
            {r.canApprove ? (
              <div role="group" aria-label="Decide" className="flex flex-wrap gap-2">
                <Button size="sm" onClick={() => onAction('approve')}>
                  Approve…
                </Button>
                <Button size="sm" variant="neutral" onClick={() => onAction('reclassify')}>
                  Move to another category…
                </Button>
                <Button size="sm" variant="neutral" onClick={() => onAction('info')}>
                  Ask the seller for more…
                </Button>
              </div>
            ) : r.canConfirm ? (
              <div role="group" aria-label="Check" className="flex flex-wrap gap-2">
                <Button size="sm" onClick={() => onAction('confirm')}>
                  Confirm…
                </Button>
                <Button size="sm" variant="neutral" onClick={() => onAction('reclassify')}>
                  Move to another category…
                </Button>
              </div>
            ) : !r.canDecide ? (
              <Notice tone="info" icon="eye" title="Read only">
                You can read this review. Deciding needs the tax:classify-admin permission.
              </Notice>
            ) : null}
          </div>
          <Notice tone="info" title={r.auditSample ? 'Why it is in the audit sample' : 'Why it needs review'}>
            {r.why}
          </Notice>
          <Proposed r={r} />
          <Section title="Facts as entered">
            {r.facts.length === 0 ? (
              <p className="m-0 text-sm text-muted">The seller has not answered any question yet.</p>
            ) : (
              <Facts rows={r.facts.map((f) => [f.label, f.value] as const)} />
            )}
            {r.sellerListings.some((l) => l.answers.length > 0) ? (
              <ul className="m-0 mt-1 flex list-none flex-col gap-1 p-0 text-sm">
                {r.sellerListings
                  .filter((l) => l.answers.length > 0)
                  .map((l) => (
                    <li key={l.sellerName}>
                      <strong className="font-semibold">{l.sellerName}</strong>: {l.answers.map((a) => `${a.label} ${a.value}`).join('; ')}
                    </li>
                  ))}
              </ul>
            ) : null}
          </Section>
          <Evidence items={r.evidence} />
          {r.ordersAffected != null ? <p className="m-0 text-sm">{r.ordersAffected === 0 ? 'No orders use this product yet.' : `${r.ordersAffected} order${r.ordersAffected === 1 ? '' : 's'} include this product. Placed orders keep the tax they were charged.`}</p> : null}
          {r.decision.reviewedByName ? (
            <Section title="Decision">
              <Facts rows={[['Decided by', r.decision.reviewedByName], ['When', dateTime(r.decision.reviewedAt)], ['Rule version', <span key="v" className={mono}>{r.decision.ruleVersion}</span>]]} />
            </Section>
          ) : null}
          <Section title="History">
            <ul className="m-0 flex list-none flex-col gap-2 p-0 text-sm">
              {r.audit.map((a, i) => (
                <li key={`${a.at}-${i}`}>
                  <strong className="font-semibold">{a.actionLabel}</strong> by {a.actorName}, {dateTime(a.at)}
                  {a.reason ? <span className="block text-muted">“{a.reason}”</span> : null}
                </li>
              ))}
            </ul>
          </Section>
        </div>
      ) : null}
      {r && which === 'approve' ? <ApproveDialog r={r} onClose={() => onAction(null)} /> : null}
      {r && which === 'confirm' ? <ConfirmDialog r={r} onClose={() => onAction(null)} /> : null}
      {r && which === 'reclassify' ? <ReclassifyDialog r={r} onClose={() => onAction(null)} /> : null}
      {r && which === 'info' ? <InfoDialog r={r} onClose={() => onAction(null)} /> : null}
    </Sheet>
  );
}

function Proposed({ r }: { r: TaxReview }) {
  const p = r.proposed;
  const rows: [string, ReactNode][] = [['Tax', p.outcomeLabel || p.statusLabel || '—']];
  if (p.rateDisplay) rows.push(['Rate', p.rateDisplay]);
  if (p.because) rows.push(['Because', p.because]);
  if (p.internalTaxCode) rows.push(['Outcome code', <span key="c" className={mono}>{p.internalTaxCode}</span>]);
  rows.push(['Category status', r.profileStatusLabel || '—']);
  return (
    <Section title="Proposed decision">
      <Facts rows={rows} />
    </Section>
  );
}

/** Proof the seller attached: an image shows as a thumbnail with its text alternative; anything else is a link. */
function Evidence({ items }: { items: TaxEvidence[] }) {
  return (
    <Section title="Proof the seller attached">
      {items.length === 0 ? (
        <p className="m-0 text-sm text-muted">No proof attached.</p>
      ) : (
        <ul className="m-0 flex list-none flex-wrap gap-4 p-0">
          {items.map((e) => (
            <li key={`${e.factId}-${e.fileName}`} className="flex w-40 flex-col gap-1 text-sm">
              {e.isImage ? (
                <a href={e.url} target="_blank" rel="noreferrer noopener" aria-label={`Open ${e.fileName} in a new tab`}>
                  <img src={e.url} alt={`${e.factLabel}: ${e.fileName}`} width={160} height={100} className="h-24 w-40 rounded-md border border-border object-cover" />
                </a>
              ) : (
                <a href={e.url} target="_blank" rel="noreferrer noopener" className="flex h-24 w-40 items-center justify-center rounded-md border border-border bg-surface px-2 text-center">
                  {e.fileName} (opens in a new tab)
                </a>
              )}
              <span className="text-xs text-muted">
                {e.factLabel}
                {e.uploadedByName ? ` · ${e.uploadedByName}` : ''}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Section>
  );
}

function useDecision(onClose: () => void, atin: string, run: () => Promise<TaxReview>, done: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const [error, setError] = useState<string | null>(null);
  const m = useMutation({
    mutationFn: run,
    retry: false,
    onSuccess: (r) => {
      qc.setQueryData(staffTaxKeys.review(atin), r);
      void qc.invalidateQueries({ queryKey: staffTaxKeys.all });
      toast.show(done);
      onClose();
    },
    onError: (e) => setError(isApiError(e) && e.status === 503 ? 'The tax service can’t take that yet. Nothing was saved. Try again later.' : problemMessage(e))
  });
  return { m, error };
}

function ApproveDialog({ r, onClose }: { r: TaxReview; onClose: () => void }) {
  const api = staffApi();
  const [reason, setReason] = useState('');
  const [facts, setFacts] = useState<Record<string, boolean>>(() => Object.fromEntries(r.approve.staffFacts.map((f) => [f.id, f.value === true])));
  const [codes, setCodes] = useState<Record<string, string>>(() => Object.fromEntries(r.approve.refinedProviders.map((p) => [p.provider, p.value])));
  const needsCode = r.approve.needsRefinedCode;
  const body = {
    reason: reason.trim(),
    staffFacts: r.approve.staffFacts.length ? facts : undefined,
    refinedProviderCodes: needsCode ? Object.fromEntries(Object.entries(codes).filter(([, v]) => v.trim())) : undefined
  };
  const { m, error } = useDecision(onClose, r.atin, () => api.tax.approve(r.atin, body), `${r.title} is approved.`);
  const stripeNeeded = needsCode && r.approve.refinedProviders.some((p) => p.required && !codes[p.provider]?.trim());
  return (
    <ActionDialog
      open
      title={`Approve the tax for ${r.title}?`}
      intro={<p className="m-0">{r.proposed.because ? `${r.proposed.because}.` : r.proposed.statusLabel}</p>}
      submitLabel="Approve"
      reasonLabel="Reason"
      reasonHelp="What you checked. Kept with your name and the rule version."
      reason={reason}
      onReason={setReason}
      extraValid={!stripeNeeded}
      pending={m.isPending}
      error={error}
      onSubmit={() => m.mutate()}
      onClose={onClose}
    >
      {r.approve.staffFacts.map((f) => (
        <label key={f.id} className="flex items-start gap-2 text-sm">
          <input type="checkbox" className="mt-1" checked={facts[f.id] === true} onChange={(e) => setFacts({ ...facts, [f.id]: e.currentTarget.checked })} />
          <span>
            <strong className="font-semibold">{f.label}</strong>
            {f.help ? <span className="block text-muted">{f.help}</span> : null}
          </span>
        </label>
      ))}
      {needsCode ? (
        <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
          <legend className="mb-1 p-0 text-sm font-semibold">Verified provider code for this product</legend>
          <p className="m-0 text-xs text-muted">The category uses a generic code. Enter the specific code you checked in the provider’s list.</p>
          {r.approve.refinedProviders.map((p) => (
            <label key={p.provider} className="flex flex-col gap-1 text-sm">
              {p.label}
              {p.required ? ' (required)' : ' (optional)'}
              <input className="rounded-md border border-border-strong px-2 py-1.5 font-mono text-sm" value={codes[p.provider] ?? ''} onChange={(e) => setCodes({ ...codes, [p.provider]: e.currentTarget.value })} />
            </label>
          ))}
        </fieldset>
      ) : null}
    </ActionDialog>
  );
}

/** An audit sample item: the system approved it and it is live; the reviewer says it is right. Nothing changes for checkout. */
function ConfirmDialog({ r, onClose }: { r: TaxReview; onClose: () => void }) {
  const api = staffApi();
  const [reason, setReason] = useState('');
  const { m, error } = useDecision(onClose, r.atin, () => api.tax.approve(r.atin, { reason: reason.trim() }), `${r.title} is confirmed and leaves the audit sample.`);
  return (
    <ActionDialog
      open
      title={`Confirm the tax for ${r.title}?`}
      intro={<p className="m-0">The system approved this and it is live. Confirming says the category and the tax decision are right. It leaves the audit sample and nothing changes for customers.</p>}
      submitLabel="Confirm"
      reasonLabel="Reason"
      reasonHelp="What you checked. Kept with your name and the rule version."
      reason={reason}
      onReason={setReason}
      pending={m.isPending}
      error={error}
      onSubmit={() => m.mutate()}
      onClose={onClose}
    />
  );
}

function ReclassifyDialog({ r, onClose }: { r: TaxReview; onClose: () => void }) {
  const api = staffApi();
  const profiles = useQuery(staffTaxQueries.profiles(api.tax, { size: 100, sort: ['name,asc'] }));
  const [reason, setReason] = useState('');
  const [category, setCategory] = useState('');
  const { m, error } = useDecision(onClose, r.atin, () => api.tax.reclassify(r.atin, { categoryCode: category, reason: reason.trim() }), `${r.title} moved to another category and is back in review.`);
  return (
    <ActionDialog
      open
      title={`Move ${r.title} to another category?`}
      intro={
        <p className="m-0">
          {r.ordersAffected != null
            ? `${r.ordersAffected} order${r.ordersAffected === 1 ? '' : 's'} include this product. `
            : ''}
          The new category’s questions and rules apply. The product goes back into review{r.auditSample ? ' and stops being live until it is approved again' : ''}, and the old decision stays in the history. Placed orders keep the tax they were charged.
        </p>
      }
      submitLabel="Move to category"
      reasonLabel="Reason"
      reasonHelp="Why this category fits better. Kept with your name."
      reason={reason}
      onReason={setReason}
      extraValid={category !== '' && category !== r.categoryCode}
      pending={m.isPending}
      error={error}
      onSubmit={() => m.mutate()}
      onClose={onClose}
    >
      <Select
        label="New category"
        value={category}
        placeholder="Choose a category"
        options={(profiles.data?.items ?? []).filter((p) => p.categoryCode !== r.categoryCode).map((p) => ({ value: p.categoryCode, label: `${p.departmentName} › ${p.name}` }))}
        onChange={(e) => setCategory(e.currentTarget.value)}
        help={profiles.isError ? 'The category list could not be loaded.' : undefined}
      />
    </ActionDialog>
  );
}

function InfoDialog({ r, onClose }: { r: TaxReview; onClose: () => void }) {
  const api = staffApi();
  const [message, setMessage] = useState('');
  const { m, error } = useDecision(onClose, r.atin, () => api.tax.requestInformation(r.atin, { message: message.trim() }), 'Your request was sent to the seller.');
  return (
    <ActionDialog
      open
      title={`Ask the seller of ${r.title} for more?`}
      intro={<p className="m-0">The seller sees your message and the product goes back to them. It returns to this queue when they change it.</p>}
      submitLabel="Send to the seller"
      reasonLabel="What do you need from the seller?"
      reasonHelp="Written for the seller, in plain words."
      reason={message}
      onReason={setMessage}
      pending={m.isPending}
      error={error}
      onSubmit={() => m.mutate()}
      onClose={onClose}
    />
  );
}
