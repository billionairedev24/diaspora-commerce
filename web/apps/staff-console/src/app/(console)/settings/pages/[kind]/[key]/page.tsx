import { type Metadata } from 'next';
import { notFound } from 'next/navigation';
import { Suspense } from 'react';

import { ContentPageEditor } from '@/components/pages/ContentPageEditor';
import { Spinner } from '@/components/ui/States';

export const metadata: Metadata = { title: 'Published page' };

/** Preview, edit and publish one legal document or help page: /settings/pages/legal/privacy, /settings/pages/help/shipping. */
export default async function PublishedPageRoute({ params }: { params: Promise<{ kind: string; key: string }> }) {
  const { kind, key } = await params;
  if (kind !== 'legal' && kind !== 'help') notFound();
  return (
    <Suspense fallback={<Spinner />}>
      <ContentPageEditor kind={kind} pageKey={decodeURIComponent(key)} />
    </Suspense>
  );
}
