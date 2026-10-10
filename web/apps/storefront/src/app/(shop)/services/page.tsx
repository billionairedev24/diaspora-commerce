import { type Metadata } from 'next';

import { ServicesView } from '@/components/services/ServicesView';

export const metadata: Metadata = { title: 'Services', description: 'Book braiders, caterers, tailors and more from the diaspora near you.' };

export default function Page() {
  return <ServicesView />;
}
