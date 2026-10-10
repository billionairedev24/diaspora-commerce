/* Catalogue checks for unit tests: every locale has every key of the English source, no extra keys, the same
   placeholders, and a $meta block (machine-translated catalogues must be marked needsReview). */
import { type CatalogFile, isLocale, LOCALE_CODES, messagesOf, metaOf } from './index';

const PLACEHOLDER = /\{\s*(\w+)\s*(?:\}|,\s*plural\s*,)/g;

export function placeholdersOf(template: string): string[] {
  return [...new Set([...template.matchAll(PLACEHOLDER)].map((m) => m[1] ?? ''))].sort();
}

export interface CatalogProblems {
  locale: string;
  missing: string[];
  extra: string[];
  placeholderMismatch: string[];
  empty: string[];
  meta: string[];
}

/** Compares one catalogue with the English source. */
export function checkCatalog(locale: string, base: CatalogFile, file: CatalogFile): CatalogProblems {
  const en = messagesOf(base);
  const other = messagesOf(file);
  const missing = Object.keys(en).filter((k) => !(k in other));
  const extra = Object.keys(other).filter((k) => !(k in en));
  const placeholderMismatch = Object.keys(en).filter((k) => k in other && placeholdersOf(en[k] ?? '').join() !== placeholdersOf(other[k] ?? '').join());
  const empty = Object.keys(other).filter((k) => !(other[k] ?? '').trim());
  const meta: string[] = [];
  const m = metaOf(file);
  if (locale !== 'en') {
    if (!m) meta.push('missing $meta block');
    else {
      if (m.locale !== locale) meta.push(`$meta.locale is "${m.locale}"`);
      if (m.source === 'machine' && !m.needsReview) meta.push('machine translation must be marked needsReview');
    }
  }
  return { locale, missing, extra, placeholderMismatch, empty, meta };
}

/** Checks every catalogue against `catalogs.en`; returns only the locales with problems. */
export function checkCatalogs(catalogs: Record<string, CatalogFile>): CatalogProblems[] {
  const base = catalogs.en;
  if (!base) throw new Error('checkCatalogs: an "en" catalogue is required');
  const problems: CatalogProblems[] = [];
  for (const code of LOCALE_CODES) {
    const file = catalogs[code];
    if (!file) {
      problems.push({ locale: code, missing: ['<catalogue file>'], extra: [], placeholderMismatch: [], empty: [], meta: [] });
      continue;
    }
    const p = checkCatalog(code, base, file);
    if (p.missing.length || p.extra.length || p.placeholderMismatch.length || p.empty.length || p.meta.length) problems.push(p);
  }
  for (const code of Object.keys(catalogs)) {
    if (!isLocale(code)) problems.push({ locale: code, missing: [], extra: [], placeholderMismatch: [], empty: [], meta: ['unsupported locale code'] });
  }
  return problems;
}
