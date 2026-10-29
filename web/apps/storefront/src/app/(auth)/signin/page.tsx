import { buttonClassName } from '@afrotransact/ui';
import { type Metadata } from 'next';
import { redirect } from 'next/navigation';

import styles from '@/components/auth/Auth.module.css';
import { AuthShell } from '@/components/auth/AuthShell';
import { hostedLoginTarget, type SearchParams } from '@/components/auth/hostedLogin';
import { getT } from '@/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.signin'), robots: { index: false } };
}

type Props = { searchParams: Promise<SearchParams> };

/**
 * Sign-in happens on identity's hosted page (single sign-on for every AfroTransact app). This route stays so old links
 * keep working: it forwards to the BFF's hosted sign-in start. When sign-in failed (`?error=`, set by the BFF
 * callback) it explains and offers to start again instead of looping.
 */
export default async function SignInPage({ searchParams }: Props) {
  const sp = await searchParams;
  const { href, error, returnTo } = hostedLoginTarget(sp, 'signin');
  const isExternal = Boolean(returnTo && (/^https?:\/\//i.test(returnTo) || returnTo.startsWith('//')));
  if (!error || isExternal) redirect(href);
  const t = await getT();
  return (
    <AuthShell mode="signin">
      <h1 className={styles.h1}>{t('auth.signinTitle')}</h1>
      <p className={styles.summary} role="alert">
        {t('auth.genericError')}
      </p>
      <a href={href} className={buttonClassName({ block: true })}>
        {t('auth.continue')}
      </a>
    </AuthShell>
  );
}
