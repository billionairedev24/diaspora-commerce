import { type Metadata } from 'next';

import { ServicesView } from '@/components/services/ServicesView';
import { getT } from '@/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.services'), description: t('title.servicesDescription') };
}

const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) ?? '';

/** /services?category=tailoring&q=agbada: deep links from the home page and from search. The place is the deliver-to location. */
export default async function Page({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  return <ServicesView initialCategory={one(sp.category).toLowerCase().slice(0, 60)} initialText={one(sp.q).slice(0, 120)} />;
}
