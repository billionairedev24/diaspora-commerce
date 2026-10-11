import '@afrotransact/tokens/fonts.css';
import '@afrotransact/tokens/tokens.css';
import '@afrotransact/ui/global.css';
import './globals.css';

import { dirOf } from '@afrotransact/i18n';
import { I18nProvider } from '@afrotransact/i18n/react';
import { WebVitalsReporter } from '@afrotransact/telemetry/next/components';
import { resolvedVariables } from '@afrotransact/tokens';
import { type Metadata, type Viewport } from 'next';
import { type ReactNode } from 'react';

import { messagesFor } from '@/i18n/catalogs';
import { getLocale, getT } from '@/i18n/server';
import { docsPortalUrl, storefrontUrl } from '@/lib/config';
import { Providers } from '@/lib/query/Providers';
import { RuntimeConfigProvider } from '@/lib/runtime-config';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return {
    title: { default: t('st.meta.title'), template: `%s · AfroTransact ${t('st.meta.title')}` },
    description: t('st.meta.description'),
    applicationName: 'AfroTransact Staff console',
    icons: { icon: '/logo-mark.svg' },
    robots: { index: false, follow: false }
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
          {t('st.layout.skip')}
        </a>
        <I18nProvider locale={locale} messages={messagesFor(locale)}>
          <WebVitalsReporter />
          <RuntimeConfigProvider value={{ storefrontUrl: storefrontUrl(), docsUrl: docsPortalUrl() }}>
            <Providers>{children}</Providers>
          </RuntimeConfigProvider>
        </I18nProvider>
      </body>
    </html>
  );
}
