/*
 * AfroTransact i18n — one pattern for every web and mobile app:
 *
 *  - Messages live in flat JSON catalogues per app (`en.json` is the source; `fr.json`, `es.json`, `ar.json`,
 *    `yo.json` sit beside it). Keys are dotted ("header.cart"); values use `{name}` placeholders and an ICU-style
 *    plural `{count, plural, one {# item} other {# items}}`.
 *  - A non-English catalogue carries a `$meta` block. Machine-translated placeholder text is marked
 *    `needsReview: true` until a translator signs it off (keys reviewed so far go in `reviewedKeys`).
 *  - The active locale is the `at_locale` cookie on the web (negotiated from Accept-Language when unset) and the
 *    device language or the in-app choice on mobile. Arabic is right-to-left.
 *  - Currency, numbers and dates are formatted with `Intl` for the active locale (`createFormatters`).
 */

export const LOCALES = [
  { code: 'en', name: 'English', english: 'English', dir: 'ltr', intl: 'en-US' },
  { code: 'fr', name: 'Français', english: 'French', dir: 'ltr', intl: 'fr-FR' },
  { code: 'es', name: 'Español', english: 'Spanish', dir: 'ltr', intl: 'es-US' },
  { code: 'ar', name: 'العربية', english: 'Arabic', dir: 'rtl', intl: 'ar' },
  { code: 'yo', name: 'Yorùbá', english: 'Yoruba', dir: 'ltr', intl: 'yo-NG' }
] as const;

export type LocaleInfo = (typeof LOCALES)[number];
export type Locale = LocaleInfo['code'];
export type Direction = 'ltr' | 'rtl';

export const DEFAULT_LOCALE: Locale = 'en';
/** Cookie that carries the shopper's language choice on the web (1 year, path=/, SameSite=Lax). */
export const LOCALE_COOKIE = 'at_locale';
export const LOCALE_CODES: readonly Locale[] = LOCALES.map((l) => l.code);

export function isLocale(v: unknown): v is Locale {
  return typeof v === 'string' && (LOCALE_CODES as readonly string[]).includes(v);
}

export function localeInfo(locale: Locale): LocaleInfo {
  return LOCALES.find((l) => l.code === locale) ?? LOCALES[0];
}

export function dirOf(locale: Locale): Direction {
  return localeInfo(locale).dir;
}

/** BCP 47 tag handed to Intl for a locale (market formatting: en-US, fr-FR, es-US, ar, yo-NG). */
export function intlTag(locale: Locale): string {
  return localeInfo(locale).intl;
}

/** Cookie string that stores a language choice (client side: `document.cookie = localeCookie('fr')`). */
export function localeCookie(locale: Locale): string {
  return `${LOCALE_COOKIE}=${locale}; path=/; max-age=31536000; samesite=lax`;
}

/** First supported language in a list of BCP 47 tags ("fr-CA" → fr). */
export function matchLocale(tags: readonly string[]): Locale | undefined {
  for (const tag of tags) {
    const base = tag.trim().toLowerCase().split(/[-_]/)[0];
    if (isLocale(base)) return base;
  }
  return undefined;
}

/** Parses an Accept-Language header into tags ordered by quality. */
export function parseAcceptLanguage(header: string | null | undefined): string[] {
  if (!header) return [];
  return header
    .split(',')
    .map((part, i) => {
      const [tag = '', ...params] = part.trim().split(';');
      const q = params.map((p) => p.trim()).find((p) => p.startsWith('q='));
      return { tag: tag.trim(), q: q ? Number(q.slice(2)) : 1, i };
    })
    .filter((x) => x.tag && x.tag !== '*' && Number.isFinite(x.q) && x.q > 0)
    .sort((a, b) => b.q - a.q || a.i - b.i)
    .map((x) => x.tag);
}

/** The explicit choice wins, then the browser/device languages, then English. */
export function negotiateLocale(choice: string | null | undefined, acceptLanguage?: string | readonly string[] | null): Locale {
  if (isLocale(choice)) return choice;
  const tags = typeof acceptLanguage === 'string' || acceptLanguage == null ? parseAcceptLanguage(acceptLanguage) : acceptLanguage;
  return matchLocale(tags) ?? DEFAULT_LOCALE;
}

// ---------------------------------------------------------------- catalogues

export type Vars = Record<string, string | number>;
export type Messages = Record<string, string>;

/** `$meta` block of a non-English catalogue. */
export interface CatalogMeta {
  locale: string;
  /** "machine" for placeholder machine translation, "human" once a translator owns the file. */
  source: 'machine' | 'human';
  /** True while any string still needs a translator's review. */
  needsReview: boolean;
  /** Keys a translator has already reviewed (the rest still need review while needsReview is true). */
  reviewedKeys?: string[];
  note?: string;
}

/** A catalogue file as imported from JSON: messages plus an optional `$meta` block. */
export type CatalogFile = Record<string, unknown>;

/** Messages of a catalogue file (drops `$`-prefixed metadata keys and non-string values). */
export function messagesOf(file: CatalogFile): Messages {
  const out: Messages = {};
  for (const [k, v] of Object.entries(file)) {
    if (!k.startsWith('$') && typeof v === 'string') out[k] = v;
  }
  return out;
}

export function metaOf(file: CatalogFile): CatalogMeta | undefined {
  const m = file.$meta;
  return m && typeof m === 'object' ? (m as CatalogMeta) : undefined;
}

const pluralCache = new Map<string, Intl.PluralRules>();
function pluralRules(locale: string): Intl.PluralRules {
  let r = pluralCache.get(locale);
  if (!r) {
    r = new Intl.PluralRules(locale);
    pluralCache.set(locale, r);
  }
  return r;
}

/** Index of the brace that closes the one opened at `open`. */
function closingBrace(s: string, open: number): number {
  let depth = 0;
  for (let i = open; i < s.length; i++) {
    if (s[i] === '{') depth++;
    else if (s[i] === '}' && --depth === 0) return i;
  }
  return -1;
}

function plural(body: string, n: number, locale: string, vars: Vars): string {
  const forms = new Map<string, string>();
  const re = /\s*(=\d+|zero|one|two|few|many|other)\s*\{/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(body))) {
    const open = m.index + m[0].length - 1;
    const close = closingBrace(body, open);
    if (close < 0) break;
    forms.set(m[1] ?? 'other', body.slice(open + 1, close));
    re.lastIndex = close + 1;
  }
  const form = forms.get(`=${n}`) ?? forms.get(pluralRules(locale).select(n)) ?? forms.get('other') ?? '';
  return interpolate(form.replace(/#/g, new Intl.NumberFormat(locale).format(n)), vars, locale);
}

/** Replaces `{name}` placeholders and `{n, plural, …}` blocks; unknown placeholders are left as they are. */
export function interpolate(template: string, vars?: Vars, locale = 'en-US'): string {
  if (!vars || !template.includes('{')) return template;
  let out = '';
  let i = 0;
  while (i < template.length) {
    const open = template.indexOf('{', i);
    if (open < 0) {
      out += template.slice(i);
      break;
    }
    out += template.slice(i, open);
    const close = closingBrace(template, open);
    if (close < 0) {
      out += template.slice(open);
      break;
    }
    const inner = template.slice(open + 1, close);
    const pm = /^\s*(\w+)\s*,\s*plural\s*,(.*)$/s.exec(inner);
    if (pm) {
      const n = Number(vars[pm[1] ?? '']);
      out += Number.isFinite(n) ? plural(pm[2] ?? '', n, locale, vars) : template.slice(open, close + 1);
    } else {
      const name = inner.trim();
      out += /^\w+$/.test(name) && name in vars ? String(vars[name]) : template.slice(open, close + 1);
    }
    i = close + 1;
  }
  return out;
}

/**
 * Translator for one app: `base` is the English catalogue (it types the keys); `overlay` the active locale's
 * messages. Missing keys fall back to English.
 */
export function translateWith<K extends string>(base: Record<K, string>, overlay: Messages | undefined, locale: Locale, key: K, vars?: Vars): string {
  const template = overlay?.[key] ?? base[key] ?? key;
  return interpolate(template, vars, intlTag(locale));
}

export type Translator<K extends string> = (key: K, vars?: Vars) => string;

/** Binds a translator to a locale and its catalogue. */
export function createTranslator<K extends string>(base: Record<K, string>, locale: Locale, overlay?: Messages): Translator<K> {
  return (key, vars) => translateWith(base, overlay, locale, key, vars);
}

// ---------------------------------------------------------------- formatting

export type DateStyle = 'short' | 'medium' | 'long' | 'weekday' | 'monthDay' | 'dayMonthShort';

const DATE_OPTIONS: Record<DateStyle, Intl.DateTimeFormatOptions> = {
  short: { month: 'short', day: 'numeric', year: 'numeric' },
  medium: { month: 'long', day: 'numeric', year: 'numeric' },
  long: { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' },
  weekday: { weekday: 'short', month: 'short', day: 'numeric' },
  monthDay: { month: 'long', day: 'numeric' },
  dayMonthShort: { month: 'short', day: 'numeric' }
};

/** A date-only ISO string ("2026-10-10") is read at noon so it shows the same calendar day in every time zone. */
export function toDate(value: string | number | Date): Date {
  if (value instanceof Date) return value;
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)) return new Date(`${value}T12:00:00`);
  return new Date(value);
}

export interface Formatters {
  locale: Locale;
  tag: string;
  /** Minor units → currency ("1850" → "$18.50", "18,50 $US", "US$ 18.50"…). */
  money(minor: number, currency?: string): string;
  /** Major units → currency, without cents when whole. */
  moneyMajor(major: number, currency?: string): string;
  number(n: number, options?: Intl.NumberFormatOptions): string;
  percent(ratio: number): string;
  date(value: string | number | Date, style?: DateStyle | Intl.DateTimeFormatOptions, timeZone?: string): string;
  dateTime(value: string | number | Date, timeZone?: string): string;
  time(value: string | number | Date, timeZone?: string): string;
  relative(value: number, unit: Intl.RelativeTimeFormatUnit): string;
  list(items: string[]): string;
}

export function createFormatters(locale: Locale): Formatters {
  const tag = intlTag(locale);
  return {
    locale,
    tag,
    money: (minor, currency = 'USD') => new Intl.NumberFormat(tag, { style: 'currency', currency }).format(minor / 100),
    moneyMajor: (major, currency = 'USD') =>
      new Intl.NumberFormat(tag, { style: 'currency', currency, maximumFractionDigits: major % 1 ? 2 : 0 }).format(major),
    number: (n, options) => new Intl.NumberFormat(tag, options).format(n),
    percent: (ratio) => new Intl.NumberFormat(tag, { style: 'percent', maximumFractionDigits: 0 }).format(ratio),
    date: (value, style = 'short', timeZone) =>
      toDate(value).toLocaleDateString(tag, { ...(typeof style === 'string' ? DATE_OPTIONS[style] : style), ...(timeZone ? { timeZone } : {}) }),
    dateTime: (value, timeZone) =>
      toDate(value).toLocaleString(tag, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', ...(timeZone ? { timeZone } : {}) }),
    time: (value, timeZone) => toDate(value).toLocaleTimeString(tag, { hour: 'numeric', minute: '2-digit', ...(timeZone ? { timeZone } : {}) }),
    relative: (value, unit) => new Intl.RelativeTimeFormat(tag, { numeric: 'auto' }).format(value, unit),
    list: (items) => new Intl.ListFormat(tag, { style: 'long', type: 'conjunction' }).format(items)
  };
}
