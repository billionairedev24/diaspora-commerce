import { type Metadata } from 'next';
import { redirect } from 'next/navigation';

import { hostedLoginTarget, type SearchParams } from '@/components/auth/hostedLogin';
import { getT } from '@/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.signup'), robots: { index: false } };
}

type Props = { searchParams: Promise<SearchParams> };

/**
 * Account creation happens on identity's hosted sign-up page (OIDC prompt=create). This route stays so old links and
 * organisation invite links (`?invitation=…&email=…`) keep working: it forwards to the BFF's hosted sign-in start.
 */
export default async function SignUpPage({ searchParams }: Props) {
  redirect(hostedLoginTarget(await searchParams, 'signup').href);
}
