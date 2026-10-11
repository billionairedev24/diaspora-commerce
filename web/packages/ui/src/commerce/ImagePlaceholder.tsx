'use client';

import { type CSSProperties, type ReactNode, useCallback, useState } from 'react';

import { cx } from '../cx';
import styles from './commerce.module.css';

export interface ImagePlaceholderProps {
  src?: string | null;
  alt: string;
  /** Text inside the striped placeholder while there is no image (Storybook / mocks: size hints; never over a real image). */
  caption?: ReactNode;
  /** CSS aspect ratio, e.g. "1 / 1" (default) or "16 / 9". */
  ratio?: string;
  stripe?: number;
  /** `eager` for above-the-fold images (the home hero); lazy otherwise. */
  loading?: 'eager' | 'lazy';
  className?: string;
  style?: CSSProperties;
}

/** Product / campaign / tile image: renders `src` whenever the API sends one and falls back to the design's striped
    placeholder when there is none or it fails to load. */
export function ImagePlaceholder({ src, alt, caption, ratio = '1 / 1', loading = 'lazy', className, style }: ImagePlaceholderProps) {
  const [failed, setFailed] = useState<string | null>(null);
  const showImage = !!src && failed !== src;
  // A server-rendered <img> can fail before hydration, when React's onError never fires: check it on mount too.
  const check = useCallback(
    (img: HTMLImageElement | null) => {
      if (img && src && img.complete && img.naturalWidth === 0) setFailed(src);
    },
    [src]
  );
  // Decorative when alt is empty (WCAG 1.1.1): hidden from assistive tech instead of an unnamed role="img".
  const decorative = !alt.trim();
  const a11y = showImage ? {} : decorative ? { 'aria-hidden': true } : { role: 'img', 'aria-label': alt };
  return (
    <span className={cx(styles.placeholder, showImage && styles.withImage, className)} style={{ aspectRatio: ratio, ...style }} {...a11y}>
      {showImage && <img ref={check} src={src} alt={alt} loading={loading} decoding="async" onError={() => setFailed(src)} />}
      {!showImage && caption && (
        <span className={styles.placeholderCaption} aria-hidden>
          {caption}
        </span>
      )}
    </span>
  );
}

export function Skeleton({ width = '100%', height = 16, className, radius }: { width?: number | string; height?: number | string; className?: string; radius?: string }) {
  return <span className={cx(styles.skeleton, className)} style={{ width, height, borderRadius: radius }} aria-hidden />;
}
