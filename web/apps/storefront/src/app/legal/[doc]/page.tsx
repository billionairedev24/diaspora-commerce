import { isApiError, type LegalDocumentKey, legalDocumentKeyOf, legalHref, type LegalPage, legalQueries } from '@afrotransact/api-client';
import { dehydrate, HydrationBoundary } from '@tanstack/react-query';
import { type Metadata } from 'next';
import { notFound, permanentRedirect } from 'next/navigation';

import { CookiePreferences } from '@/components/legal/CookiePreferences';
import { LegalDocumentView, legalTitle } from '@/components/legal/LegalDocumentView';
import { PrivacyChoices } from '@/components/legal/PrivacyChoices';
import { getLocale, getT } from '@/i18n/server';
import { siteQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

type Props = { params: Promise<{ doc: string }>; searchParams: Promise<{ version?: string | string[] }> };

const VERSION = /^\d{1,4}(\.\d{1,4}){0,2}$/;

/** Canonical key for the slug: unknown → 404, legacy alias (/legal/terms, /legal/sms…) → permanent redirect. */
async function resolve({ params, searchParams }: Props): Promise<{ key: LegalDocumentKey; version: string | undefined }> {
  const [{ doc }, sp] = await Promise.all([params, searchParams]);
  const key = legalDocumentKeyOf(decodeURIComponent(doc));
  const raw = Array.isArray(sp.version) ? sp.version[0] : sp.version;
  const version = raw && VERSION.test(raw) ? raw : undefined;
  if (!key) notFound();
  if (key !== doc) permanentRedirect(version ? `${legalHref(key)}?version=${version}` : legalHref(key));
  return { key, version };
}

async function load(key: LegalDocumentKey, locale: string, version: string | undefined) {
  const qc = getQueryClient();
  const api = await serverApi();
  try {
    const page: LegalPage = await qc.fetchQuery(legalQueries.document(api, key, locale, version));
    return { qc, api, page };
  } catch (e) {
    if (isApiError(e) && e.status === 404) notFound();
    throw e;
  }
}

export async function generateMetadata(props: Props): Promise<Metadata> {
  const { key, version } = await resolve(props);
  const t = await getT();
  const title = legalTitle(t, key);
  return {
    title: version ? `${title} · ${t('legal.version', { version })}` : title,
    description: `${t('legal.center')} · ${title}`,
    alternates: { canonical: legalHref(key) },
    robots: version ? { index: false } : undefined
  };
}

/** Legal & privacy center: every policy page, served from content-service (versioned, editable without a deploy). */
export default async function LegalDocumentPage(props: Props) {
  const { key, version } = await resolve(props);
  const [locale, t] = await Promise.all([getLocale(), getT()]);
  const { qc, api, page } = await load(key, locale, version);
  let interactive = null;
  if (key === 'cookies') {
    await qc.prefetchQuery(legalQueries.cookiePolicy(api));
    interactive = <CookiePreferences />;
  } else if (key === 'privacy-choices') {
    const config = await qc.fetchQuery(siteQueries.config(api)).catch(() => undefined);
    interactive = <PrivacyChoices privacyEmail={config?.contacts.privacyEmail ?? 'privacy@afrotransact.com'} />;
  }
  return (
    <HydrationBoundary state={dehydrate(qc)}>
      <LegalDocumentView docKey={key} page={page} locale={locale} t={t}>
        {interactive}
      </LegalDocumentView>
    </HydrationBoundary>
  );
}
