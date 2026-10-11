import '@afrotransact/tokens/fonts.css';
import '@afrotransact/tokens/tokens.css';
import '@afrotransact/ui/global.css';
import './app.css';

import { dirOf } from '@afrotransact/i18n';
import { I18nProvider } from '@afrotransact/i18n/react';
import { WebVitalsReporter } from '@afrotransact/telemetry/next/components';
import { resolvedVariables } from '@afrotransact/tokens';
import { type Metadata, type Viewport } from 'next';
import { cookies, headers } from 'next/headers';
import { type ReactNode } from 'react';

import { messagesFor } from '@/i18n/catalogs';
import { getLocale, getT } from '@/i18n/server';
import { CONSENT_COOKIE, headerGpc, parseConsent } from '@/lib/consent/consent';
import { ConsentGate } from '@/lib/consent/ConsentProvider';
import { Providers } from '@/lib/query/Providers';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return {
    title: { default: t('meta.title'), template: '%s · AfroTransact' },
    description: t('meta.description'),
    applicationName: 'AfroTransact',
    icons: { icon: '/logo-mark.svg' },
    openGraph: { siteName: 'AfroTransact', type: 'website' }
  };
}

// viewport-fit=cover: the page runs edge to edge on notched phones; sticky bars pad themselves with the safe-area insets.
export const viewport: Viewport = { width: 'device-width', initialScale: 1, viewportFit: 'cover', themeColor: resolvedVariables['--at-color-header-bg'] };

export default async function RootLayout({ children }: { children: ReactNode }) {
  const locale = await getLocale();
  const t = await getT();
  const [jar, h] = await Promise.all([cookies(), headers()]);
  const consent = parseConsent(jar.get(CONSENT_COOKIE)?.value);
  return (
    <html lang={locale} dir={dirOf(locale)}>
      <body>
        <a href="#main" className="at-skip-link">
          {t('a11y.skip')}
        </a>
        <I18nProvider locale={locale} messages={messagesFor(locale)}>
          <Providers consent={consent} gpc={headerGpc(h.get('sec-gpc'))}>
            {/* Page-performance measurement is an analytics cookie category: only with consent (S192). */}
            <ConsentGate category="analytics">
              <WebVitalsReporter />
            </ConsentGate>
            {children}
          </Providers>
        </I18nProvider>
      </body>
    </html>
  );
}
