import { type Metadata } from 'next';

import { WaitlistUnsubscribe } from '@/components/preorder/WaitlistUnsubscribe';
import { getT } from '@/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('preorder.unsubscribe.title'), robots: { index: false, follow: false } };
}

/** /pre-orders/waitlist/unsubscribe?token= — the unsubscribe link of pre-order waitlist emails (delivery-runs). */
export default async function WaitlistUnsubscribePage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  const token = typeof sp.token === 'string' && sp.token.trim() ? sp.token.trim().slice(0, 200) : null;
  return <WaitlistUnsubscribe token={token} />;
}
