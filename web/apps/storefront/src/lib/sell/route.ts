import 'server-only';

import { redirect } from 'next/navigation';

import { serverApi } from '../api/server';
import { sellerCenterUrl } from '../config';
import { sellEntryUrl,type SellRole } from './entry';

/** GET /sell and /provide: straight into Seller Center onboarding (signed in), or create an account first. */
export async function startSelling(role: SellRole): Promise<never> {
  const api = await serverApi();
  const me = await api.getMe().catch(() => null);
  redirect(sellEntryUrl({ role, signedIn: me?.authenticated === true, sellerCenterUrl: sellerCenterUrl() }));
}
