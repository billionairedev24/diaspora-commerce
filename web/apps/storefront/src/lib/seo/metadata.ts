import { type Metadata } from 'next';

import { type IndexingPolicy } from './policy';

export const SITE_NAME = 'AfroTransact';
const DESCRIPTION_MAX = 160;
const TITLE_MAX = 70;

/** Collapses whitespace and cuts at a word boundary with an ellipsis. */
export function clip(text: string, max: number): string {
  const s = text.replace(/\s+/g, ' ').trim();
  if (s.length <= max) return s;
  const cut = s.slice(0, max - 1);
  const at = cut.lastIndexOf(' ');
  return `${(at > max * 0.6 ? cut.slice(0, at) : cut).replace(/[\s,.;:·–—-]+$/, '')}…`;
}

export const metaDescription = (text: string): string => clip(text, DESCRIPTION_MAX);
/** The layout's title template appends " · AfroTransact"; keep the page part short so the whole title stays under ~70. */
export const metaTitle = (text: string): string => clip(text, TITLE_MAX - SITE_NAME.length - 3);

export interface PageSeoInput {
  title: string;
  description?: string;
  /** Path (with the query that makes the page distinct, e.g. /s?dept=Fashion): the ONE canonical of the page. */
  canonicalPath: string;
  /** False for pages that must stay out of the index even in prod. */
  index: boolean;
  /** Absolute URL of the real main image, when there is one. */
  imageUrl?: string;
  imageAlt?: string;
  ogType?: 'website' | 'article';
}

/** Title, description, one canonical, Open Graph and Twitter card. In a non-indexable environment the page is noindex whatever `index` says. */
export function pageMetadata(i: PageSeoInput, policy: IndexingPolicy): Metadata {
  const title = metaTitle(i.title);
  const description = i.description ? metaDescription(i.description) : undefined;
  const images = i.imageUrl ? [{ url: i.imageUrl, alt: i.imageAlt ?? i.title }] : undefined;
  const indexable = policy.indexable && i.index;
  return {
    title,
    ...(description ? { description } : {}),
    alternates: { canonical: i.canonicalPath },
    robots: indexable ? { index: true, follow: true, 'max-image-preview': 'large' } : { index: false, follow: false },
    openGraph: { type: i.ogType ?? 'website', siteName: SITE_NAME, title, ...(description ? { description } : {}), url: i.canonicalPath, ...(images ? { images } : {}) },
    twitter: { card: images ? 'summary_large_image' : 'summary', title, ...(description ? { description } : {}), ...(images ? { images: images.map((im) => im.url) } : {}) }
  };
}

/** Metadata of a page that must never be indexed (cart, account, search facets…). */
export function noindexMetadata(title: string): Metadata {
  return { title, robots: { index: false, follow: false } };
}
