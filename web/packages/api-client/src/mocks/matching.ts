/* Token matching ported from the prototype (WORDS, lev, SYN, matchScore, PHRASES). Approximates the
   Elasticsearch behaviour described in contracts/openapi/search.yaml: every typed word must match a
   product word — exactly, as a prefix, through a diaspora synonym, or within a typo tolerance
   (1 edit for 4–5 letters, 2 for 6+). */
import { type FixtureProduct, PRODUCTS } from './fixtures/catalog';

export const SYNONYMS: Record<string, string[]> = {
  gari: ['garri', 'cassava'], garri: ['gari'], egusi: ['melon'], chile: ['chiles', 'chili', 'pepper'], chili: ['chile', 'chiles', 'pepper'],
  pepper: ['chiles', 'shito', 'scotch'], hibiscus: ['bissap', 'zobo', 'sorrel'], zobo: ['hibiscus', 'bissap'], sorrel: ['hibiscus', 'bissap'],
  rice: ['basmati', 'ofada'], soap: ['black'], coffee: ['bean'], spice: ['seasoning', 'blend', 'mix'], seasoning: ['spice', 'blend'],
  fabric: ['ankara', 'wax'], lamps: ['diya', 'diyas'], cream: ['butter']
};

export function words(text: string): string[] {
  return text
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .split(/[^a-z0-9]+/)
    .filter(Boolean);
}

export function levenshtein(a: string, b: string): number {
  const x = a.toLowerCase();
  const y = b.toLowerCase();
  const prev = Array.from({ length: y.length + 1 }, (_, j) => j);
  for (let i = 1; i <= x.length; i++) {
    let diag = prev[0] ?? 0;
    prev[0] = i;
    for (let j = 1; j <= y.length; j++) {
      const tmp = prev[j] ?? 0;
      prev[j] = Math.min((prev[j] ?? 0) + 1, (prev[j - 1] ?? 0) + 1, diag + (x[i - 1] === y[j - 1] ? 0 : 1));
      diag = tmp;
    }
  }
  return prev[y.length] ?? 0;
}

interface ProductWords { name: string[]; all: string[] }
const cache = new Map<string, ProductWords>();
function productWords(p: FixtureProduct): ProductWords {
  let w = cache.get(p.id);
  if (!w) {
    w = { name: words(p.name), all: words([p.name, p.brand, p.comm, p.cat, p.origin].join(' ')) };
    cache.set(p.id, w);
  }
  return w;
}

/** 0 when any token fails to match; otherwise a relevance score (higher is better). */
export function matchScore(p: FixtureProduct, query: string): number {
  const tokens = words(query);
  if (!tokens.length) return 1;
  const w = productWords(p);
  let score = 0;
  for (const t of tokens) {
    let best = 0;
    for (const x of w.all) {
      if (x === t) best = Math.max(best, 4);
      else if (x.startsWith(t)) best = Math.max(best, 3);
      else if (t.length >= 4 && x.length >= 4) {
        const tol = t.length >= 6 ? 2 : 1;
        if (levenshtein(x.slice(0, t.length), t) <= tol || levenshtein(x, t) <= tol) best = Math.max(best, 1);
      }
    }
    if (!best && SYNONYMS[t]?.some((s) => w.all.some((x) => x.startsWith(s)))) best = 2;
    if (!best) return 0;
    if (w.name.some((x) => x.startsWith(t))) best += 1;
    score += best;
  }
  return score + Math.min(1, p.reviews / 3000);
}

const STOP = new Set(['oz', 'lb', 'in', 'of', 'set', 'pack', 'yards']);

/** Completion phrases (1–3 word n-grams from product names) weighted by review volume. */
export const PHRASES: ReadonlyArray<[phrase: string, weight: number]> = (() => {
  const m = new Map<string, number>();
  PRODUCTS.forEach((p) => {
    const ws = words(p.name.replace(/AfroTransact Brand/i, '')).filter((x) => !/^\d+$/.test(x) && !STOP.has(x));
    for (let i = 0; i < ws.length; i++) {
      for (let n = 1; n <= 3 && i + n <= ws.length; n++) {
        const ph = ws.slice(i, i + n).join(' ');
        m.set(ph, (m.get(ph) ?? 0) + p.reviews);
      }
    }
  });
  return [...m.entries()];
})();

/** Phrases that complete what the shopper typed: earlier words must match exactly, the last as a prefix. */
export function completePhrases(query: string, limit = 5): string[] {
  const toks = words(query);
  const last = toks[toks.length - 1] ?? '';
  const head = toks.slice(0, -1).join(' ');
  if (!last) return [];
  return PHRASES.filter(([ph]) => {
    const pw = ph.split(' ');
    if (head && !ph.startsWith(head + ' ')) return false;
    const lastW = head ? pw[toks.length - 1] : pw[0];
    return !!lastW && lastW.startsWith(last) && pw.length >= toks.length;
  })
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([ph]) => ph);
}
