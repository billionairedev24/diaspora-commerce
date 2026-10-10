import { type Metadata } from 'next';

import { authPageData } from '@/components/auth/authPageData';
import { AuthShell } from '@/components/auth/AuthShell';
import { SignInForm } from '@/components/auth/SignInForm';

export const metadata: Metadata = { title: 'Sign in', robots: { index: false } };

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> };

export default async function SignInPage({ searchParams }: Props) {
  const d = await authPageData(searchParams);
  return (
    <AuthShell mode="signin">
      <SignInForm providers={d.providers} returnTo={d.returnTo} demo={d.demo} />
    </AuthShell>
  );
}
