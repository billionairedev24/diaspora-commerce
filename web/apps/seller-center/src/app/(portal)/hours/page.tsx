import { type Metadata } from 'next';

import { HoursEditor } from '@/components/provider/HoursEditor';

export const metadata: Metadata = { title: 'Working hours' };

export default function HoursPage() {
  return <HoursEditor />;
}
