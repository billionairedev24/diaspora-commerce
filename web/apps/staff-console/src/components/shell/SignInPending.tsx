'use client';

import { PendingNavigation } from '@afrotransact/ui';

import { useT } from '@/i18n/t';
import { BFF_BASE_PATH } from '@/lib/config';

const PREFIXES = [`${BFF_BASE_PATH}/v1/auth/login`, `${BFF_BASE_PATH}/v1/auth/oidc/`] as const;

/** A spinner over the page from the click on a sign-in link until the next page loads. */
export function SignInPending() {
  const t = useT();
  return <PendingNavigation label={t('st.consoleShell.signingIn')} prefixes={PREFIXES} />;
}
