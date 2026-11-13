'use client';

import { type CatalogMatch } from '@afrotransact/api-client';
import { Button, TextLinkButton } from '@afrotransact/ui';
import { useId, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { useT } from '@/i18n/t';

import { blockerKey, optionsText } from './match-model';
import { ReportWrongProduct } from './ReportForms';

interface Props {
  match: CatalogMatch;
  onSell: (match: CatalogMatch, atin: string) => void;
}

/**
 * The product page that already exists for the barcode: its photo, title, brand, options and how many sellers list it, flat, with the two
 * answers. "Yes, sell this" opens the offer-only form (the seller never changes the page); "No, this is not my product" opens the
 * wrong-product report. A page the seller cannot sell yet says why, in plain words, and offers no sell button.
 */
export function MatchCard({ match, onSell }: Props) {
  const t = useT();
  const legend = useId();
  const variants = match.variants;
  const [atin, setAtin] = useState(match.atin ?? (variants.length === 1 ? (variants[0]?.atin ?? '') : ''));
  const [wrong, setWrong] = useState(false);
  const chosen = variants.find((v) => v.atin === atin);
  const options = optionsText(chosen?.options ?? match.variantOptions);
  const sellers = match.sellerCount;
  return (
    <section className="flex flex-col gap-4" aria-labelledby={`${legend}-h`} data-result="MATCH">
      <h2 id={`${legend}-h`} className="m-0 text-lg font-semibold">{t('sc.match.found')}</h2>
      <div className="flex flex-wrap gap-4">
        {match.image?.url ? <img src={match.image.url} alt={match.title ?? ''} className="size-28 rounded-sm bg-surface-muted object-contain" /> : null}
        <div className="flex min-w-0 flex-1 basis-60 flex-col gap-1">
          <strong className="text-base" data-testid="match-title">{match.title}</strong>
          {match.brand ? <span className="text-sm text-muted">{match.brand}</span> : null}
          {options ? <span className="text-sm">{options}</span> : null}
          {match.category ? <span className="text-xs text-muted">{match.category}</span> : null}
          {typeof sellers === 'number' ? <span className="text-sm" data-testid="match-sellers">{t('sc.match.sellers', { count: sellers })}</span> : null}
        </div>
      </div>
      {variants.length > 1 ? (
        <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
          <legend className="mb-1 p-0 text-sm font-semibold">{t('sc.match.pickVariant')}</legend>
          {variants.map((v) => (
            <label key={v.atin} className="flex items-center gap-2 text-sm">
              <input type="radio" name={`${legend}-variant`} value={v.atin} checked={atin === v.atin} onChange={() => setAtin(v.atin)} />
              {optionsText(v.options) || match.title}
            </label>
          ))}
        </fieldset>
      ) : null}
      {match.blockers.length ? (
        <Notice tone="warning" title={t('sc.match.cannotOffer')}>
          {match.blockers.map((b) => (
            <span key={b.code} className="block">{blockerKey(b.code) ? t(blockerKey(b.code) ?? 'sc.match.blocker.exists') : b.message}</span>
          ))}
        </Notice>
      ) : null}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <Button variant="primary" disabled={!match.canOffer || !atin} onClick={() => onSell(match, atin)}>{t('sc.match.yes')}</Button>
        <TextLinkButton onClick={() => setWrong((w) => !w)} aria-expanded={wrong}>{t('sc.match.no')}</TextLinkButton>
      </div>
      {wrong ? <ReportWrongProduct match={match} atin={atin || match.atin || match.parentAtin || ''} onClose={() => setWrong(false)} /> : null}
    </section>
  );
}
