import { type IdentityProviderId } from '@afrotransact/api-client';
import { SocialButton } from '@afrotransact/ui';

import { t } from '@/i18n/t';
import { BFF_BASE_PATH } from '@/lib/config';

import styles from './Auth.module.css';

/** Social sign-in buttons at the bottom of the form — only providers the platform has enabled. */
export function SocialSignIn({ providers, returnTo }: { providers: IdentityProviderId[]; returnTo: string }) {
  if (!providers.length) return null;
  return (
    <>
      <div className={styles.divider}>{t('auth.orContinue')}</div>
      {providers.map((p) => (
        <SocialButton key={p} provider={p} href={`${BFF_BASE_PATH}/v1/auth/oidc/${p}/authorize?returnTo=${encodeURIComponent(returnTo)}`} />
      ))}
    </>
  );
}
