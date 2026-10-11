import { type SettingsApiClient } from '@afrotransact/api-client';
import { I18nProvider } from '@afrotransact/i18n/react';
import { ToastProvider } from '@afrotransact/ui';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { type ReactNode } from 'react';
import { vi } from 'vitest';

import { type AccountLinkProps, AccountProvider } from '../src';

export function Link({ href, className, children, ...rest }: AccountLinkProps) {
  return (
    <a href={href} className={className} {...rest}>
      {children}
    </a>
  );
}

/** A settings client whose methods are vi.fn()s (override the ones a test needs). */
export function fakeApi(overrides: Partial<Record<keyof SettingsApiClient, unknown>>): SettingsApiClient {
  return new Proxy(overrides, {
    get(target, prop: string) {
      if (!(prop in target)) (target as Record<string, unknown>)[prop] = vi.fn(() => Promise.reject(new Error(`unexpected call ${prop}`)));
      return (target as Record<string, unknown>)[prop];
    }
  }) as unknown as SettingsApiClient;
}

export function renderWith(ui: ReactNode, api: SettingsApiClient) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return {
    qc,
    ...render(
      <I18nProvider locale="en">
        <QueryClientProvider client={qc}>
          <ToastProvider>
            <AccountProvider env={{ api, Link, profileHref: '/account' }}>{ui}</AccountProvider>
          </ToastProvider>
        </QueryClientProvider>
      </I18nProvider>
    )
  };
}
