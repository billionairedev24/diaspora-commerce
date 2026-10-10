import { type Metadata } from 'next';

import { PolicyEditor } from '@/components/provider/PolicyEditor';

export const metadata: Metadata = { title: 'Cancellation policy' };

export default function PolicyPage() {
  return <PolicyEditor />;
}
