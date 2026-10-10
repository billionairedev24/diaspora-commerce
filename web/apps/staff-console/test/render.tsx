import { type StaffSession } from '@afrotransact/api-client';
import { ToastProvider } from '@afrotransact/ui';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { type ReactElement } from 'react';

import { StaffSessionProvider } from '@/lib/session';

import { mockApi } from './mock-api';

const client = () => new QueryClient({ defaultOptions: { queries: { retry: false } } });

export function renderWithProviders(ui: ReactElement) {
  return render(
    <QueryClientProvider client={client()}>
      <ToastProvider>{ui}</ToastProvider>
    </QueryClientProvider>
  );
}

/** Renders a screen inside the staff session of the current mock roles (fetched from the mocks first). */
export async function renderScreen(ui: ReactElement) {
  const session: StaffSession = await mockApi().getSession();
  return renderWithProviders(<StaffSessionProvider session={session}>{ui}</StaffSessionProvider>);
}
