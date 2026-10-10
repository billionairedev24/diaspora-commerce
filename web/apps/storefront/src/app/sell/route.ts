import { redirect } from 'next/navigation';

import { sellerCenterUrl } from '@/lib/config';

/** "sell" entry point → Seller Center onboarding (signed-in shoppers get the seller role added there). */
export function GET(): never {
  redirect(`${sellerCenterUrl()}/onboarding?role=seller`);
}
