'use client';

import { deliveryQuoteKeys, isApiError, type QuoteContact } from '@afrotransact/api-client';
import { Button, buttonClassName, Field, fieldStyles, Message, Select } from '@afrotransact/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type FormEvent, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './DeliveryQuotes.module.css';

export interface AskForQuoteProps {
  /** The saved address the delivery goes to. */
  addressId: string;
  /** "Marfa, TX 79843": where, in words. */
  place: string;
  /** Part of the cart to quote (default: every line). */
  lines?: Array<{ variantAtin: string; quantity: number }>;
  /** Called with the request's id once it is made (or found: the same cart and address returns the same request). */
  onRequested?: (id: string) => void;
}

const CONTACTS: QuoteContact[] = ['EMAIL', 'PHONE', 'SMS'];

/**
 * "Too far for standard delivery. Ask us for a quote" (listing rules D20). Checkout never dead-ends: the shopper asks
 * here, customer care prices the delivery, and the shopper is emailed a link back to checkout where the quote is applied.
 * Signed-out shoppers sign in first (the BFF answers 401). Asking twice for the same cart and address is one request.
 */
export function AskForQuote({ addressId, place, lines, onRequested }: AskForQuoteProps) {
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const [contact, setContact] = useState<QuoteContact>('EMAIL');
  const [note, setNote] = useState('');
  const ask = useMutation({
    mutationFn: () => api.requestDeliveryQuote({ addressId, lines, contact, note: note.trim() || undefined }),
    onSuccess: async (q) => {
      onRequested?.(q.id);
      await qc.invalidateQueries({ queryKey: deliveryQuoteKeys.all });
    }
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!ask.isPending) ask.mutate();
  };
  const signedOut = ask.isError && isApiError(ask.error) && ask.error.status === 401;
  const tooMany = ask.isError && isApiError(ask.error) && ask.error.status === 429;

  return (
    <section className={styles.box} aria-labelledby="dq-ask-title" data-testid="ask-for-quote">
      <h3 id="dq-ask-title" className={styles.boxTitle}>
        {t('dq.ask.title')}
      </h3>
      <p className={styles.text}>{t('dq.ask.text', { place })}</p>
      {ask.isSuccess ? (
        <>
          <Message tone="success">{t('dq.ask.sent')}</Message>
          <div className={styles.actions}>
            <Link href="/account/delivery-quotes" className={buttonClassName({ variant: 'secondary' })}>
              {t('dq.ask.follow')}
            </Link>
          </div>
        </>
      ) : (
        <form className={styles.form} onSubmit={submit}>
          <Select
            label={t('dq.ask.contact')}
            value={contact}
            onChange={(e) => setContact(e.target.value as QuoteContact)}
            options={CONTACTS.map((c) => ({ value: c, label: t(`dq.ask.contact.${c}`) }))}
          />
          <Field label={t('dq.ask.note')}>
            {(a11y) => <textarea {...a11y} className={fieldStyles.control} value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} rows={2} data-testid="ask-for-quote-note" />}
          </Field>
          {signedOut && <Message tone="error">{t('dq.ask.signIn')}</Message>}
          {tooMany && <Message tone="error">{t('dq.ask.tooMany')}</Message>}
          {ask.isError && !signedOut && !tooMany && <Message tone="error">{isApiError(ask.error) ? ask.error.message : t('auth.genericError')}</Message>}
          <div className={styles.actions}>
            <Button type="submit" loading={ask.isPending} data-testid="ask-for-quote-send">
              {t('dq.ask.send')}
            </Button>
          </div>
        </form>
      )}
    </section>
  );
}
