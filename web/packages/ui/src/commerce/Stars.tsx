import { cx } from '../cx';
import styles from './commerce.module.css';

export interface StarsProps {
  rating: number;
  reviewCount?: number;
  /** Show the numeric rating before the stars (product page). */
  showValue?: boolean;
  size?: number;
  className?: string;
  reviewsLabel?: (count: string) => string;
}

const STAR = 'M12 2.5l2.94 6.24 6.81.79-5.04 4.66 1.35 6.73L12 17.52 5.94 20.92l1.35-6.73L2.25 9.53l6.81-.79z';

function Star({ fill, size }: { fill: number; size: number }) {
  return (
    <span className={styles.star} style={{ width: size, height: size }}>
      <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden focusable="false" className={styles.starTrack}>
        <path fill="currentColor" d={STAR} />
      </svg>
      <span className={styles.starFill} style={{ width: `${fill * 100}%` }}>
        <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden focusable="false">
          <path fill="currentColor" d={STAR} />
        </svg>
      </span>
    </span>
  );
}

/** Rating stars in brand yellow with an accessible text equivalent. */
export function Stars({ rating, reviewCount, showValue, size = 14, className, reviewsLabel = (c) => c }: StarsProps) {
  const r = Math.max(0, Math.min(5, rating));
  return (
    <span className={cx(styles.stars, className)}>
      {showValue && <span aria-hidden>{r.toFixed(1)}</span>}
      <span className={styles.starRow} role="img" aria-label={`${r.toFixed(1)} out of 5 stars`}>
        {[0, 1, 2, 3, 4].map((i) => (
          <Star key={i} size={size} fill={Math.max(0, Math.min(1, Math.round((r - i) * 2) / 2))} />
        ))}
      </span>
      {reviewCount !== undefined && <span className={styles.reviews}>{reviewsLabel(reviewCount.toLocaleString('en-US'))}</span>}
    </span>
  );
}
