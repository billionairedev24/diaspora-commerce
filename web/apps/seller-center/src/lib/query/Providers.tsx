'use client';

import { ToastProvider } from '@afrotransact/ui';
import { QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode } from 'react';

import { getQueryClient } from './getQueryClient';

export function Providers({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={getQueryClient()}>
      <ToastProvider>{children}</ToastProvider>
    </QueryClientProvider>
  );
}
