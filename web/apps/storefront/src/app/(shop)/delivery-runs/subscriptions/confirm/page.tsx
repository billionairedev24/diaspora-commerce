import { type Metadata } from 'next';

import { SubscriptionLink } from '@/components/runs/SubscriptionLink';
import { getT } from '@/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('runs.confirm.title'), robots: { index: false, follow: false } };
}

/** /delivery-runs/subscriptions/confirm?token= — the link in the confirmation email of a delivery alert sign-up. */
export default async function ConfirmRunAlertsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  const token = typeof sp.token === 'string' && sp.token.trim() ? sp.token.trim().slice(0, 200) : null;
  return <SubscriptionLink mode="confirm" token={token} />;
}
