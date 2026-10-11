'use client';

import { ToastProvider } from '@afrotransact/ui';
import { QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode } from 'react';

import { LolaProvider } from '@/components/shell/LolaLauncher';
import { type ConsentState } from '@/lib/consent/consent';
import { ConsentProvider } from '@/lib/consent/ConsentProvider';

import { CartAnnouncer } from '../cart/announcer';
import { getQueryClient } from './getQueryClient';

export function Providers({ children, consent = null, gpc = false }: { children: ReactNode; consent?: ConsentState | null; gpc?: boolean }) {
  const queryClient = getQueryClient();
  return (
    <QueryClientProvider client={queryClient}>
      <ConsentProvider initial={consent} initialGpc={gpc}>
        <ToastProvider>
          <CartAnnouncer>
            <LolaProvider>{children}</LolaProvider>
          </CartAnnouncer>
        </ToastProvider>
      </ConsentProvider>
    </QueryClientProvider>
  );
}
