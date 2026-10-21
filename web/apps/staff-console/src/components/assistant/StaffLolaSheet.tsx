'use client';

import { isApiError, type StaffAssistantReply } from '@afrotransact/api-client';
import { Button, cx, PaperPlaneRight, Sheet, Sparkle, Spinner } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type FormEvent, useEffect, useRef, useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { recordHref } from '@/lib/modules';

interface Message {
  id: number;
  role: 'user' | 'assistant';
  text: string;
  steps?: string[];
  links?: StaffAssistantReply['links'];
  error?: boolean;
}

const CHIPS = ['queue', 'payouts', 'orders'] as const;
const STATUS_KEY = ['staff', 'assistant'] as const;

/** Lola for staff: ask about queues, orders and sellers' payouts. She only looks things up (with your own access) and offers a button to open the record. */
export function StaffLolaSheet({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const t = useT();
  const qc = useQueryClient();
  const status = useQuery({ queryKey: STATUS_KEY, queryFn: ({ signal }) => staffApi().getAssistantStatus(signal), enabled: open, staleTime: 30_000 });
  const [messages, setMessages] = useState<Message[]>([]);
  const [draft, setDraft] = useState('');
  const next = useRef(1);
  const end = useRef<HTMLDivElement>(null);
  const ask = useMutation({
    mutationFn: (text: string) =>
      staffApi().askAssistant({
        message: text,
        history: messages.filter((m) => !m.error).slice(-12).map((m) => ({ role: m.role, content: m.text }))
      }),
    onSuccess: (r: StaffAssistantReply) => {
      qc.setQueryData(STATUS_KEY, r.status);
      setMessages((m) => [...m, { id: next.current++, role: 'assistant', text: r.answer, steps: r.steps.map((s) => s.label), links: r.links }]);
    },
    onError: (e) => {
      void qc.invalidateQueries({ queryKey: STATUS_KEY });
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
    <Sheet open={open} onOpenChange={onOpenChange} title={t('st.lola.title')} description={t('st.lola.description')} width={460}>
      <div className="flex h-full min-h-0 flex-col gap-3">
        {s ? (
          <div className="rounded-md bg-surface-muted px-3 py-2 text-xs" data-testid="lola-remaining">
            <span className="text-muted">{t('st.lola.remaining', { n: s.remaining, total: s.dailyMessages })}</span> · <span>{t('st.lola.readOnly')}</span>
          </div>
        ) : null}
        <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto" aria-live="polite" data-testid="lola-messages">
          {messages.length === 0 ? (
            <div className="flex flex-col gap-3">
              <p className="m-0 flex items-start gap-2 text-sm">
                <Sparkle size={18} aria-hidden className="mt-0.5 shrink-0 text-gold-600" />
                <span>{t('st.lola.greeting')}</span>
              </p>
              <div className="flex flex-wrap gap-2">
                {CHIPS.map((c) => (
                  <Button key={c} size="sm" variant="neutral" disabled={ask.isPending || spent} onClick={() => send(t(`st.lola.chip.${c}`))}>
                    {t(`st.lola.chip.${c}`)}
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
              {m.links?.length ? (
                <div className="flex flex-col items-start gap-1">
                  {m.links.map((l, i) => {
                    const href = recordHref(l.resource, l.id);
                    return href ? (
                      <Link key={`${m.id}-l${i}`} href={href} onClick={() => onOpenChange(false)} className="inline-flex items-center rounded-pill border border-border px-3 py-1 text-sm font-semibold no-underline hover:no-underline">
                        {l.label}
                      </Link>
                    ) : null;
                  })}
                </div>
              ) : null}
            </div>
          ))}
          {ask.isPending ? (
            <div className="flex items-center gap-2 text-xs text-muted">
              <Spinner /> {t('st.lola.thinking')}
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
            placeholder={spent ? t('st.lola.spent') : t('st.lola.placeholder')}
            aria-label={t('st.lola.placeholder')}
            disabled={spent}
            onChange={(e) => setDraft(e.currentTarget.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                send(draft);
              }
            }}
          />
          <Button type="submit" size="sm" disabled={!draft.trim() || ask.isPending || spent} aria-label={t('st.lola.send')}>
            <PaperPlaneRight size={16} aria-hidden />
          </Button>
        </form>
      </div>
    </Sheet>
  );
}
