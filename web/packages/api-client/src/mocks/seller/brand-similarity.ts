/* The mock of the catalog's brand "did you mean" and name rules (BrandSimilarity, BrandProposalRules): the same decisions
   for the cases the product form and its tests exercise, so the form behaves the same against the mock and the service. */

/** Edit distance counting an adjacent swap as one mistake; stops early once above `limit`. */
function distance(a: string, b: string, limit: number): number {
  const rows = Array.from({ length: a.length + 1 }, (_, i) => Array.from<number>({ length: b.length + 1 }).fill(0).map((_v, j) => (i === 0 ? j : j === 0 ? i : 0)));
  const at = (i: number, j: number): number => rows[i]?.[j] ?? limit + 1;
  for (let i = 1; i <= a.length; i += 1) {
    const row = rows[i];
    if (!row) continue;
    for (let j = 1; j <= b.length; j += 1) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      let best = Math.min(at(i, j - 1) + 1, at(i - 1, j) + 1, at(i - 1, j - 1) + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) best = Math.min(best, at(i - 2, j - 2) + 1);
      row[j] = best;
    }
  }
  return Math.min(at(a.length, b.length), limit + 1);
}

/** True when two comparison keys differ but are one or two typing mistakes apart, or one extends the other by a few letters. */
export function nearBrandKeys(a: string, b: string): boolean {
  if (a === b) return false;
  const [shorter, longer] = a.length <= b.length ? [a, b] : [b, a];
  if (shorter.length < 4) return false;
  if (longer.startsWith(shorter) && longer.length - shorter.length <= 6) return true;
  const allowed = shorter.length >= 8 ? 2 : 1;
  return longer.length - shorter.length <= allowed && distance(a, b, allowed) <= allowed;
}

const BLOCKED = new Set(['fuck', 'shit', 'bitch', 'cunt', 'porn', 'xxx', 'merde', 'putain', 'scam']);

/** The field message when a name may not be added as a brand (a web address, an e-mail address, profanity, odd characters), else null. */
export function brandNameProblem(name: string): string | null {
  if (name.includes('@')) return 'No e-mail addresses';
  if (/:\/\/|\bwww\.|\b[\p{L}\p{N}-]+\.(com|net|org|io|co|biz|info|shop|store|xyz|app|ly|me|ng|uk|us|fr|es|de|ru|cn|site|online)\b/iu.test(name)) return 'No web addresses';
  if (!/^[\p{L}\p{N}][\p{L}\p{M}\p{N} &'’.,+\-()!/®™]*$/u.test(name) || !/\p{L}/u.test(name)) return 'Letters, digits and & \' . , - + ( ) ! / only';
  if (name.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().split(/[^\p{L}\p{N}]+/u).some((w) => BLOCKED.has(w))) return 'Choose another name';
  return null;
}
