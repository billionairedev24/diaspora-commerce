'use client';

import { staffKeys, type TaxRatesImportResult } from '@afrotransact/api-client';
import { Button, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type ChangeEvent, type FormEvent, useRef, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { staffApi } from '@/lib/api';

/** CSV header expected by tax-returns (USA pilot; country column reserved for later markets). */
export const TAX_RATES_CSV_HEADER =
  'kind,country,state,name,zipPrefix,generalRate,groceryRate,preparedFoodRate,shippingTaxable,facilitator,localRate,appliesToGrocery';

export const TAX_RATES_CSV_TEMPLATE = `${TAX_RATES_CSV_HEADER}
STATE,US,TX,Texas,,0.0625,0,0.0625,true,true,,
LOCAL,US,TX,Houston (770),770,,,,,,0.02,false
`;

function downloadTemplate() {
  const blob = new Blob([TAX_RATES_CSV_TEMPLATE], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'tax-rates-us-template.csv';
  a.click();
  URL.revokeObjectURL(url);
}

/** Paste or upload Comptroller-aligned state rules and local ZIP rates into tax-returns. */
export function TaxRatesImportPanel() {
  const qc = useQueryClient();
  const toast = useToast();
  const fileRef = useRef<HTMLInputElement>(null);
  const [csv, setCsv] = useState('');
  const [result, setResult] = useState<TaxRatesImportResult | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);

  const importRates = useMutation({
    mutationFn: (text: string) => staffApi().importTaxRates(text),
    onSuccess: (r) => {
      setResult(r);
      toast.show(`Imported ${r.statesUpserted} state rule(s) and ${r.localsUpserted} local rate(s).`);
      void qc.invalidateQueries({ queryKey: staffKeys.resource('tax-rates') });
    }
  });

  const onFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    setFileError(null);
    try {
      const text = await file.text();
      setCsv(text);
      setResult(null);
    } catch {
      setFileError('Could not read that file.');
    }
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setResult(null);
    importRates.mutate(csv.trim());
  };

  return (
    <Card title="Import tax rates (CSV)">
      <p className="m-0 text-sm text-muted">
        Bulk-update state rules and city or ZIP-prefix local rates. USA is the pilot market; use two-letter state codes and
        decimal rates (for example 0.0625 for 6.25%). Edit individual rows in the table below, or import thousands of locals
        from a spreadsheet.
      </p>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="secondary" size="sm" onClick={downloadTemplate}>
          Download template
        </Button>
        <Button type="button" variant="secondary" size="sm" onClick={() => { setCsv(TAX_RATES_CSV_TEMPLATE); setResult(null); }}>
          Load example into editor
        </Button>
        <Button type="button" variant="secondary" size="sm" onClick={() => fileRef.current?.click()}>
          Choose CSV file
        </Button>
        <input ref={fileRef} type="file" accept=".csv,text/csv" className="sr-only" onChange={onFile} />
      </div>
      {fileError ? <Notice tone="bad">{fileError}</Notice> : null}
      <form className="flex flex-col gap-3" onSubmit={submit}>
        <label className="text-sm font-medium" htmlFor="tax-rates-csv">
          CSV text
        </label>
        <textarea
          id="tax-rates-csv"
          className="min-h-[12rem] w-full resize-y rounded-md border border-border bg-canvas px-3 py-2 font-mono text-xs leading-relaxed"
          value={csv}
          spellCheck={false}
          onChange={(e) => { setCsv(e.currentTarget.value); setResult(null); }}
          placeholder={TAX_RATES_CSV_HEADER}
        />
        <div className="flex flex-wrap items-center gap-2">
          <Button type="submit" disabled={!csv.trim() || importRates.isPending}>
            {importRates.isPending ? 'Importing…' : 'Import rates'}
          </Button>
          {importRates.isError ? <ErrorState error={importRates.error} onRetry={() => importRates.mutate(csv.trim())} /> : null}
        </div>
      </form>
      {result ? (
        <dl className="m-0 grid grid-cols-[repeat(auto-fit,minmax(min(100%,160px),1fr))] gap-3 text-sm" data-testid="tax-import-result">
          <div>
            <dt className="text-xs text-muted">State rules</dt>
            <dd className="m-0 text-lg font-semibold">{result.statesUpserted}</dd>
          </div>
          <div>
            <dt className="text-xs text-muted">Local rates</dt>
            <dd className="m-0 text-lg font-semibold">{result.localsUpserted}</dd>
          </div>
          {result.warnings.length ? (
            <div className="col-span-full">
              <dt className="text-xs text-muted">Warnings</dt>
              <dd className="m-0">
                <ul className="m-0 list-disc pl-5 text-sm">
                  {result.warnings.map((w) => (
                    <li key={w}>{w}</li>
                  ))}
                </ul>
              </dd>
            </div>
          ) : null}
        </dl>
      ) : null}
      {importRates.isError && !importRates.isPending ? (
        <p className="m-0 text-sm text-muted">{problemMessage(importRates.error)}</p>
      ) : null}
    </Card>
  );
}
