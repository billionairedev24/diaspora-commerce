import { isApiError } from '@afrotransact/api-client';
import { type Metadata } from 'next';
import { notFound } from 'next/navigation';

import { BusinessPageView } from '@/components/business/BusinessPageView';
import { serverApi } from '@/lib/api/server';

type Props = { params: Promise<{ slug: string }> };

async function load(slug: string) {
  try {
    return await (await serverApi()).getBusinessPage(slug);
  } catch (e) {
    if (isApiError(e) && e.status === 404) return null;
    throw e;
  }
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const page = await load((await params).slug);
  if (!page) return { title: 'Page not found', robots: { index: false } };
  return {
    title: page.name,
    description: page.description ?? undefined,
    alternates: { canonical: `/b/${page.slug}` },
    robots: page.indexable ? undefined : { index: false }
  };
}

/** A business's plain public page: its name, description, logo and brand colour, and a provider's services. */
export default async function BusinessPage({ params }: Props) {
  const page = await load((await params).slug);
  if (!page) notFound();
  return <BusinessPageView page={page} />;
}
