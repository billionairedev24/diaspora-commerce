import { siFacebook, siInstagram, siLinkedin, siTiktok, siWhatsapp, siX, siYoutube } from 'simple-icons';

import { cx } from '../cx';
import styles from './brand.module.css';

export type SocialNetwork = 'tiktok' | 'instagram' | 'facebook' | 'x' | 'youtube' | 'linkedin' | 'whatsapp';

/* Official brand marks from Simple Icons 9.21.0 (the version the design prototype loads), white on the
   network's brand colour (tokens --at-brand-*). */
const PATHS: Record<SocialNetwork, string> = {
  tiktok: siTiktok.path,
  instagram: siInstagram.path,
  facebook: siFacebook.path,
  x: siX.path,
  youtube: siYoutube.path,
  linkedin: siLinkedin.path,
  whatsapp: siWhatsapp.path
};

export interface SocialIconLinkProps {
  network: SocialNetwork;
  href: string;
  /** Network name, e.g. "Instagram". */
  label: string;
  newTabLabel?: string;
  className?: string;
}

/** Footer social link: brand mark in a 40px brand-coloured circle, opens in a new tab. */
export function SocialIconLink({ network, href, label, newTabLabel = '(opens in a new tab)', className }: SocialIconLinkProps) {
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" aria-label={`${label} ${newTabLabel}`} title={label} className={cx(styles.socialIcon, styles[network], className)}>
      <svg viewBox="0 0 24 24" aria-hidden focusable="false">
        <path d={PATHS[network]} />
      </svg>
    </a>
  );
}
