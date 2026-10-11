'use client';

import { type AccountLinkProps, AccountProvider } from '@afrotransact/account';
import { createSettingsApiClient, type SettingsApiClient } from '@afrotransact/api-client';
import NextLink from 'next/link';
import { type ReactNode } from 'react';

import { BFF_BASE_PATH } from '@/lib/config';

let client: SettingsApiClient | null = null;

/** Browser client for the shared account settings routes (/v1/account/…, same-origin BFF proxy). */
export function settingsApi(): SettingsApiClient {
  client ??= createSettingsApiClient({ baseUrl: BFF_BASE_PATH });
  return client;
}

function Link({ href, className, children, ...rest }: AccountLinkProps) {
  return (
    <NextLink href={href} className={className} {...rest}>
      {children}
    </NextLink>
  );
}

/** The shared account screens inside the storefront. */
export function AccountEnv({ children }: { children: ReactNode }) {
  return <AccountProvider env={{ api: settingsApi(), Link, profileHref: '/account/profile' }}>{children}</AccountProvider>;
}
