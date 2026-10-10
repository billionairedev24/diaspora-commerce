import { type Metadata } from 'next';

import { authPageData } from '@/components/auth/authPageData';
import { AuthShell } from '@/components/auth/AuthShell';
import { SignUpForm } from '@/components/auth/SignUpForm';

export const metadata: Metadata = { title: 'Create your account', robots: { index: false } };

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> };

export default async function SignUpPage({ searchParams }: Props) {
  const d = await authPageData(searchParams);
  return (
    <AuthShell mode="signup">
      <SignUpForm providers={d.providers} returnTo={d.returnTo} demo={d.demo} smsProgram={d.smsProgram} links={d.links} />
    </AuthShell>
  );
}
