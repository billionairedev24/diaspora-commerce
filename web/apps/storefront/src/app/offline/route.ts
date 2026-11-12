/* Offline fallback page (/offline), precached by the service worker and shown when a page cannot be fetched. A
   self-contained document in the visitor's language; it contains nothing personal. */
import { dirOf } from '@afrotransact/i18n';
import { offlineHtml } from '@afrotransact/pwa';
import { resolvedVariables } from '@afrotransact/tokens';

import { getLocale, getT } from '@/i18n/server';

export async function GET(): Promise<Response> {
  const [locale, t] = await Promise.all([getLocale(), getT()]);
  const html = offlineHtml({
    lang: locale,
    dir: dirOf(locale),
    title: `${t('pwa.offline.title')} · AfroTransact`,
    heading: t('pwa.offline.heading'),
    body: t('pwa.offline.body'),
    retryLabel: t('pwa.offline.retry'),
    retryHref: '/',
    colors: {
      canvas: resolvedVariables['--at-color-canvas'],
      text: resolvedVariables['--at-color-text'],
      textMuted: resolvedVariables['--at-color-text-muted'],
      actionPrimary: resolvedVariables['--at-color-action-primary'],
      actionPrimaryText: resolvedVariables['--at-color-action-primary-text']
    },
    fontFamily: resolvedVariables['--at-font-sans']
  });
  return new Response(html, { headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'private, no-cache', 'X-Robots-Tag': 'noindex' } });
}
