'use client';

import { useCallback, useState } from 'react';

import { isRealPhoto } from '@/lib/home/photo';

import styles from './DynamicHome.module.css';

/**
 * A real photograph in a box that keeps its shape (no layout shift). With no photo it renders nothing, so the cell is
 * text only: never a striped placeholder, an icon or an emoji. A photo that fails to load keeps the flat box, empty.
 * `priority` is for the single image that can be the page's largest paint: fetched first, never lazy.
 */
export function HomePhoto({ src, ratio = '4 / 3', priority = false, className }: { src: string | null | undefined; ratio?: string; priority?: boolean; className?: string }) {
  const [failed, setFailed] = useState<string | null>(null);
  // A server-rendered image can fail before hydration, when onError never fires: check it on mount too.
  const check = useCallback(
    (img: HTMLImageElement | null) => {
      if (img && src && img.complete && img.naturalWidth === 0) setFailed(src);
    },
    [src]
  );
  if (!isRealPhoto(src)) return null;
  return (
    <span className={`${styles.photo} ${className ?? ''}`} style={{ aspectRatio: ratio }} aria-hidden>
      {failed !== src && <img ref={check} src={src} alt="" loading={priority ? 'eager' : 'lazy'} fetchPriority={priority ? 'high' : 'auto'} decoding="async" onError={() => setFailed(src)} />}
    </span>
  );
}
