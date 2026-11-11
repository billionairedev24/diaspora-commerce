import { Skeleton } from '@afrotransact/ui';

import styles from './Blocks.module.css';

/** Placeholder for the page body while it streams: two rail-shaped rows of the same height as the real ones. */
export function HomeSkeleton() {
  return (
    <div aria-hidden data-testid="home-skeleton">
      {[0, 1].map((i) => (
        <div key={i} className={styles.skel}>
          <Skeleton width={220} height={24} />
          <div className={styles.skelRow}>
            {Array.from({ length: 8 }, (_, n) => (
              <Skeleton key={n} height={260} />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
