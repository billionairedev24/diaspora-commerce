'use client';

import { type AccountLinkProps, AccountProvider } from '@afrotransact/account';
import NextLink from 'next/link';
import { type ReactNode } from 'react';

import { settingsApi } from '@/lib/settings';

function Link({ href, className, children, ...rest }: AccountLinkProps) {
  return (
    <NextLink href={href} className={className} {...rest}>
      {children}
    </NextLink>
  );
}

/** My account runs inside Seller Center: in-app links and the Seller Center BFF proxy. */
export function AccountEnv({ children }: { children: ReactNode }) {
  return <AccountProvider env={{ api: settingsApi(), Link, profileHref: '/account' }}>{children}</AccountProvider>;
}
