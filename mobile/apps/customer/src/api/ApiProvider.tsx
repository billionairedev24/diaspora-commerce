import { focusManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createContext, type ReactNode, useContext, useEffect, useMemo, useState } from 'react';
import { AppState, type AppStateStatus, Platform } from 'react-native';

import { useAuth } from '@/auth/AuthProvider';

import { createCustomerApi, type CustomerApi } from './client';
import { retryDelay, shouldRetry } from './network';

const ApiContext = createContext<CustomerApi | null>(null);

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      // Data is shown immediately and refreshed in the background; a screen that comes back after a minute refetches.
      queries: { staleTime: 30_000, gcTime: 10 * 60_000, retry: shouldRetry, retryDelay, refetchOnReconnect: true, refetchOnWindowFocus: true },
      mutations: { retry: 0 }
    }
  });
}

export function ApiProvider({ children }: { children: ReactNode }) {
  const { tokenSource } = useAuth();
  const api = useMemo(() => createCustomerApi(tokenSource), [tokenSource]);
  const [queryClient] = useState(createQueryClient);
  // React Native has no window focus: coming back to the app is the focus, so stale data refreshes then.
  useEffect(() => {
    if (Platform.OS === 'web') return;
    const sub = AppState.addEventListener('change', (s: AppStateStatus) => focusManager.setFocused(s === 'active'));
    return () => sub.remove();
  }, []);
  return (
    <ApiContext.Provider value={api}>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </ApiContext.Provider>
  );
}

export function useApi(): CustomerApi {
  const api = useContext(ApiContext);
  if (!api) throw new Error('useApi outside ApiProvider');
  return api;
}
