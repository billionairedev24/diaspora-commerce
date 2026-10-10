import '@afrotransact/tokens/fonts.css';
import '@afrotransact/tokens/tokens.css';
import '@afrotransact/ui/global.css';
import './globals.css';

import { resolvedVariables } from '@afrotransact/tokens';
import { type Metadata, type Viewport } from 'next';
import { type ReactNode } from 'react';

import { Providers } from '@/lib/query/Providers';

export const metadata: Metadata = {
  title: { default: 'Staff console', template: '%s · AfroTransact Staff console' },
  description: 'The AfroTransact platform back office for staff.',
  applicationName: 'AfroTransact Staff console',
  icons: { icon: '/logo-mark.svg' },
  robots: { index: false, follow: false }
};

export const viewport: Viewport = { width: 'device-width', initialScale: 1, themeColor: resolvedVariables['--at-color-header-bg'] };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <a href="#main" className="at-skip-link">
          Skip to content
        </a>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
