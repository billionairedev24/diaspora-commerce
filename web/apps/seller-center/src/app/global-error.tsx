'use client';

import '@afrotransact/tokens/tokens.css';
import '@afrotransact/ui/global.css';

import { useReportError } from '@afrotransact/telemetry/react';

/*
 * Last-resort boundary (S178): replaces the root layout when it fails, so it renders its own <html>, cannot rely on
 * providers (no locale catalogue), and reports the error to telemetry. Text stays short and in English.
 */
export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useReportError(error, { boundary: 'global' });
  return (
    <html lang="en">
      <body>
        <main id="main" role="alert" style={{ padding: 'var(--at-space-8) var(--at-space-4)', display: 'flex', flexDirection: 'column', gap: 'var(--at-space-4)', alignItems: 'flex-start' }}>
          <h1 style={{ margin: 0 }}>Something went wrong</h1>
          <p style={{ margin: 0, color: 'var(--at-color-text-muted)' }}>Please try again. If it keeps happening, reload the page.</p>
          <button type="button" onClick={reset} style={{ minHeight: 44, padding: '0 var(--at-space-5)', borderRadius: 'var(--at-radius-pill)', border: 0, background: 'var(--at-color-action-primary)', color: 'var(--at-color-action-primary-text)', fontWeight: 700 }}>
            Try again
          </button>
        </main>
      </body>
    </html>
  );
}
