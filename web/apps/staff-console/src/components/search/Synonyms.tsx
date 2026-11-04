'use client';

import { isApiError, staffSearchKeys, staffSearchQueries } from '@afrotransact/api-client';
import { type StaffSynonymRule } from '@afrotransact/api-client/staff-search';
import { Button, Dialog, Sheet, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { ErrorState, problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';

/** "grand boubou, bubu" → ["grand boubou", "bubu"]. */
const wordsOf = (text: string): string[] => text.split(',').map((w) => w.trim()).filter(Boolean);

/**
 * Add a synonym for a query that found nothing (shared Sheet). The query is fixed; the staff member says which words
 * should find the same products. Search's own validation comes back on the words field. Save stays off until there is
 * something to save.
 */
export function AddSynonymSheet({ query, onClose }: { query: string | null; onClose: () => void }) {
  const t = useT();
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [words, setWords] = useState('');
  const [serverError, setServerError] = useState<string | null>(null);
  const open = query !== null;
  const typed = wordsOf(words);

  const close = () => {
    setWords('');
    setServerError(null);
    onClose();
  };
  const save = useMutation({
    mutationFn: () => api.search.addSynonym([query ?? '', ...typed]),
    onSuccess: (rule) => {
      void qc.invalidateQueries({ queryKey: staffSearchKeys.synonyms() });
      toast.show(t('st.search.synonym.saved', { terms: rule.terms.join(' = ') }));
      close();
    },
    onError: (e) => setServerError(isApiError(e) && e.fieldErrors.terms ? e.fieldErrors.terms : problemMessage(e))
  });

  return (
    <Sheet open={open} onOpenChange={(o) => !o && close()} title={t('st.search.synonym.title')} width={440} closeLabel={t('st.search.close')}>
      <form
        className="flex flex-col gap-4"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          if (typed.length > 0 && !save.isPending) save.mutate();
        }}
      >
        <p className="m-0 text-muted">{t('st.search.synonym.intro', { query: query ?? '' })}</p>
        <TextField label={t('st.search.synonym.query')} value={query ?? ''} readOnly />
        <TextField
          label={t('st.search.synonym.words')}
          value={words}
          onChange={(e) => {
            setWords(e.currentTarget.value);
            setServerError(null);
          }}
          error={serverError}
          help={t('st.search.synonym.help')}
          autoComplete="off"
        />
        {typed.length > 0 ? (
          <p className="m-0 text-sm" data-testid="synonym-preview">
            <strong>{t('st.search.synonym.preview')}</strong> {[query ?? '', ...typed].join(' = ')}
          </p>
        ) : null}
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="neutral" onClick={close}>
            {t('st.search.cancel')}
          </Button>
          <Button type="submit" disabled={typed.length === 0 || save.isPending}>
            {save.isPending ? t('st.search.synonym.saving') : t('st.search.synonym.save')}
          </Button>
        </div>
      </form>
    </Sheet>
  );
}

/** Synonym rules: the words search treats as the same thing. Deleting needs search:synonyms.edit. */
export function SynonymRules({ canEdit }: { canEdit: boolean }) {
  const t = useT();
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const query = useQuery(staffSearchQueries.synonyms(api.search));
  const [target, setTarget] = useState<StaffSynonymRule | null>(null);
  const [error, setError] = useState<string | null>(null);
  const remove = useMutation({
    mutationFn: (id: string) => api.search.deleteSynonym(id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: staffSearchKeys.synonyms() });
      toast.show(t('st.search.rules.deleted'));
      setTarget(null);
    },
    onError: (e) => setError(problemMessage(e))
  });
  const rules = query.data?.items ?? [];
  const terms = (r: StaffSynonymRule) => r.terms.join(' = ');

  return (
    <div className="flex flex-col gap-3">
      <p className="m-0 max-w-[80ch] text-muted">{t('st.search.explain.rules')}</p>
      {query.isPending ? (
        <p role="status" className="m-0 text-sm text-muted">
          {t('st.search.loading')}
        </p>
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : rules.length === 0 ? (
        <p className="m-0 py-6 text-center text-muted">{t('st.search.rules.empty')}</p>
      ) : (
        <ul aria-label={t('st.search.tab.rules')} className="m-0 flex list-none flex-col divide-y divide-border border-y border-border p-0">
          {rules.map((r) => (
            <li key={r.id} data-testid="synonym-rule" className="flex flex-wrap items-center gap-x-4 gap-y-2 py-3">
              <span className="min-w-0 flex-1 basis-60">
                {r.terms.map((term, i) => (
                  <span key={term}>
                    {i > 0 ? <span aria-hidden className="px-1.5 text-muted">=</span> : null}
                    <strong className="font-semibold">{term}</strong>
                  </span>
                ))}
              </span>
              {canEdit ? (
                <Button
                  variant="neutral"
                  size="sm"
                  aria-label={t('st.search.rules.deleteFor', { terms: terms(r) })}
                  onClick={() => {
                    setError(null);
                    setTarget(r);
                  }}
                >
                  {t('st.search.rules.delete')}
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      {!canEdit && rules.length > 0 ? <p className="m-0 text-xs text-subtle">{t('st.search.rules.readOnly')}</p> : null}
      <Dialog open={target !== null} onOpenChange={(o) => !o && setTarget(null)} title={t('st.search.rules.confirmTitle')} description={target ? t('st.search.rules.confirmBody', { terms: terms(target) }) : undefined} closeLabel={t('st.search.close')}>
        {error ? (
          <p role="alert" className="mt-0 mb-3 text-sm text-danger">
            {error}
          </p>
        ) : null}
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="neutral" onClick={() => setTarget(null)}>
            {t('st.search.cancel')}
          </Button>
          <Button variant="primary" disabled={remove.isPending} onClick={() => target && remove.mutate(target.id)}>
            {t('st.search.rules.delete')}
          </Button>
        </div>
      </Dialog>
    </div>
  );
}
