'use client';

import { intlTag } from '@afrotransact/i18n';
import { useLocale } from '@afrotransact/i18n/react';
import { ToastProvider } from '@afrotransact/ui';
import { QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode } from 'react';

import { AccountEnv } from '@/components/account/AccountEnv';
import { setFormatLocale } from '@/lib/format';

import { getQueryClient } from './getQueryClient';

export function Providers({ children }: { children: ReactNode }) {
  // Before children render, so every money/date formatted below uses the active language.
  setFormatLocale(intlTag(useLocale()));
  return (
    <QueryClientProvider client={getQueryClient()}>
      <ToastProvider>
        <AccountEnv>{children}</AccountEnv>
      </ToastProvider>
    </QueryClientProvider>
  );
}
