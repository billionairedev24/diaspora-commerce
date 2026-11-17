'use client';

import { type CatalogMatch, isApiError, type MatchCandidate, matchingKeys, type SizeRequestResult } from '@afrotransact/api-client';
import { Button, TextField, TextLinkButton } from '@afrotransact/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';

import { blockerKey, candidateAsMatch, optionsText, sizeNeedsNewProduct, sizeProblemKey, sizeToConfirm } from './match-model';

interface Props {
  candidate: MatchCandidate;
  /** What was typed and the category chip the search used: the answer is recorded against them. */
  query: string;
  categoryId: string | undefined;
  /** The seller confirmed the page (and its size): on to the price-and-stock form. */
  onSell: (match: CatalogMatch, atin: string) => void;
  /** "Add it as a new product instead": the full form, started with what was typed. */
  onNew: () => void;
}

/** Where the card is. Every step has a way back to the one before, so a wrong tap is never a dead end. */
type Step = 'start' | 'confirm' | 'different' | 'answered';

/**
 * One page that looks like what the seller is selling: its photo, brand, size or pack and the number of sellers. Nothing is matched
 * silently: "This is my product" opens the one-tap size question ("Same size, 5 kg?"), and only "Yes, same size" goes on. A seller whose
 * size is different is not stopped and does not make a duplicate page: "No, a different size" asks for their own size to be added to this
 * page (staff check it like any new size). Every state can be left: "Not this product" goes back to the start, the size question and
 * the size form go back to each other, and nothing here disables the other answers.
 */
export function CandidateCard({ candidate: c, query, categoryId, onSell, onNew }: Props) {
  const t = useT();
  const heading = useId();
  const api = sellerApi().matching;
  const qc = useQueryClient();
  const only = c.variants.length === 1 ? (c.variants[0]?.atin ?? '') : '';
  const [atin, setAtin] = useState(c.atin ?? only);
  const [step, setStep] = useState<Step>('start');
  const [size, setSize] = useState('');
  const [answer, setAnswer] = useState<SizeRequestResult | null>(null);
  const confirm = sizeToConfirm(c, atin);
  const decide = useMutation({
    mutationFn: (target: string) => api.decide({ choice: 'SELL_EXISTING', query, ...(categoryId ? { categoryId } : {}), atin: target, sizeConfirmed: true }),
    onSuccess: (d, target) => {
      void qc.invalidateQueries({ queryKey: matchingKeys.all });
      if (d.canOffer) onSell(candidateAsMatch(c, target), target);
    }
  });
  const ask = useMutation({
    mutationFn: () => api.requestSize({ query, ...(categoryId ? { categoryId } : {}), atin: atin || c.parentAtin, size: size.trim() }),
    onSuccess: (r) => {
      setAnswer(r);
      setStep('answered');
      void qc.invalidateQueries({ queryKey: matchingKeys.all });
    }
  });
  const blockers = decide.data && !decide.data.canOffer ? decide.data.blockers : c.blockers;
  const sellers = c.sellerCount;
  const back = () => {
    setStep('start');
    setAnswer(null);
    decide.reset();
    ask.reset();
  };
  const problemCode = ask.isError && isApiError(ask.error) ? ask.error.code : undefined;
  const problemKey = sizeProblemKey(problemCode);
  return (
    <li className="m-0 flex list-none flex-col gap-3 border-0 border-t border-solid border-border pt-4 first:border-t-0 first:pt-0" aria-labelledby={`${heading}-t`} data-testid="candidate" data-kind={c.kind} data-step={step}>
      <div className="flex flex-wrap gap-4">
        {c.image?.url ? <img src={c.image.url} alt={c.title} className="size-24 rounded-sm bg-surface-muted object-contain" /> : null}
        <div className="flex min-w-0 flex-1 basis-60 flex-col gap-1">
          <strong id={`${heading}-t`} className="text-base">{c.title}</strong>
          {c.brand ? <span className="text-sm text-muted">{c.brand}</span> : null}
          {c.sizeLabel ? <span className="text-sm" data-testid="candidate-size">{c.sizeLabel}</span> : null}
          {c.category ? <span className="text-xs text-muted">{c.category}</span> : null}
          {typeof sellers === 'number' ? <span className="text-sm" data-testid="candidate-sellers">{t('sc.match.sellers', { count: sellers })}</span> : null}
          {c.likely ? <span className="text-sm font-semibold">{t('sc.match.cand.likely')}</span> : null}
        </div>
      </div>
      {blockers.length ? (
        <Notice tone="warning" title={t('sc.match.cannotOffer')}>
          {blockers.map((b) => (
            <span key={b.code} className="block">{blockerKey(b.code) ? t(blockerKey(b.code) ?? 'sc.match.blocker.exists') : b.message}</span>
          ))}
        </Notice>
      ) : null}
      {step === 'start' ? (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <Button variant="primary" disabled={!c.canOffer} onClick={() => setStep('confirm')}>{t('sc.match.cand.mine')}</Button>
        </div>
      ) : null}
      {step === 'confirm' ? (
        <div className="flex flex-col gap-3" role="group" aria-label={t('sc.match.cand.mine')}>
          {c.variants.length > 1 ? (
            <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
              <legend className="mb-1 p-0 text-sm font-semibold">{t('sc.match.pickVariant')}</legend>
              {c.variants.map((v) => (
                <label key={v.atin} className="flex items-center gap-2 text-sm">
                  <input type="radio" name={`${heading}-variant`} value={v.atin} checked={atin === v.atin} onChange={() => setAtin(v.atin)} />
                  {optionsText(v.options) || c.title}
                </label>
              ))}
            </fieldset>
          ) : null}
          {atin && confirm ? <p className="m-0 text-sm font-semibold" data-testid="size-question">{t('sc.match.cand.sameSize', { size: confirm })}</p> : null}
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <Button variant="primary" disabled={!atin || decide.isPending} onClick={() => decide.mutate(atin)}>{confirm ? t('sc.match.cand.yesSize') : t('sc.match.cand.confirm')}</Button>
            {confirm ? <TextLinkButton onClick={() => setStep('different')}>{t('sc.match.cand.noSize')}</TextLinkButton> : null}
            <TextLinkButton onClick={back}>{t('sc.match.cand.undo')}</TextLinkButton>
          </div>
          {decide.isError ? <p role="alert" className="m-0 text-sm text-danger">{problemMessage(decide.error)}</p> : null}
        </div>
      ) : null}
      {step === 'different' ? (
        <form
          className="flex flex-col gap-3"
          aria-label={t('sc.match.cand.differentTitle')}
          data-testid="size-differs"
          onSubmit={(e) => {
            e.preventDefault();
            if (size.trim() && !ask.isPending) ask.mutate();
          }}
        >
          <h3 className="m-0 text-base font-semibold">{t('sc.match.cand.differentTitle')}</h3>
          <p className="m-0 text-sm">{t('sc.match.cand.differentLead')}</p>
          <TextField id={`${heading}-size`} label={t('sc.match.cand.sizeLabel')} help={t('sc.match.cand.sizeHelp')} autoComplete="off" maxLength={40} value={size} onChange={(e) => { setSize(e.target.value); ask.reset(); }} />
          {ask.isError ? <p role="alert" className="m-0 text-sm text-danger">{problemKey ? t(problemKey) : problemMessage(ask.error)}</p> : null}
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <Button type="submit" variant="primary" loading={ask.isPending} disabled={!size.trim()}>{t('sc.match.cand.sizeAsk')}</Button>
            {sizeNeedsNewProduct(problemCode) ? <Button variant="neutral" onClick={onNew}>{t('sc.match.cand.sizeAddNew')}</Button> : null}
            <TextLinkButton onClick={() => { ask.reset(); setStep('confirm'); }}>{t('sc.match.cand.sizeSame')}</TextLinkButton>
            <TextLinkButton onClick={back}>{t('sc.match.cand.undo')}</TextLinkButton>
          </div>
        </form>
      ) : null}
      {step === 'answered' && answer ? (
        <div className="flex flex-col gap-3" data-testid="size-answer" data-outcome={answer.outcome}>
          {answer.outcome === 'ALREADY_ON_PAGE' ? (
            <>
              <p className="m-0 text-sm font-semibold">{t('sc.match.cand.sizeOnPage', { size: answer.size ?? '' })}</p>
              {answer.blockers.length ? (
                <Notice tone="warning" title={t('sc.match.cannotOffer')}>
                  {answer.blockers.map((b) => <span key={b.code} className="block">{blockerKey(b.code) ? t(blockerKey(b.code) ?? 'sc.match.blocker.exists') : b.message}</span>)}
                </Notice>
              ) : null}
              <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
                <Button variant="primary" disabled={!answer.canOffer || !answer.existingAtin || decide.isPending} onClick={() => decide.mutate(answer.existingAtin ?? '')}>{t('sc.match.cand.sizeSell', { size: answer.size ?? '' })}</Button>
                <TextLinkButton onClick={() => setStep('different')}>{t('sc.match.cand.sizeChange')}</TextLinkButton>
                <TextLinkButton onClick={back}>{t('sc.match.cand.undo')}</TextLinkButton>
              </div>
              {decide.isError ? <p role="alert" className="m-0 text-sm text-danger">{problemMessage(decide.error)}</p> : null}
            </>
          ) : (
            <>
              <Notice tone="success" title={answer.outcome === 'ALREADY_REQUESTED' ? t('sc.match.cand.sizeWaiting') : t('sc.match.cand.sizeSent')}>
                {answer.outcome === 'REQUESTED' ? t('sc.match.cand.sizeSentBody') : null}
              </Notice>
              <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
                <TextLinkButton onClick={() => setStep('different')}>{t('sc.match.cand.sizeChange')}</TextLinkButton>
                <TextLinkButton onClick={back}>{t('sc.match.cand.undo')}</TextLinkButton>
              </div>
            </>
          )}
        </div>
      ) : null}
    </li>
  );
}
