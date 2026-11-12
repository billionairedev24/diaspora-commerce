'use client';

import { type CatalogMatch, matchingQueries } from '@afrotransact/api-client';
import { Button, ChipGroup, FormActions, FormFields, Message, TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

import { type ProductFormStart } from '../form-model';
import { ChangeRequestsList } from './ChangeRequestsList';
import { blockerKey, digitsOf, type FindMode, MAX_BARCODE_DIGITS, requestOf } from './match-model';
import { MatchCard } from './MatchCard';

const DEBOUNCE_MS = 400;

interface Props {
  /** The seller says yes to a matched page: the offer-only form. */
  onSell: (match: CatalogMatch, atin: string) => void;
  /** No page: the full form, with the barcode (or "no barcode") and the brand typed here. */
  onCreate: (start: ProductFormStart) => void;
}

/**
 * Step 0 of "Add a product": the barcode (or brand and part number, or no barcode). The lookup runs by itself after the 12th to 14th digit
 * and answers in plain words: the existing product (sell it, or say it is not yours), no page yet (add it with photos), a barcode that does
 * not look right (with the fix), or a number that is not a product barcode. Nothing here shows an id.
 */
export function BarcodeStep({ onSell, onCreate }: Props) {
  const t = useT();
  const { orgId } = useSeller();
  const api = sellerApi().matching;
  const [mode, setMode] = useState<FindMode>('barcode');
  const [code, setCode] = useState('');
  const [brand, setBrand] = useState('');
  const [mpn, setMpn] = useState('');
  const [asked, setAsked] = useState(false);
  // The lookup follows what was typed after a short pause, so every keystroke is not a request.
  const [typed, setTyped] = useState({ mode, code, brand, mpn, asked });
  useEffect(() => {
    const h = window.setTimeout(() => setTyped({ mode, code, brand, mpn, asked }), DEBOUNCE_MS);
    return () => window.clearTimeout(h);
  }, [mode, code, brand, mpn, asked]);
  const req = requestOf(typed.mode, typed, typed.asked);
  const q = useQuery(matchingQueries.match(api, orgId, req ?? {}, req !== null));
  const waiting = req !== null && q.isFetching;
  const match = req !== null && !q.isError ? q.data : undefined;
  const digits = digitsOf(code);

  const choose = (m: FindMode) => {
    setMode(m);
    setAsked(false);
  };
  const edit = (set: (v: string) => void) => (e: { target: { value: string } }) => {
    set(e.target.value);
    setAsked(false);
  };

  return (
    <>
      <PageHeader title={t('sc.match.title')} description={t('sc.match.lead')} />
      <div className="flex max-w-3xl flex-col gap-6" data-tour="barcode-step">
        <ChipGroup<FindMode>
          label={t('sc.match.mode.label')}
          value={mode}
          onChange={choose}
          options={[{ value: 'barcode', label: t('sc.match.haveBarcode') }, { value: 'brand', label: t('sc.match.byBrandMpn') }, { value: 'none', label: t('sc.match.noBarcode') }]}
        />
        {mode === 'barcode' ? (
          <FormFields
            label={t('sc.match.haveBarcode')}
            onSubmit={(e) => {
              e.preventDefault();
              setAsked(true);
              setTyped({ mode, code, brand, mpn, asked: true });
              // "Check barcode" always asks again: the page may have been approved since the last answer for the same number.
              if (req !== null) void q.refetch();
            }}
          >
            <TextField
              id="match-barcode"
              data-tour="barcode-input"
              label={t('sc.match.barcode.label')}
              help={t('sc.match.barcode.help')}
              inputMode="numeric"
              autoComplete="off"
              maxLength={MAX_BARCODE_DIGITS + 6}
              value={code}
              onChange={edit(setCode)}
            />
            <FormActions>
              <Button type="submit" variant="neutral" disabled={digits.length < 8}>{t('sc.match.check')}</Button>
            </FormActions>
          </FormFields>
        ) : null}
        {mode === 'brand' ? (
          <FormFields label={t('sc.match.byBrandMpn')} onSubmit={(e) => e.preventDefault()}>
            <TextField id="match-brand" label={t('sc.match.brand.label')} autoComplete="off" maxLength={120} value={brand} onChange={edit(setBrand)} />
            <TextField id="match-mpn" label={t('sc.match.mpn.label')} help={t('sc.match.mpn.help')} autoComplete="off" maxLength={80} value={mpn} onChange={edit(setMpn)} />
          </FormFields>
        ) : null}
        {mode === 'none' ? (
          <FormFields label={t('sc.match.noBarcode')} onSubmit={(e) => { e.preventDefault(); onCreate({ noGtin: true, brand }); }}>
            <p className="m-0 text-sm">{t('sc.match.noBarcode.body')}</p>
            <p className="m-0 text-sm text-muted">{t('sc.match.exemptWait')}</p>
            <TextField id="match-brand" label={t('sc.match.brand.label')} autoComplete="off" maxLength={120} value={brand} onChange={edit(setBrand)} help={t('sc.match.noBarcode.brandHelp')} />
            <FormActions>
              <Button type="submit" variant="primary">{t('sc.match.noBarcode.continue')}</Button>
            </FormActions>
          </FormFields>
        ) : null}

        {mode !== 'none' ? (
          <div aria-live="polite" className="flex flex-col gap-4" data-testid="match-result">
            {waiting && !q.data ? <Spinner label={t('sc.match.checking')} /> : null}
            {req !== null && q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
            {match ? (
              <Result
                match={match}
                mode={mode}
                digits={digits}
                brand={brand}
                onUse={(fixed) => { setCode(fixed); setAsked(false); }}
                onSell={onSell}
                onCreate={onCreate}
              />
            ) : null}
          </div>
        ) : null}
        <ChangeRequestsList />
      </div>
    </>
  );
}

interface ResultProps {
  match: CatalogMatch;
  mode: FindMode;
  digits: string;
  brand: string;
  onUse: (code: string) => void;
  onSell: Props['onSell'];
  onCreate: Props['onCreate'];
}

function Result({ match, mode, digits, brand, onUse, onSell, onCreate }: ResultProps) {
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
  const start: ProductFormStart = mode === 'barcode' ? { gtin: digits, brand } : { noGtin: true, brand };
  return (
    <div className="flex flex-col gap-3" data-result="NO_MATCH">
      <h2 className="m-0 text-lg font-semibold">{t('sc.match.noMatch.title')}</h2>
      {match.canCreate ? (
        <>
          <p className="m-0 text-sm">{mode === 'barcode' ? t('sc.match.noMatch.body') : t('sc.match.noMatch.bodyBrand')}</p>
          <div><Button variant="primary" onClick={() => onCreate(start)}>{t('sc.match.noMatch.continue')}</Button></div>
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
