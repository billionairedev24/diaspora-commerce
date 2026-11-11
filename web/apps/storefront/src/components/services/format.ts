import { type Formatters } from '@afrotransact/i18n';

import { type MessageKey } from '@/i18n/t';

/** Known service categories → message keys; any other category shows its own name with a capital. */
const CATEGORY_KEYS: Record<string, MessageKey> = {
  'hair & beauty': 'svc.cat.hair',
  catering: 'svc.cat.catering',
  tailoring: 'svc.cat.tailoring',
  'tax & legal': 'svc.cat.tax'
};

export function categoryKey(category: string): MessageKey | null {
  return CATEGORY_KEYS[category.toLowerCase()] ?? null;
}

/** "Next: Wed, Nov 11 · 10:00 AM" in the visitor's time zone (the device's, or the one in their account preferences): never the raw ISO instant. */
export function formatNext(fmt: Formatters, iso: string): string {
  const day = fmt.date(iso, { weekday: 'short', month: 'short', day: 'numeric' });
  return `${day} · ${fmt.time(iso)}`;
}

/** Up to two initials of a provider's name for the photo-less tile ("Adire Tailors" → "AT"). */
export function monogram(name: string): string {
  const words = name
    .replace(/['’]s\b/g, '')
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean);
  const letters = words.slice(0, 2).map((w) => Array.from(w)[0] ?? '');
  return letters.join('').toUpperCase() || '·';
}
