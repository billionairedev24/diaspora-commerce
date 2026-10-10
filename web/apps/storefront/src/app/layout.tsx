import '@afrotransact/tokens/fonts.css';
import '@afrotransact/tokens/tokens.css';
import '@afrotransact/ui/global.css';
import './app.css';

import { resolvedVariables } from '@afrotransact/tokens';
import { type Metadata, type Viewport } from 'next';
import { cookies } from 'next/headers';
import { type ReactNode } from 'react';

import { t } from '@/i18n/t';
import { Providers } from '@/lib/query/Providers';

export const metadata: Metadata = {
  title: { default: t('meta.title'), template: '%s · AfroTransact' },
  description: t('meta.description'),
  applicationName: 'AfroTransact',
  icons: { icon: '/logo-mark.svg' },
  openGraph: { siteName: 'AfroTransact', type: 'website' }
};

export const viewport: Viewport = { width: 'device-width', initialScale: 1, themeColor: resolvedVariables['--at-color-header-bg'] };

const RTL = new Set(['ar']);

export default async function RootLayout({ children }: { children: ReactNode }) {
  const locale = (await cookies()).get('at_locale')?.value ?? 'en';
  return (
    <html lang={locale} dir={RTL.has(locale) ? 'rtl' : 'ltr'}>
      <body>
        <a href="#main" className="at-skip-link">
          {t('a11y.skip')}
        </a>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
