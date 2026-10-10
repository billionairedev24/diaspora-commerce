import '@afrotransact/tokens/fonts.css';
import '@afrotransact/tokens/tokens.css';
import '@afrotransact/ui/global.css';
import './app.css';

import { dirOf } from '@afrotransact/i18n';
import { I18nProvider } from '@afrotransact/i18n/react';
import { WebVitalsReporter } from '@afrotransact/telemetry/next/components';
import { resolvedVariables } from '@afrotransact/tokens';
import { type Metadata, type Viewport } from 'next';
import { type ReactNode } from 'react';

import { messagesFor } from '@/i18n/catalogs';
import { getLocale, getT } from '@/i18n/server';
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

export const viewport: Viewport = { width: 'device-width', initialScale: 1, themeColor: resolvedVariables['--at-color-header-bg'] };

export default async function RootLayout({ children }: { children: ReactNode }) {
  const locale = await getLocale();
  const t = await getT();
  return (
    <html lang={locale} dir={dirOf(locale)}>
      <body>
        <a href="#main" className="at-skip-link">
          {t('a11y.skip')}
        </a>
        <I18nProvider locale={locale} messages={messagesFor(locale)}>
          <WebVitalsReporter />
          <Providers>{children}</Providers>
        </I18nProvider>
      </body>
    </html>
  );
}
