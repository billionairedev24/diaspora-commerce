'use client';

import { cartKeys, formatMoney, isApiError, type LolaProductCard, type LolaProposal } from '@afrotransact/api-client';
import { Button, buttonClassName, CheckCircle, CircleNotch, Headset, PaperPlaneRight, Sheet, Sparkle, useToast } from '@afrotransact/ui';
import { useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { type FormEvent, useEffect, useRef, useState } from 'react';

import { t } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { productHref, signInHref } from '@/lib/links';
import { type LolaMessage, useLola } from '@/lib/lola/useLola';

import styles from './LolaSheet.module.css';

interface ProposalLine {
  variantAtin?: string;
  offerId?: string;
  quantity?: number;
}

const str = (v: unknown) => (typeof v === 'string' ? v : '');

/** Carries out a confirmed proposal through the normal BFF routes (Lola never acts on her own). */
function useProposalAction() {
  const qc = useQueryClient();
  const router = useRouter();
  return async (p: LolaProposal): Promise<string | null> => {
    switch (p.type) {
      case 'ADD_TO_CART': {
        const lines = (Array.isArray(p.payload.lines) ? p.payload.lines : []) as ProposalLine[];
        let cart = null;
        for (const l of lines) {
          const offerId = l.offerId ?? l.variantAtin;
          if (offerId) cart = await browserApi().addToCart({ offerId, quantity: Math.max(1, Math.min(99, l.quantity ?? 1)) });
        }
        if (cart) qc.setQueryData(cartKeys.current(), cart);
        return t('lola.added', { n: lines.length });
      }
      case 'CHECKOUT':
        router.push('/checkout');
        return null;
      case 'BOOK_SERVICE':
        router.push(`/services/${encodeURIComponent(str(p.payload.serviceId))}`);
        return null;
      case 'START_RETURN':
        router.push(`/account/returns/new?order=${encodeURIComponent(str(p.payload.orderId))}`);
        return null;
      default:
        return null;
    }
  };
}

function ProductCard({ p }: { p: LolaProductCard }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const offerId = typeof p.offerId === 'string' ? p.offerId : p.atin;
  const add = async () => {
    setBusy(true);
    try {
      qc.setQueryData(cartKeys.current(), await browserApi().addToCart({ offerId, quantity: 1 }));
      toast.show(t('cart.added', { title: p.title }));
    } catch (e) {
      toast.show(isApiError(e) ? e.message : t('auth.genericError'));
    } finally {
      setBusy(false);
    }
  };
  return (
    <li className={styles.product}>
      <Link href={productHref(p.atin)} className={styles.productName}>
        {p.title}
      </Link>
      <span className={styles.productMeta}>
        {formatMoney(p.priceMinor)}
        {p.deliveryDays !== undefined ? ` · ${t('lola.days', { n: p.deliveryDays })}` : ''}
      </span>
      <Button size="sm" onClick={() => void add()} loading={busy} disabled={p.inStock === false} aria-label={`${t('card.addToCart')}: ${p.title}`}>
        {t('lola.add')}
      </Button>
    </li>
  );
}

function Answer({ m, onSettle }: { m: LolaMessage; onSettle: (proposalId: string, state: 'done' | 'dismissed') => void }) {
  const run = useProposalAction();
  const toast = useToast();
  const [busy, setBusy] = useState<string | null>(null);
  const confirm = async (p: LolaProposal) => {
    setBusy(p.id);
    try {
      const note = await run(p);
      if (note) toast.show(note);
      onSettle(p.id, 'done');
    } catch (e) {
      toast.show(isApiError(e) ? e.message : t('auth.genericError'));
    } finally {
      setBusy(null);
    }
  };
  return (
    <div className={styles.lola} data-testid="lola-answer">
      {m.tools.length > 0 && (
        <ul className={styles.tools} aria-label={t('lola.actions')}>
          {m.tools.map((x) => (
            <li key={x.id} className={styles.tool}>
              {x.done ? <CheckCircle weight="fill" aria-hidden /> : <CircleNotch className={styles.spin} aria-hidden />} {x.label}
            </li>
          ))}
        </ul>
      )}
      {(m.text || m.streaming) && (
        <p className={styles.bubble} aria-live={m.streaming ? 'polite' : undefined}>
          {m.text}
          {m.streaming && !m.text && <span className={styles.typing}>{t('lola.typing')}</span>}
          {m.streaming && m.text && <span className={styles.caret} aria-hidden />}
        </p>
      )}
      {m.products.length > 0 && (
        <ul className={styles.products} aria-label={t('lola.products')}>
          {m.products.map((p) => (
            <ProductCard key={p.atin} p={p} />
          ))}
        </ul>
      )}
      {m.proposals.map((p) => (
        <div key={p.id} className={styles.proposal} data-testid="lola-proposal">
          <strong>{p.label}</strong>
          {p.state === 'open' ? (
            <span className={styles.proposalActions}>
              <Button size="sm" variant="secondary" onClick={() => void confirm(p)} loading={busy === p.id}>
                {t(`lola.confirm.${p.type}`)}
              </Button>
              <Button size="sm" variant="neutral" onClick={() => onSettle(p.id, 'dismissed')}>
                {t('lola.notNow')}
              </Button>
            </span>
          ) : (
            <span className={styles.note}>{p.state === 'done' ? t('lola.doneNote') : t('lola.dismissed')}</span>
          )}
        </div>
      ))}
      {m.handoff !== null && (
        <p className={styles.note}>
          <Headset aria-hidden /> {t('lola.handoff')}
        </p>
      )}
      {m.error && (
        <p className={styles.error} role="alert">
          {m.error}
        </p>
      )}
    </div>
  );
}

/** Lola drawer (S135): streamed answers, visible tool calls, product cards, confirmable proposals, suggestion chips. */
export function LolaSheet({ open, onOpenChange, prompt, onPromptSent }: { open: boolean; onOpenChange: (o: boolean) => void; prompt?: string | null; onPromptSent?: () => void }) {
  const { messages, suggestions, busy, needsSignIn, send, settle } = useLola();
  const [input, setInput] = useState('');
  const pathname = usePathname();
  const end = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open && prompt) {
      void send(prompt);
      onPromptSent?.();
    }
  }, [open, prompt, send, onPromptSent]);
  useEffect(() => {
    end.current?.scrollIntoView({ block: 'end' });
  }, [messages]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const text = input;
    setInput('');
    void send(text);
  };

  return (
    <Sheet
      open={open}
      onOpenChange={onOpenChange}
      title={t('lola.title')}
      description={t('lola.subtitle')}
      header={
        <>
          <span className={styles.avatar} aria-hidden>
            <Sparkle weight="fill" />
          </span>
          <span className={styles.text}>
            <strong>{t('lola.title')}</strong>
            <span className={styles.sub}>{t('lola.subtitle')}</span>
          </span>
        </>
      }
    >
      <div className={styles.chat}>
        <div className={styles.log} role="log" aria-label={t('lola.conversation')}>
          {messages.map((m) =>
            m.role === 'user' ? (
              <p key={m.id} className={styles.user}>
                {m.text}
              </p>
            ) : (
              <Answer key={m.id} m={m} onSettle={(pid, state) => settle(m.id, pid, state)} />
            )
          )}
          {busy && <span className={styles.status}>{t('lola.shopping')}</span>}
          {needsSignIn && (
            <Link href={signInHref(pathname)} className={buttonClassName({ size: 'sm' })}>
              {t('account.signIn')}
            </Link>
          )}
          <div ref={end} />
        </div>
        {suggestions.length > 0 && (
          <div className={styles.chips} aria-label={t('lola.suggestions')}>
            {suggestions.map((s) => (
              <button key={s} type="button" className={styles.chip} onClick={() => void send(s)} disabled={busy}>
                {s}
              </button>
            ))}
          </div>
        )}
        <form className={styles.compose} onSubmit={submit}>
          <input
            className={styles.input}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder={t('lola.placeholder')}
            aria-label={t('lola.inputAria')}
            maxLength={4000}
          />
          <button type="submit" className={styles.send} disabled={busy || !input.trim()} aria-label={t('lola.send')}>
            <PaperPlaneRight weight="fill" aria-hidden />
          </button>
        </form>
      </div>
    </Sheet>
  );
}
