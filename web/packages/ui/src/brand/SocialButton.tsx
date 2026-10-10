import { type AnchorHTMLAttributes, type JSX } from 'react';

import { cx } from '../cx';
import styles from './brand.module.css';
import { AppleLogo, FacebookLogo, GoogleG } from './BrandIcons';

export type SocialProvider = 'google' | 'apple' | 'facebook';

const LOGOS: Record<SocialProvider, (p: { size?: number }) => JSX.Element> = { google: GoogleG, apple: AppleLogo, facebook: FacebookLogo };
const NAMES: Record<SocialProvider, string> = { google: 'Google', apple: 'Apple', facebook: 'Facebook' };

export interface SocialButtonProps extends Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'children'> {
  provider: SocialProvider;
  /** Where the OIDC (authorization code + PKCE) flow starts, e.g. /api/bff/v1/auth/oidc/google/authorize. */
  href: string;
  label?: string;
}

/** "Continue with Google/Apple/Facebook" — a full-page navigation to the BFF, never a form-filling shortcut. */
export function SocialButton({ provider, href, label, className, ...rest }: SocialButtonProps) {
  const Logo = LOGOS[provider];
  return (
    <a href={href} className={cx(styles.social, className)} {...rest}>
      <Logo size={20} />
      {label ?? `Continue with ${NAMES[provider]}`}
    </a>
  );
}
