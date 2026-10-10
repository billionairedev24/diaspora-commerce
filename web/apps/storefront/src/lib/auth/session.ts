import 'server-only';

import { type Me } from '@afrotransact/api-client';
import { redirect } from 'next/navigation';

import { serverApi } from '../api/server';
import { signInHref } from '../links';

/** Signed-in customer for account pages; guests are sent to sign in and come back to returnTo. */
export async function requireCustomer(returnTo: string): Promise<Me> {
  const api = await serverApi();
  const me = await api.getMe().catch(() => null);
  if (!me?.authenticated) redirect(signInHref(returnTo));
  return me;
}
