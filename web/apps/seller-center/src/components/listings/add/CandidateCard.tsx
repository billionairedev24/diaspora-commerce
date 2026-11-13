'use client';

import { type CatalogMatch, type MatchCandidate, matchingKeys } from '@afrotransact/api-client';
import { Button, TextLinkButton } from '@afrotransact/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';

import { blockerKey, candidateAsMatch, optionsText, sizeToConfirm } from './match-model';

interface Props {
  candidate: MatchCandidate;
  /** What was typed and the category chip the search used: the answer is recorded against them. */
  query: string;
  categoryId: string | undefined;
  /** The seller confirmed the page (and its size): on to the price-and-stock form. */
  onSell: (match: CatalogMatch, atin: string) => void;
}

/**
 * One page that looks like what the seller is selling: its photo, brand, size or pack and the number of sellers. Nothing is matched
 * silently: "This is my product" opens the one-tap size question ("Same size, 5 kg?"), and only "Yes, same size" goes on. A page that
 * states a different size is not the product, so "No, a different size" sends the seller back to the other results or to "Not here".
 */
export function CandidateCard({ candidate: c, query, categoryId, onSell }: Props) {
  const t = useT();
  const heading = useId();
  const api = sellerApi().matching;
  const qc = useQueryClient();
  const only = c.variants.length === 1 ? (c.variants[0]?.atin ?? '') : '';
  const [atin, setAtin] = useState(c.atin ?? only);
  const [asking, setAsking] = useState(false);
  const [notThisSize, setNotThisSize] = useState(false);
  const size = sizeToConfirm(c, atin);
  const decide = useMutation({
    mutationFn: () => api.decide({ choice: 'SELL_EXISTING', query, ...(categoryId ? { categoryId } : {}), atin, sizeConfirmed: true }),
    onSuccess: (d) => {
      void qc.invalidateQueries({ queryKey: matchingKeys.all });
      if (d.canOffer) onSell(candidateAsMatch(c, atin), atin);
    }
  });
  const blockers = decide.data && !decide.data.canOffer ? decide.data.blockers : c.blockers;
  const sellers = c.sellerCount;
  return (
    <li className="m-0 flex list-none flex-col gap-3 border-0 border-t border-solid border-border pt-4 first:border-t-0 first:pt-0" aria-labelledby={`${heading}-t`} data-testid="candidate" data-kind={c.kind}>
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
      {!asking && !notThisSize ? (
        <div><Button variant="primary" disabled={!c.canOffer} onClick={() => setAsking(true)}>{t('sc.match.cand.mine')}</Button></div>
      ) : null}
      {asking && !notThisSize ? (
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
          {atin && size ? <p className="m-0 text-sm font-semibold" data-testid="size-question">{t('sc.match.cand.sameSize', { size })}</p> : null}
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <Button variant="primary" disabled={!atin || decide.isPending} onClick={() => decide.mutate()}>{size ? t('sc.match.cand.yesSize') : t('sc.match.cand.confirm')}</Button>
            {size ? <TextLinkButton onClick={() => setNotThisSize(true)}>{t('sc.match.cand.noSize')}</TextLinkButton> : null}
          </div>
          {decide.isError ? <p role="alert" className="m-0 text-sm text-danger">{problemMessage(decide.error)}</p> : null}
        </div>
      ) : null}
      {notThisSize ? <p className="m-0 text-sm" data-testid="size-differs">{t('sc.match.cand.noSizeNote')}</p> : null}
    </li>
  );
}
