'use client';

import { PendingNavigation } from '@afrotransact/ui';

import { useT } from '@/i18n/t';
import { LOGIN_START_PATH } from '@/lib/links';

const PREFIXES = [LOGIN_START_PATH, '/api/bff/v1/auth/oidc/', '/back-office/'] as const;

/** A spinner over the page from the click on "Sign in" / "Create account" / "Seller Center" until the next page loads. */
export function SignInPending() {
  const t = useT();
  return <PendingNavigation label={t('a11y.signingIn')} prefixes={PREFIXES} />;
}
