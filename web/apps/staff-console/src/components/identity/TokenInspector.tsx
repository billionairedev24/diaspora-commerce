'use client';

import { type IdentityTokenInspection, staffQueries } from '@afrotransact/api-client';
import { Button, Field, fieldStyles } from '@afrotransact/ui';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';

const pretty = (o: unknown) => JSON.stringify(o, null, 2);

/** Verdict, header, claims and scopes of an inspected token. The token itself is not part of the answer. */
function Result({ r }: { r: IdentityTokenInspection }) {
  const t = useT();
  const expiry = r.expiresAt
    ? t(r.expired ? 'st.identity.inspector.expired' : 'st.identity.inspector.expires', { when: dateTime(r.expiresAt) })
    : t('st.identity.inspector.noExpiry');
  return (
    <section aria-label={t('st.identity.inspector.result')} className="flex flex-col gap-4">
      <Notice tone={r.valid ? 'success' : 'danger'} title={r.valid ? t('st.identity.inspector.valid') : t('st.identity.inspector.invalid')} role="alert">
        {r.valid ? t('st.identity.inspector.validBody') : r.wellFormed ? t('st.identity.inspector.invalidBody') : t('st.identity.inspector.notAJwt')}
      </Notice>
      {r.wellFormed ? (
        <dl className="m-0 grid grid-cols-[minmax(0,1fr)_minmax(0,2fr)] gap-x-3 gap-y-1.5 text-sm">
          <dt className="text-muted">{t('st.identity.inspector.signature')}</dt>
          <dd className="m-0">{r.signatureValid ? t('st.identity.inspector.verified') : t('st.identity.inspector.failed')}</dd>
          <dt className="text-muted">{t('st.identity.inspector.issuer')}</dt>
          <dd className="m-0">{r.issuerValid ? t('st.identity.inspector.verified') : t('st.identity.inspector.failed')}</dd>
          <dt className="text-muted">{t('st.identity.inspector.expiry')}</dt>
          <dd className="m-0">{expiry}</dd>
          <dt className="text-muted">{t('st.identity.inspector.keyId')}</dt>
          <dd className="m-0 font-mono text-xs [overflow-wrap:anywhere]">{r.keyId ?? '—'}</dd>
          <dt className="text-muted">{t('st.identity.inspector.scopes')}</dt>
          <dd className="m-0 [overflow-wrap:anywhere]">{r.scopes.length ? r.scopes.join(', ') : t('st.identity.inspector.noScopes')}</dd>
        </dl>
      ) : null}
      {r.problems.length ? (
        <div className="flex flex-col gap-1">
          <h4 className="m-0 text-sm font-bold">{t('st.identity.inspector.problems')}</h4>
          <ul className="m-0 pl-5 text-sm">
            {r.problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </div>
      ) : null}
      {r.wellFormed ? (
        <div className="grid gap-4 md:grid-cols-2">
          <div className="flex min-w-0 flex-col gap-1">
            <h4 className="m-0 text-sm font-bold">{t('st.identity.inspector.header')}</h4>
            <pre className="m-0 max-h-72 overflow-auto rounded-md border border-border bg-surface-muted p-3 text-xs">{pretty(r.header)}</pre>
          </div>
          <div className="flex min-w-0 flex-col gap-1">
            <h4 className="m-0 text-sm font-bold">{t('st.identity.inspector.claims')}</h4>
            <pre className="m-0 max-h-72 overflow-auto rounded-md border border-border bg-surface-muted p-3 text-xs">{pretty(r.claims)}</pre>
          </div>
        </div>
      ) : null}
    </section>
  );
}

/** The OIDC discovery document and the JWKS identity publishes (public keys only). */
function Documents() {
  const t = useT();
  const api = staffApi();
  const docs = useQuery(staffQueries.identityDocuments(api));
  return (
    <section aria-label={t('st.identity.documents.title')} className="flex flex-col gap-3">
      <h3 className="m-0 text-md font-bold">{t('st.identity.documents.title')}</h3>
      <p className="m-0 text-sm text-muted">{t('st.identity.documents.intro')}</p>
      {docs.isPending ? <Spinner /> : null}
      {docs.isError ? <ErrorState error={docs.error} onRetry={() => void docs.refetch()} /> : null}
      {docs.data ? (
        <div className="flex flex-col gap-2">
          <details className="rounded-md border border-border px-3 py-2">
            <summary className="cursor-pointer text-sm font-semibold">{t('st.identity.documents.discovery')}</summary>
            <pre className="mt-2 max-h-80 overflow-auto text-xs">{pretty(docs.data.discovery)}</pre>
          </details>
          <details className="rounded-md border border-border px-3 py-2">
            <summary className="cursor-pointer text-sm font-semibold">{t('st.identity.documents.jwks')}</summary>
            <pre className="mt-2 max-h-80 overflow-auto text-xs">{pretty(docs.data.jwks)}</pre>
          </details>
        </div>
      ) : null}
    </section>
  );
}

/**
 * Identity & access › Token inspector (S291). The pasted token is sent in the request body to be decoded and verified in
 * memory; it is not kept anywhere: the input is cleared with "Clear" and the answer never contains it.
 */
export function TokenInspector() {
  const t = useT();
  const api = staffApi();
  const [token, setToken] = useState('');
  const inspect = useMutation({ mutationFn: (value: string) => api.inspectIdentityToken(value.trim()), retry: false });
  return (
    <div className="flex flex-col gap-6">
      <form
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (token.trim()) inspect.mutate(token);
        }}
      >
        <h3 className="m-0 text-md font-bold">{t('st.identity.inspector.title')}</h3>
        <p className="m-0 text-sm text-muted">{t('st.identity.inspector.intro')}</p>
        <Field label={t('st.identity.inspector.token')} id="inspector-token">
          {(a11y) => (
            <textarea
              {...a11y}
              className={fieldStyles.control}
              rows={5}
              maxLength={16_384}
              spellCheck={false}
              autoComplete="off"
              autoCapitalize="off"
              value={token}
              onChange={(e) => setToken(e.currentTarget.value)}
            />
          )}
        </Field>
        <div className="flex flex-wrap gap-2">
          <Button type="submit" loading={inspect.isPending} disabled={!token.trim()}>
            {t('st.identity.inspector.inspect')}
          </Button>
          <Button
            type="button"
            variant="neutral"
            onClick={() => {
              setToken('');
              inspect.reset();
            }}
          >
            {t('st.identity.inspector.clear')}
          </Button>
        </div>
      </form>
      {inspect.isError ? (
        <Notice tone="danger" title={t('st.identity.inspector.failedTitle')} role="alert">
          {problemMessage(inspect.error)}
        </Notice>
      ) : null}
      {inspect.data ? <Result r={inspect.data} /> : null}
      <Documents />
    </div>
  );
}
