import { type Metadata } from 'next';

import { AccountHealthView } from '@/components/onboarding/AccountHealthView';

export const metadata: Metadata = { title: 'Account health' };

export default function AccountHealthPage() {
  return <AccountHealthView />;
}
