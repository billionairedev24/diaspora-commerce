import { redirect } from 'next/navigation';

import { sellerCenterUrl } from '@/lib/config';

/** "provide" entry point → Seller Center onboarding (signed-in shoppers get the provider role added there). */
export function GET(): never {
  redirect(`${sellerCenterUrl()}/onboarding?role=provider`);
}
