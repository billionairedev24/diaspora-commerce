import { type Metadata } from 'next';

import { InviteAccept } from '@/components/invite/InviteAccept';

// A standalone page: it sits outside the console group on purpose, so it has no staff navigation, no session check and no
// header. The invitation token is in the address after `#`, which no server, proxy or log ever receives.
export const metadata: Metadata = { title: 'Staff invitation', referrer: 'no-referrer' };

export default function InviteRoute() {
  return <InviteAccept />;
}
