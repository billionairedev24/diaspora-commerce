'use client';

import { ToastProvider } from '@afrotransact/ui';
import { QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode } from 'react';

import { LolaProvider } from '@/components/shell/LolaLauncher';

import { CartAnnouncer } from '../cart/announcer';
import { getQueryClient } from './getQueryClient';

export function Providers({ children }: { children: ReactNode }) {
  const queryClient = getQueryClient();
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <CartAnnouncer>
          <LolaProvider>{children}</LolaProvider>
        </CartAnnouncer>
      </ToastProvider>
    </QueryClientProvider>
  );
}
