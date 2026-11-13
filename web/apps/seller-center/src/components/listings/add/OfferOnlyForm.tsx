'use client';

import { type CatalogMatch, isApiError, matchingKeys, type OfferOnlyResult, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { Button, buttonClassName, FormActions, Message, Select, TextField, TextLinkButton } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRef, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { problemMessage } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { idempotencyKey } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { emptyOffer, OFFER_CONDITIONS, OFFER_FIELD, type OfferForm, offerProblemKey, offerProblems, optionsText, serverOfferField, toOfferInput } from './match-model';
import { type ReportKind,ReportPanel } from './ReportForms';

const FULFILMENTS = ['SELLER', 'AFROTRANSACT', 'AFROTRANSACT_DELIVERY'] as const;
const HANDLING_DAYS = [1, 2, 3, 4, 5, 7, 10, 14, 21, 30];

interface Props {
  match: CatalogMatch;
  /** The variant being sold. */
  atin: string;
  onBack: () => void;
  onAnother: () => void;
}

/**
 * The offer-only form for a page that already exists: price, list price, quantity, condition (and a note when it is not new), shipping,
 * who ships it, handling time, where from and an SKU. There is no photo, title or attribute field: those belong to the page and are the same
 * for every seller. The form says so, and offers "Suggest a change", "This is the wrong product" and "This page is a duplicate". An offer waits
 * ("We are checking the tax details for this item.") until the seller's own tax decision for the item is resolved.
 */
export function OfferOnlyForm({ match, atin, onBack, onAnother }: Props) {
  const t = useT();
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const api = sellerApi();
  const [f, setF] = useState<OfferForm>(emptyOffer);
  const [showAll, setShowAll] = useState(false);
  const [server, setServer] = useState<Record<string, string>>({});
  const [form, setForm] = useState('');
  const [result, setResult] = useState<OfferOnlyResult | null>(null);
  const [report, setReport] = useState<ReportKind | null>(null);
  const key = useRef(idempotencyKey());
  const shipFrom = useQuery(sellerQueries.shipFrom(api, orgId));
  const locations = shipFrom.data ?? [];
  const child = match.variants.find((v) => v.atin === atin);
  const options = optionsText(child?.options ?? match.variantOptions);
  const patch = (p: Partial<OfferForm>) => {
    setF((prev) => ({ ...prev, ...p }));
    setServer({});
    setForm('');
  };
  const problems = offerProblems(f);
  const shown = (id: string): string | undefined => {
    const k = problems[id];
    if (server[id]) return server[id];
    return showAll && k ? t(k) : undefined;
  };

  const send = useMutation({
    mutationFn: () => api.matching.createOffer(toOfferInput(atin, f), key.current),
    onSuccess: (r) => {
      setResult(r);
      void qc.invalidateQueries({ queryKey: sellerKeys.listings(orgId) });
      void qc.invalidateQueries({ queryKey: matchingKeys.all });
    },
    onError: (e) => {
      key.current = idempotencyKey();
      if (isApiError(e) && Object.keys(e.fieldErrors).length && e.code !== 'PAGE_CONTENT_LOCKED') {
        setServer(Object.fromEntries(Object.entries(e.fieldErrors).map(([field, message]) => [serverOfferField(field), message])));
        return;
      }
      const known = isApiError(e) ? offerProblemKey(e.code) : undefined;
      setForm(known ? t(known) : problemMessage(e));
    }
  });

  const submit = () => {
    setShowAll(true);
    if (Object.keys(problems).length) {
      const first = Object.keys(problems)[0];
      if (first) document.getElementById(first)?.focus();
      return;
    }
    send.mutate();
  };

  if (result) {
    const held = result.status === 'TAX_HELD';
    return (
      <>
        <PageHeader title={t('sc.match.offerOnly.title')} />
        <div className="flex max-w-3xl flex-col gap-4" data-testid="offer-result" data-status={result.status}>
          <Notice tone={held ? 'info' : 'success'} title={held ? t('sc.match.offer.held.title') : t('sc.match.offer.live.title')}>
            {held ? t('sc.match.offer.held.body') : t('sc.match.offer.live.body')}
          </Notice>
          <p className="m-0 text-sm"><strong>{match.title}</strong>{options ? ` · ${options}` : ''}</p>
          <FormActions>
            <Link href="/listings" className={buttonClassName({ variant: 'primary' })}>{t('sc.match.offer.listings')}</Link>
            <TextLinkButton onClick={onAnother}>{t('sc.match.offer.another')}</TextLinkButton>
          </FormActions>
        </div>
      </>
    );
  }

  return (
    <>
      <PageHeader title={t('sc.match.offerOnly.title')} description={t('sc.match.offerOnly.lead')} />
      <div className="flex max-w-3xl flex-col gap-6" data-tour="offer-only">
        <div className="flex flex-wrap gap-4" data-testid="offer-page">
          {match.image?.url ? <img src={match.image.url} alt={match.title ?? ''} className="size-20 rounded-sm bg-surface-muted object-contain" /> : null}
          <div className="flex min-w-0 flex-1 basis-60 flex-col gap-1">
            <strong>{match.title}</strong>
            {match.brand ? <span className="text-sm text-muted">{match.brand}</span> : null}
            {options ? <span className="text-sm">{options}</span> : null}
            <TextLinkButton className="self-start" onClick={onBack}>{t('sc.match.back')}</TextLinkButton>
          </div>
        </div>
        <p className="m-0 text-sm" data-testid="content-locked">{t('sc.match.contentLocked')}</p>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
          <TextLinkButton aria-expanded={report === 'suggest'} onClick={() => setReport(report === 'suggest' ? null : 'suggest')}>{t('sc.match.suggestEdit')}</TextLinkButton>
          <TextLinkButton aria-expanded={report === 'wrong'} onClick={() => setReport(report === 'wrong' ? null : 'wrong')}>{t('sc.match.wrongProduct')}</TextLinkButton>
          <TextLinkButton aria-expanded={report === 'duplicate'} onClick={() => setReport(report === 'duplicate' ? null : 'duplicate')}>{t('sc.match.duplicate')}</TextLinkButton>
        </div>
        {report ? <ReportPanel kind={report} match={match} atin={atin} onClose={() => setReport(null)} /> : null}

        <form className="flex flex-col gap-4" noValidate aria-label={t('sc.match.offerOnly.title')} onSubmit={(e) => { e.preventDefault(); submit(); }}>
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField id={OFFER_FIELD.price} label={t('sc.pf.field.priceUsd')} inputMode="decimal" value={f.price} error={shown(OFFER_FIELD.price)} onChange={(e) => patch({ price: e.target.value })} />
            <TextField id={OFFER_FIELD.listPrice} label={t('sc.pf.field.compareAt')} help={t('sc.pf.compareAtHelp')} inputMode="decimal" value={f.listPrice} error={shown(OFFER_FIELD.listPrice)}
              onChange={(e) => patch({ listPrice: e.target.value })} />
            <Select id={OFFER_FIELD.condition} label={t('sc.pf.field.condition')} value={f.condition} error={shown(OFFER_FIELD.condition)}
              options={OFFER_CONDITIONS.map((c) => ({ value: c, label: t(`sc.pf.condition.${c}` as MessageKey) }))} onChange={(e) => patch({ condition: e.currentTarget.value as OfferForm['condition'] })} />
            {f.condition !== 'NEW' ? (
              <TextField id={OFFER_FIELD.note} label={t('sc.match.offer.note')} help={t('sc.match.offer.noteHelp')} maxLength={200} value={f.note} error={shown(OFFER_FIELD.note)} onChange={(e) => patch({ note: e.target.value })} />
            ) : null}
          </div>

          <fieldset className="m-0 flex flex-col gap-2 border-0 p-0" id={OFFER_FIELD.fulfilment}>
            <legend className="mb-1 p-0 text-sm font-semibold">{t('sc.match.offer.fulfilment')}</legend>
            {FULFILMENTS.map((c) => (
              <div key={c} className="flex items-start gap-2 text-sm">
                <input type="radio" id={`of-fulfilment-${c}`} name="of-fulfilment" className="mt-1 shrink-0" value={c} checked={f.fulfilment === c} aria-describedby={`of-fulfilment-${c}-hint`}
                  onChange={() => patch({ fulfilment: c })} />
                <span>
                  <label htmlFor={`of-fulfilment-${c}`} className="cursor-pointer font-semibold">{t(`sc.ff.choice.${c}` as MessageKey)}</label>
                  <span id={`of-fulfilment-${c}-hint`} className="block text-xs text-muted">{t(`sc.ff.choice.${c}.hint` as MessageKey)}</span>
                </span>
              </div>
            ))}
          </fieldset>

          <div className="grid gap-4 sm:grid-cols-2">
            {f.fulfilment === 'SELLER' ? (
              <TextField id={OFFER_FIELD.qty} label={t('sc.pf.field.qty')} inputMode="numeric" value={f.qty} error={shown(OFFER_FIELD.qty)} onChange={(e) => patch({ qty: e.target.value })} />
            ) : null}
            {f.fulfilment !== 'AFROTRANSACT' ? (
              <TextField id={OFFER_FIELD.shipping} label={t('sc.match.offer.shipping')} help={t('sc.match.offer.shippingHelp')} inputMode="decimal" value={f.shipping} error={shown(OFFER_FIELD.shipping)}
                onChange={(e) => patch({ shipping: e.target.value })} />
            ) : null}
            {f.fulfilment === 'SELLER' ? (
              <Select id={OFFER_FIELD.handling} label={t('sc.pf.field.handling')} help={t('sc.pf.handlingHelp')} value={f.handling} error={shown(OFFER_FIELD.handling)}
                options={HANDLING_DAYS.map((d) => ({ value: String(d), label: t('sc.pf.handlingDays', { count: d }) }))} onChange={(e) => patch({ handling: e.currentTarget.value })} />
            ) : null}
            {f.fulfilment !== 'AFROTRANSACT' ? (
              <Select id={OFFER_FIELD.shipFrom} label={t('sc.pf.field.shipFrom')} value={f.shipFromId} error={shown(OFFER_FIELD.shipFrom)}
                help={locations.length ? undefined : t('sc.pf.shipFromNone')} placeholder={t('sc.match.offer.shipFromDefault')}
                options={locations.map((a) => ({ value: a.id, label: `${a.label} · ${a.city}, ${a.state}` }))} onChange={(e) => patch({ shipFromId: e.currentTarget.value })} />
            ) : null}
            <TextField id={OFFER_FIELD.sku} label={t('sc.pf.field.sku')} placeholder={t('sc.pf.sku.placeholder')} maxLength={40} value={f.sku} error={shown(OFFER_FIELD.sku)} onChange={(e) => patch({ sku: e.target.value })} />
          </div>

          {f.fulfilment === 'AFROTRANSACT' ? (
            <fieldset className="m-0 flex flex-col gap-2 border-0 p-0" id={OFFER_FIELD.pkg}>
              <legend className="mb-1 p-0 text-sm font-semibold">{t('sc.pf.field.package')}</legend>
              <span className="text-xs text-muted">{t('sc.match.offer.packageHelp')}</span>
              <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
                {(['length', 'width', 'height', 'weight'] as const).map((k) => (
                  <TextField key={k} id={`of-pkg-${k}`} label={t(`sc.match.offer.pkg.${k}` as MessageKey)} inputMode="decimal" value={f.pkg[k]} onChange={(e) => patch({ pkg: { ...f.pkg, [k]: e.target.value } })} />
                ))}
              </div>
              {shown(OFFER_FIELD.pkg) ? <Message tone="error">{shown(OFFER_FIELD.pkg)}</Message> : null}
            </fieldset>
          ) : null}

          {form ? <Message tone="error">{form}</Message> : null}
          <FormActions>
            <Button type="submit" variant="primary" loading={send.isPending}>{t('sc.match.offer.submit')}</Button>
            <TextLinkButton onClick={onBack}>{t('sc.match.back')}</TextLinkButton>
          </FormActions>
        </form>
      </div>
    </>
  );
}
