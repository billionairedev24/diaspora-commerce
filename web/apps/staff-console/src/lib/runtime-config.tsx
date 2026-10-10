'use client';

import { createContext, type ReactNode, useContext } from 'react';

import { DEFAULT_STOREFRONT_URL } from './config';

/** Per-environment values the server resolves at request time (src/lib/config.ts) for client components. */
export interface RuntimeConfig {
  storefrontUrl: string;
}

const RuntimeConfigContext = createContext<RuntimeConfig>({ storefrontUrl: DEFAULT_STOREFRONT_URL });

/** Provided once by the root layout with the server's runtime configuration. */
export function RuntimeConfigProvider({ value, children }: { value: RuntimeConfig; children: ReactNode }) {
  return <RuntimeConfigContext.Provider value={value}>{children}</RuntimeConfigContext.Provider>;
}

/** Storefront origin of this environment (PUBLIC_WEB_URL; http://localhost:3000 locally). */
export function useStorefrontUrl(): string {
  return useContext(RuntimeConfigContext).storefrontUrl;
}
