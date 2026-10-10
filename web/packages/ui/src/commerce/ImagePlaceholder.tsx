import { type CSSProperties, type ReactNode } from 'react';

import { cx } from '../cx';
import styles from './commerce.module.css';

export interface ImagePlaceholderProps {
  src?: string | null;
  alt: string;
  caption?: ReactNode;
  /** CSS aspect ratio, e.g. "1 / 1" (default) or "16 / 9". */
  ratio?: string;
  stripe?: number;
  className?: string;
  style?: CSSProperties;
}

/** Product/campaign image, falling back to the design's striped placeholder until photography exists. */
export function ImagePlaceholder({ src, alt, caption, ratio = '1 / 1', className, style }: ImagePlaceholderProps) {
  // Decorative when alt is empty (WCAG 1.1.1): hidden from assistive tech instead of an unnamed role="img".
  const decorative = !alt.trim();
  const a11y = src ? {} : decorative ? { 'aria-hidden': true } : { role: 'img', 'aria-label': alt };
  return (
    <span className={cx(styles.placeholder, className)} style={{ aspectRatio: ratio, ...style }} {...a11y}>
      {src && <img src={src} alt={alt} loading="lazy" />}
      {caption && <span className={styles.placeholderCaption} aria-hidden>{caption}</span>}
    </span>
  );
}

export function Skeleton({ width = '100%', height = 16, className, radius }: { width?: number | string; height?: number | string; className?: string; radius?: string }) {
  return <span className={cx(styles.skeleton, className)} style={{ width, height, borderRadius: radius }} aria-hidden />;
}
