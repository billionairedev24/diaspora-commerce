import { startSelling } from '@/lib/sell/route';

export const dynamic = 'force-dynamic';

/** "Start selling": Seller Center onboarding, signed in (single sign-on), or identity's create-account page first. */
export function GET(): Promise<never> {
  return startSelling('seller');
}
