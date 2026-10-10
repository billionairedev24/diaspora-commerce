import { type Metadata } from 'next';

import { PhoneVerificationCard } from '@/components/account/PhoneVerificationCard';
import { ComingSoon } from '@/components/common/ComingSoon';

export const metadata: Metadata = { title: 'Your account', robots: { index: false } };

export default function Page() {
  return (
    <>
      <PhoneVerificationCard />
      <ComingSoon page="soon.account" />
    </>
  );
}
