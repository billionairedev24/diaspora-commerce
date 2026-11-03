import { type Metadata } from 'next';
import { redirect } from 'next/navigation';

import { referralSignUpHref } from '@/lib/links';

export const metadata: Metadata = { robots: { index: false, follow: false }, referrer: 'no-referrer' };

type Props = { params: Promise<{ code: string }> };

/**
 * A friend's share link (S322): /r/KDM7-XR3Q opens identity's hosted sign-up with the code filled in. A code that is
 * not made of letters, digits and dashes goes to the plain sign-up instead; whether the code belongs to anyone is
 * decided after sign-up, never revealed here.
 */
export default async function ReferralLinkPage({ params }: Props) {
  const { code } = await params;
  redirect(referralSignUpHref(code));
}
