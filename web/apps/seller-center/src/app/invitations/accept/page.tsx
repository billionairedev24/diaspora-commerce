import { type Metadata } from 'next';

import { AcceptInvitationView } from '@/components/invitations/AcceptInvitationView';

export const metadata: Metadata = { title: 'Join an organisation' };

/** Landing page of the invitation email: /invitations/accept?token=… */
export default async function AcceptInvitationPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const raw = (await searchParams).token;
  const token = (Array.isArray(raw) ? raw[0] : raw) ?? '';
  return <AcceptInvitationView token={token} />;
}
