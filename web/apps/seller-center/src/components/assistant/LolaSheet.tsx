'use client';

import { type AssistantProposal, type AssistantReply, type AssistantStatus, isApiError, sellerKeys } from '@afrotransact/api-client';
import { Button, CheckCircle, cx, PaperPlaneRight, Sheet, Sparkle, Spinner, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type FormEvent, useEffect, useRef, useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { idempotencyKey } from '@/lib/format';
import { hrefOf } from '@/lib/routes';
import { useSeller } from '@/lib/session';

interface Message {
  id: number;
  role: 'user' | 'assistant';
  text: string;
  steps?: string[];
  proposals?: AssistantProposal[];
  error?: boolean;
}

const CHIPS = ['orders', 'stock', 'listings', 'payouts'] as const;

/** A change Lola prepared, as a button: nothing happens until the seller taps it, then the normal Seller Center call runs. */
function Proposal({ p }: { p: AssistantProposal }) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const [done, setDone] = useState(false);
  const run = useMutation({
    mutationFn: async () => {
      const api = sellerApi();
      if (p.kind === 'SET_STOCK' && p.sku && p.quantity !== undefined) await api.adjustStock({ sku: p.sku, quantity: p.quantity, reason: 'STOCK_TAKE' }, idempotencyKey());
      else if (p.kind === 'NEXT_STEP' && p.orderNumber && p.shipmentId && p.step) await api.updateShipmentStatus(p.orderNumber, p.shipmentId, p.step, idempotencyKey());
    },
    onSuccess: () => {
      setDone(true);
      void qc.invalidateQueries({ queryKey: sellerKeys.org(orgId) });
      toast.show(t('sc.lola.done'));
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  return (
    <Button size="sm" variant={done ? 'neutral' : 'primary'} disabled={done} loading={run.isPending} onClick={() => run.mutate()}>
      {done ? <CheckCircle size={16} weight="fill" aria-hidden /> : null} {p.label}
    </Button>
  );
}

/** Today's messages on the plan, and what the next plan gives. */
function Tier({ status }: { status: AssistantStatus }) {
  const t = useT();
  return (
    <div className="flex flex-col gap-1 rounded-md bg-surface-muted px-3 py-2 text-xs" data-testid="lola-tier">
      <span className="flex flex-wrap items-center gap-x-2">
        <strong>{status.limits.label}</strong>
        <span className="text-muted">{t('sc.lola.remaining', { n: status.remaining, total: status.limits.dailyMessages })}</span>
      </span>
      {status.upgrade && (status.remaining <= Math.ceil(status.limits.dailyMessages / 5) || status.tier === 'ESSENTIALS') ? (
        <span className="text-muted">
          {t('sc.lola.upgrade', { plan: status.upgrade.label })}{' '}
          <Link href={hrefOf('billing')} className="text-link">
            {t('sc.lola.seePlans')}
          </Link>
        </span>
      ) : null}
    </div>
  );
}

/** Lola for sellers: ask about orders, listings, stock and (on higher plans) fees and payouts; she prepares changes for you to confirm. */
export function LolaSheet({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const t = useT();
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const status = useQuery({ queryKey: sellerKeys.assistant(orgId), queryFn: ({ signal }) => sellerApi().getAssistantStatus(signal), enabled: open, staleTime: 30_000 });
  const [messages, setMessages] = useState<Message[]>([]);
  const [draft, setDraft] = useState('');
  const next = useRef(1);
  const end = useRef<HTMLDivElement>(null);
  const ask = useMutation({
    mutationFn: (text: string) =>
      sellerApi().askAssistant({
        message: text,
        history: messages.filter((m) => !m.error).slice(-16).map((m) => ({ role: m.role, content: m.text }))
      }),
    onSuccess: (r: AssistantReply) => {
      qc.setQueryData(sellerKeys.assistant(orgId), r.status);
      setMessages((m) => [...m, { id: next.current++, role: 'assistant', text: r.answer, steps: r.steps.map((s) => s.label), proposals: r.proposals }]);
    },
    onError: (e) => {
      void qc.invalidateQueries({ queryKey: sellerKeys.assistant(orgId) });
      setMessages((m) => [...m, { id: next.current++, role: 'assistant', text: isApiError(e) && e.problem.detail ? e.problem.detail : problemMessage(e), error: true }]);
    }
  });
  useEffect(() => {
    end.current?.scrollIntoView({ block: 'end' });
  }, [messages, ask.isPending]);

  const send = (text: string) => {
    const v = text.trim();
    if (!v || ask.isPending) return;
    setMessages((m) => [...m, { id: next.current++, role: 'user', text: v }]);
    setDraft('');
    ask.mutate(v);
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    send(draft);
  };
  const s = status.data;
  const spent = s ? s.remaining <= 0 : false;
  return (
    <Sheet open={open} onOpenChange={onOpenChange} title={t('sc.lola.title')} description={t('sc.lola.description')} width={460}>
      <div className="flex h-full min-h-0 flex-col gap-3">
        {s ? <Tier status={s} /> : null}
        <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto" aria-live="polite" data-testid="lola-messages">
          {messages.length === 0 ? (
            <div className="flex flex-col gap-3">
              <p className="m-0 flex items-start gap-2 text-sm">
                <Sparkle size={18} aria-hidden className="mt-0.5 shrink-0 text-gold-600" />
                <span>{t('sc.lola.greeting')}</span>
              </p>
              <div className="flex flex-wrap gap-2">
                {CHIPS.filter((c) => c !== 'payouts' || !s || s.limits.tools.includes('payouts')).map((c) => (
                  <Button key={c} size="sm" variant="neutral" disabled={ask.isPending || spent} onClick={() => send(t(`sc.lola.chip.${c}`))}>
                    {t(`sc.lola.chip.${c}`)}
                  </Button>
                ))}
              </div>
            </div>
          ) : null}
          {messages.map((m) => (
            <div key={m.id} className={cx('flex flex-col gap-2', m.role === 'user' ? 'items-end' : 'items-start')}>
              {m.steps?.length ? (
                <ul className="m-0 flex list-none flex-col gap-0.5 p-0 text-xs text-muted">
                  {m.steps.map((label, i) => (
                    <li key={`${m.id}-${i}`}>· {label}</li>
                  ))}
                </ul>
              ) : null}
              <p className={cx('m-0 max-w-[85%] whitespace-pre-wrap rounded-md px-3 py-2 text-sm', m.role === 'user' ? 'bg-primary text-on-primary' : m.error ? 'bg-danger-bg text-danger' : 'bg-surface-muted')}>{m.text}</p>
              {m.proposals?.length ? (
                <div className="flex flex-col items-start gap-1">
                  {m.proposals.map((p, i) => (
                    <Proposal key={`${m.id}-p${i}`} p={p} />
                  ))}
                  <span className="text-xs text-muted">{t('sc.lola.tapToConfirm')}</span>
                </div>
              ) : null}
            </div>
          ))}
          {ask.isPending ? (
            <div className="flex items-center gap-2 text-xs text-muted">
              <Spinner /> {t('sc.lola.thinking')}
            </div>
          ) : null}
          <div ref={end} />
        </div>
        <form onSubmit={submit} className="flex items-end gap-2" noValidate>
          <textarea
            className="min-h-10 flex-1 resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm"
            rows={2}
            maxLength={1500}
            value={draft}
            placeholder={spent ? t('sc.lola.spent') : t('sc.lola.placeholder')}
            aria-label={t('sc.lola.placeholder')}
            disabled={spent}
            onChange={(e) => setDraft(e.currentTarget.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                send(draft);
              }
            }}
          />
          <Button type="submit" size="sm" disabled={!draft.trim() || ask.isPending || spent} aria-label={t('sc.lola.send')}>
            <PaperPlaneRight size={16} aria-hidden />
          </Button>
        </form>
      </div>
    </Sheet>
  );
}
