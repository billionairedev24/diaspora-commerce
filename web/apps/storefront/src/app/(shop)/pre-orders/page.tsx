import { type Metadata } from 'next';

import { PreorderView } from '@/components/preorder/PreorderPage';
import { getT } from '@/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('preorder.off.title') };
}

/** /pre-orders (S273): the shopper's pre-order window, or the open and upcoming ones elsewhere. ?place=US/TX/Lubbock
    shows a specific city's window (cards for cities the storefront cannot switch to by ZIP). */
export default async function PreordersPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  const place = typeof sp.place === 'string' ? sp.place : null;
  return <PreorderView placeParam={place} />;
}
