/* Mock of Settings > Store page: the same validation the BFF makes (plain text, https links on allowed hosts, own-media images,
   WCAG AA accent on the chosen look), a draft and a published copy, a version for optimistic locking, and an audit trail. */
import { defaultStoreConfig, type StoreConfig } from '../../schemas/storefront';
import { MOCK_PHOTO_PREFIXES } from '../storefront';

export interface MockStorePage {
  draft: StoreConfig | null;
  published: StoreConfig | null;
  version: number;
  publishedVersion: number | null;
  updatedAt: string | null;
  publishedAt: string | null;
  audit: { at: string; action: 'DRAFT_SAVED' | 'PUBLISHED' | 'UNPUBLISHED'; actor: string; version: number }[];
}

export const emptyStorePage = (): MockStorePage => ({ draft: null, published: null, version: 0, publishedVersion: null, updatedAt: null, publishedAt: null, audit: [] });

/* The canvas and surface of each look: mirrors @afrotransact/tokens storefront-theme.ts and the BFF. */
const BACKDROPS: Record<string, [string, string]> = {
  clean: ['#ffffff', '#f6f4ef'],
  'warm-market': ['#fffaf2', '#f7ecda'],
  bold: ['#ffffff', '#f0f0f0'],
  midnight: ['#0f1115', '#1a1e26'],
  heritage: ['#fbf7ef', '#f1e8d6'],
  fresh: ['#f6faf7', '#e6f1ea']
};

function lum(hex: string): number {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)));
  return 0.2126 * c[0]! + 0.7152 * c[1]! + 0.0722 * c[2]!;
}
const contrast = (a: string, b: string) => (Math.max(lum(a), lum(b)) + 0.05) / (Math.min(lum(a), lum(b)) + 0.05);

const HTMLISH = /<[a-zA-Z/!?]/;

function link(url: string, hosts?: string[]): boolean {
  try {
    const u = new URL(url);
    const host = u.hostname.toLowerCase().replace(/^(www|m)\./, '');
    if (u.protocol !== 'https:' || u.username || !host.includes('.') || /^\d+\.\d+\.\d+\.\d+$/.test(host)) return false;
    return !hosts || hosts.some((h) => host === h || host.endsWith(`.${h}`));
  } catch {
    return false;
  }
}

const NETWORK_HOSTS: Record<string, string[] | undefined> = {
  instagram: ['instagram.com'], facebook: ['facebook.com', 'fb.com', 'fb.me'], tiktok: ['tiktok.com'], x: ['x.com', 'twitter.com'], youtube: ['youtube.com', 'youtu.be'], whatsapp: ['wa.me', 'whatsapp.com'], website: undefined
};

/** Field errors by config path (empty when valid), and the config with text trimmed. */
export function validateMockStoreConfig(c: StoreConfig): { errors: Record<string, string>; accentLow: boolean; clean: StoreConfig } {
  const errors: Record<string, string> = {};
  let accentLow = false;
  const text = (path: string, v: string, max: number, line: boolean) => {
    const s = (line ? v.replace(/\n/g, ' ') : v).replace(/\r\n?/g, '\n').split('').filter((ch) => ch === '\n' || ch.charCodeAt(0) >= 32).join('').replace(/\n{3,}/g, '\n\n').trim();
    if (s.length > max) errors[path] = `At most ${max} characters`;
    else if (HTMLISH.test(s)) errors[path] = 'Plain text only: remove the HTML';
    return s;
  };
  const image = (path: string, v: string | null) => {
    if (!v) return null;
    if (!MOCK_PHOTO_PREFIXES.some((p) => v.startsWith(p)) || v.includes('..')) {
      errors[path] = 'Upload the image again';
      return null;
    }
    return v;
  };
  const clean: StoreConfig = { ...defaultStoreConfig(), ...c };
  clean.hero = { ...c.hero, tagline: text('hero.tagline', c.hero.tagline, 140, true), bannerUrl: image('hero.bannerUrl', c.hero.bannerUrl) };
  clean.announcement = { text: text('announcement.text', c.announcement.text, 140, true), href: c.announcement.href };
  if (c.announcement.href && !(c.announcement.href.startsWith('/') && !c.announcement.href.startsWith('//')) && !link(c.announcement.href)) errors['announcement.href'] = 'Use a page on your store or a full https link';
  clean.about = { heading: text('about.heading', c.about.heading, 80, true), body: text('about.body', c.about.body, 2000, false), photoUrl: image('about.photoUrl', c.about.photoUrl) };
  clean.policies = { shipping: text('policies.shipping', c.policies.shipping, 600, false), returns: text('policies.returns', c.policies.returns, 600, false) };
  clean.contact = { ...c.contact, hours: text('contact.hours', c.contact.hours, 200, true) };
  if (c.contact.phone && !/^[+0-9 ()\-.]{6,24}$/.test(c.contact.phone)) errors['contact.phone'] = 'Use digits, spaces, + ( ) and -';
  if (c.featured.length > 8) errors.featured = 'Feature at most 8 products';
  if (new Set(c.sections.map((s) => s.id)).size !== c.sections.length) errors.sections = 'Each section can appear once';
  c.social.forEach((s, i) => {
    if (!link(s.url, NETWORK_HOSTS[s.network])) errors[`social.${i}.url`] = 'Use the full https link of your profile';
  });
  if (c.social.length > 6) errors.social = 'At most 6 links';
  if (c.theme.accent) {
    const [canvas, surface] = BACKDROPS[c.theme.preset] ?? BACKDROPS.clean!;
    if (!/^#[0-9a-f]{6}$/.test(c.theme.accent)) errors['theme.accent'] = 'Use a colour like #1a7f4b';
    else if (contrast(c.theme.accent, canvas!) < 4.5 || contrast(c.theme.accent, surface!) < 4.5) {
      errors['theme.accent'] = 'That colour is too light to read on this look. Choose a darker one';
      accentLow = true;
    }
  }
  return { errors, accentLow, clean };
}
