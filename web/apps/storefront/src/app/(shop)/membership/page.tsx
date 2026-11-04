import { type Metadata } from 'next';

import { MembershipOfferView } from '@/components/membership/MembershipOfferView';
import { getT } from '@/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.membership') };
}

/** Public: anyone can see the tiers; joining needs a signed-in shopper with a saved card. */
export default function Page() {
  return <MembershipOfferView />;
}
