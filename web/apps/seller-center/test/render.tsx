import { type SellerSession } from '@afrotransact/api-client';
import { ToastProvider } from '@afrotransact/ui';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { type ReactElement } from 'react';

import { SellerSessionProvider } from '@/lib/session';

import { mockApi } from './mock-api';

const client = () => new QueryClient({ defaultOptions: { queries: { retry: false } } });

export function renderWithProviders(ui: ReactElement) {
  return render(
    <QueryClientProvider client={client()}>
      <ToastProvider>{ui}</ToastProvider>
    </QueryClientProvider>
  );
}

/** Renders a module screen inside the session context of the mock organisation (fetched from the mocks first). */
export async function renderScreen(ui: ReactElement, orgId?: string) {
  const api = mockApi();
  let session: SellerSession = await api.getSession();
  if (orgId && session.activeOrg?.id !== orgId) session = await api.setActiveOrg(orgId);
  return renderWithProviders(<SellerSessionProvider session={session}>{ui}</SellerSessionProvider>);
}
