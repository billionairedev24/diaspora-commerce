import { type Metadata } from 'next';

import { SubscriptionLink } from '@/components/runs/SubscriptionLink';
import { getT } from '@/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('runs.unsubscribe.title'), robots: { index: false, follow: false } };
}

/** /delivery-runs/subscriptions/unsubscribe?token= — the unsubscribe link in every delivery run alert. */
export default async function UnsubscribeRunAlertsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  const token = typeof sp.token === 'string' && sp.token.trim() ? sp.token.trim().slice(0, 200) : null;
  return <SubscriptionLink mode="unsubscribe" token={token} />;
}
