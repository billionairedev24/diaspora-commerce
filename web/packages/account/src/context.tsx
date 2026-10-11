'use client';

import { type SettingsApiClient } from '@afrotransact/api-client';
import { type ComponentType, createContext, type ReactNode, useContext } from 'react';

export interface AccountLinkProps {
  href: string;
  className?: string;
  children: ReactNode;
  'aria-current'?: 'page' | undefined;
}

/** What the account screens need from the app they run in. */
export interface AccountEnv {
  /** Same-origin settings client (the app's /api/bff proxy). */
  api: SettingsApiClient;
  /** In-app link (next/link in the web apps). */
  Link: ComponentType<AccountLinkProps>;
  /** Where the profile lives in this app (text-message setup sends people there to add a phone). */
  profileHref: string;
}

const Ctx = createContext<AccountEnv | null>(null);

export function AccountProvider({ env, children }: { env: AccountEnv; children: ReactNode }) {
  return <Ctx.Provider value={env}>{children}</Ctx.Provider>;
}

/** The environment, or null outside <AccountProvider> (optional helpers such as AccountFormatPreferences). */
export function useOptionalAccountEnv(): AccountEnv | null {
  return useContext(Ctx);
}

export function useAccountEnv(): AccountEnv {
  const v = useContext(Ctx);
  if (!v) throw new Error('Account screens must be rendered inside <AccountProvider>');
  return v;
}
