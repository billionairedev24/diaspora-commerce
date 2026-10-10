import { type Metadata } from 'next';

import { ComingSoon } from '@/components/common/ComingSoon';

export const metadata: Metadata = { title: 'Help center', robots: { index: false } };

export default function Page() {
  return <ComingSoon page="soon.help" />;
}
