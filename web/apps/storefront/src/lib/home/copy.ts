import { type HomeBlock, type HomeCell } from '@afrotransact/api-client';
import { type Locale } from '@afrotransact/i18n';

import { EN_MESSAGES, type MessageKey, type Translator } from '@/i18n/t';

/*
 * Home module copy (S426). The gateway sends keys and parameters (`titleKey: home.deals.title`, `{city}`), never English
 * sentences; the storefront resolves them in the shopper's language. A key this build does not know falls back to the
 * `title` the gateway also sent (staff-authored placements) and never to the raw key; no text at all means the field is empty.
 */

export const knownKey = (key: string | undefined): key is MessageKey => !!key && Object.prototype.hasOwnProperty.call(EN_MESSAGES, key);

export function copyOf(t: Translator<MessageKey>, key: string | undefined, fallback: string | undefined, params?: Record<string, string>): string | undefined {
  if (knownKey(key)) return t(key, params);
  return fallback?.trim() ? fallback : undefined;
}

/** A department or community tile label: the locale's own label, else the catalog's `dept.<key>` translation, else the English label. */
export function cellLabel(cell: HomeCell, locale: Locale, t: Translator<MessageKey>): string {
  const own = cell.labels?.[locale as keyof NonNullable<HomeCell['labels']>];
  if (own) return own;
  if (locale !== 'en' && knownKey(cell.labelKey)) return t(cell.labelKey);
  return cell.labels?.en || cell.label;
}

/** The block with its copy resolved: `title`, `sub`, `body`, `cta`, the rail's title and every cell label are now plain text in the shopper's language. */
export function localizeBlock(b: HomeBlock, t: Translator<MessageKey>, locale: Locale): HomeBlock {
  const title = copyOf(t, b.titleKey, b.title, b.titleParams);
  const out: HomeBlock = {
    ...b,
    ...(title !== undefined ? { title } : {}),
    ...(copyOf(t, b.subKey, b.sub) !== undefined ? { sub: copyOf(t, b.subKey, b.sub) } : {}),
    ...(copyOf(t, b.bodyKey, b.body) !== undefined ? { body: copyOf(t, b.bodyKey, b.body) } : {}),
    ...(copyOf(t, b.ctaKey, b.cta) !== undefined ? { cta: copyOf(t, b.ctaKey, b.cta) } : {})
  };
  if (b.rail && title !== undefined && !b.rail.title) out.rail = { ...b.rail, title };
  if (b.cells) out.cells = b.cells.map((c) => ({ ...c, label: cellLabel(c, locale, t) }));
  return out;
}
