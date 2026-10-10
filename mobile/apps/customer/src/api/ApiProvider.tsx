import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createContext, type ReactNode, useContext, useMemo, useState } from 'react';

import { useAuth } from '@/auth/AuthProvider';

import { createCustomerApi, type CustomerApi } from './client';

const ApiContext = createContext<CustomerApi | null>(null);

export function ApiProvider({ children }: { children: ReactNode }) {
  const { tokenSource } = useAuth();
  const api = useMemo(() => createCustomerApi(tokenSource), [tokenSource]);
  const [queryClient] = useState(
    () => new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, retry: 1 }, mutations: { retry: 0 } } })
  );
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
