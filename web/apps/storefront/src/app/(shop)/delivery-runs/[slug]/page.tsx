import { type Metadata } from 'next';

import { LocationPageView } from '@/components/runs/LocationPage';
import { getT } from '@/i18n/server';

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const t = await getT();
  const { slug } = await params;
  const place = slug.replace(/-[a-z]{2}$/, '').replace(/-/g, ' ');
  return { title: t('runs.location.metaTitle', { place: place.replace(/\b\w/g, (c) => c.toUpperCase()) }) };
}

/** /delivery-runs/<slug> (S262, S263): a location's page. UTM parameters on the link are kept on the alert sign-up. */
export default async function DeliveryRunLocationPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  return <LocationPageView slug={slug} />;
}
