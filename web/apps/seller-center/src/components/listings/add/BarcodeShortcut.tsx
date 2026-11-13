'use client';

import { type CatalogMatch, matchingQueries } from '@afrotransact/api-client';
import { Button, FormActions, FormFields, Message, TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

import { type ProductFormStart } from '../form-model';
import { barcodeReady, blockerKey, digitsOf, MAX_BARCODE_DIGITS, SEARCH_DEBOUNCE_MS } from './match-model';
import { MatchCard } from './MatchCard';

interface Props {
  /** The seller says yes to a matched page: the price-and-stock form. */
  onSell: (match: CatalogMatch, atin: string) => void;
  /** No page for this barcode: the full form, with the barcode filled in. */
  onCreate: (start: ProductFormStart) => void;
}

/**
 * "I have a barcode": the optional exact shortcut of "Add a product". The lookup runs by itself after the 12th to 14th digit and answers in
 * plain words: the existing product (sell it, or say it is not yours), no page yet (add it with photos), a barcode that does not look right
 * (with the fix), or a number that is not a product barcode. A seller without a barcode never needs this: the search box finds the product by name.
 */
export function BarcodeShortcut({ onSell, onCreate }: Props) {
  const t = useT();
  const { orgId } = useSeller();
  const api = sellerApi().matching;
  const [code, setCode] = useState('');
  const [asked, setAsked] = useState(false);
  // The lookup follows what was typed after a short pause, so every keystroke is not a request.
  const [typed, setTyped] = useState({ code, asked });
  useEffect(() => {
    const h = window.setTimeout(() => setTyped({ code, asked }), SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(h);
  }, [code, asked]);
  const req = barcodeReady(typed.code, typed.asked) ? { gtin: digitsOf(typed.code) } : null;
  const q = useQuery(matchingQueries.match(api, orgId, req ?? { gtin: '' }, req !== null));
  const match = req !== null && !q.isError ? q.data : undefined;
  const digits = digitsOf(code);

  return (
    <div className="flex flex-col gap-4" data-testid="barcode-shortcut">
      <FormFields
        label={t('sc.match.haveBarcode')}
        onSubmit={(e) => {
          e.preventDefault();
          setAsked(true);
          setTyped({ code, asked: true });
          // "Check barcode" always asks again: the page may have been approved since the last answer for the same number.
          if (req !== null) void q.refetch();
        }}
      >
        <TextField
          id="match-barcode"
          label={t('sc.match.barcode.label')}
          help={t('sc.match.barcode.help')}
          inputMode="numeric"
          autoComplete="off"
          maxLength={MAX_BARCODE_DIGITS + 6}
          value={code}
          onChange={(e) => {
            setCode(e.target.value);
            setAsked(false);
          }}
        />
        <FormActions>
          <Button type="submit" variant="neutral" disabled={digits.length < 8}>{t('sc.match.check')}</Button>
        </FormActions>
      </FormFields>
      <div aria-live="polite" className="flex flex-col gap-4" data-testid="match-result">
        {req !== null && q.isFetching && !q.data ? <Spinner label={t('sc.match.checking')} /> : null}
        {req !== null && q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
        {match ? <Result match={match} digits={digits} onUse={(fixed) => { setCode(fixed); setAsked(false); }} onSell={onSell} onCreate={onCreate} /> : null}
      </div>
    </div>
  );
}

interface ResultProps {
  match: CatalogMatch;
  digits: string;
  onUse: (code: string) => void;
  onSell: Props['onSell'];
  onCreate: Props['onCreate'];
}

function Result({ match, digits, onUse, onSell, onCreate }: ResultProps) {
  const t = useT();
  if (match.result === 'MATCH') return <MatchCard match={match} onSell={onSell} />;
  if (match.result === 'INVALID') {
    return (
      <div className="flex flex-col gap-2" data-result="INVALID">
        <Message tone="error">{match.suggestion ? t('sc.match.invalid.suggest', { code: match.suggestion }) : digits.length < 8 || digits.length > 14 ? t('sc.match.invalid.short') : t('sc.match.invalid')}</Message>
        {match.suggestion ? (
          <div><Button size="sm" variant="neutral" onClick={() => onUse(match.suggestion ?? '')}>{t('sc.match.invalid.use', { code: match.suggestion })}</Button></div>
        ) : null}
      </div>
    );
  }
  if (match.result === 'RESTRICTED') {
    return (
      <div className="flex flex-col gap-1" data-result="RESTRICTED">
        <Message tone="error">{t('sc.match.restricted')}</Message>
        <p className="m-0 text-sm text-muted">{t('sc.match.restricted.fix')}</p>
      </div>
    );
  }
  // No page: add it, with a main photo; a wrong-match report in progress on this barcode says to wait.
  return (
    <div className="flex flex-col gap-3" data-result="NO_MATCH">
      <h2 className="m-0 text-lg font-semibold">{t('sc.match.noMatch.title')}</h2>
      {match.canCreate ? (
        <>
          <p className="m-0 text-sm">{t('sc.match.noMatch.body')}</p>
          <div><Button variant="primary" onClick={() => onCreate({ gtin: digits })}>{t('sc.match.noMatch.continue')}</Button></div>
        </>
      ) : (
        <Notice tone="warning" title={t('sc.match.noMatch.blocked')}>
          {match.createBlockers.map((b) => (
            <span key={b.code} className="block">{blockerKey(b.code) ? t(blockerKey(b.code) ?? 'sc.match.blocker.exists') : b.message}</span>
          ))}
        </Notice>
      )}
    </div>
  );
}
