import { isApiError } from '@afrotransact/api-client';
import { type Metadata } from 'next';
import { notFound } from 'next/navigation';

import { BusinessPageView } from '@/components/business/BusinessPageView';
import { getT } from '@/i18n/server';
import { serverApi } from '@/lib/api/server';
import { realPhotoUrls } from '@/lib/seo/eligibility';
import { pageMetadata } from '@/lib/seo/metadata';
import { getIndexingPolicy } from '@/lib/seo/server';

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
  if (!page) return { title: (await getT())('seo.notFound.title'), robots: { index: false, follow: false } };
  const logo = realPhotoUrls([page.logoUrl], undefined)[0];
  // `indexable` is false until the business is approved: noindex until then.
  return pageMetadata({ title: page.name, ...(page.description ? { description: page.description } : {}), canonicalPath: `/b/${page.slug}`, index: page.indexable, ...(logo ? { imageUrl: logo } : {}) }, await getIndexingPolicy());
}

/** A business's plain public page: its name, description, logo and brand colour, and a provider's services. */
export default async function BusinessPage({ params }: Props) {
  const page = await load((await params).slug);
  if (!page) notFound();
  return <BusinessPageView page={page} />;
}
