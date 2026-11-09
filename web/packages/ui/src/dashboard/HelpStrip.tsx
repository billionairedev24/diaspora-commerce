import { type ReactNode } from 'react';

import { cx } from '../cx';
import { Headset } from '../icons';
import styles from './dashboard.module.css';
import { type LinkLike } from './EmptyPrompt';

export interface HelpStripProps {
  /** Heading (translate it); defaults to "We're here to help". */
  title?: string;
  /** One line under the heading. */
  description?: ReactNode;
  links?: readonly { label: string; href: string }[];
  linkAs?: LinkLike;
  className?: string;
}

/** Closing strip of a dashboard: a short promise and the places to get help. A hairline above, no box. */
export function HelpStrip({ title = 'We’re here to help', description, links = [], linkAs, className }: HelpStripProps) {
  const Link = linkAs ?? 'a';
  return (
    <section aria-label={title} className={cx(styles.help, className)}>
      <Headset size={24} aria-hidden className={styles.helpIcon} />
      <div className={styles.helpText}>
        <h2 className={styles.helpTitle}>{title}</h2>
        {description ? <p className={styles.helpDesc}>{description}</p> : null}
      </div>
      {links.length ? (
        <ul className={styles.helpLinks}>
          {links.map((l) => (
            <li key={l.href + l.label}>
              <Link href={l.href} className={styles.helpLink}>
                {l.label}
              </Link>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}
