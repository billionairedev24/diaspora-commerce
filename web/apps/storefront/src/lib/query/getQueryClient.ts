import { isApiError } from '@afrotransact/api-client';
import { environmentManager, QueryClient } from '@tanstack/react-query';

function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: (count, err) => !(isApiError(err) && err.status < 500) && count < 2,
        refetchOnWindowFocus: false
      },
      dehydrate: { shouldDehydrateQuery: (q) => q.state.status === 'success' }
    }
  });
}

let browserClient: QueryClient | undefined;

/** New client per server request; one shared client in the browser. */
export function getQueryClient(): QueryClient {
  if (environmentManager.isServer()) return makeQueryClient();
  browserClient ??= makeQueryClient();
  return browserClient;
}
