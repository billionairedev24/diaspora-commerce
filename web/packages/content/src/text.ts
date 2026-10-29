/* Text rules shared with content-service so a preview reads exactly as the storefront will: the `{phone}` tokens of legal
   Markdown, the "On this page" outline and its anchors. */

const TOKEN = /\{([A-Za-z][A-Za-z0-9]*)\}/g;

/** Fills `{phone}`, `{legal}`, `{address}`… from the site contacts, like content-service's LegalTemplate. Unknown tokens stay. */
export function fillPlaceholders(markdown: string, values: Readonly<Record<string, string>>): string {
  return markdown.replace(TOKEN, (whole, name: string) => values[name] ?? whole);
}

/** The tokens a text uses, without braces, in order of first use. */
export function placeholdersUsed(markdown: string): string[] {
  const seen = new Set<string>();
  for (const m of markdown.matchAll(TOKEN)) seen.add(m[1] as string);
  return [...seen];
}

/** Anchor of a heading as content-service builds it (accents folded, everything else but letters and digits a dash). */
export function slugOf(title: string): string {
  const folded = title.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  const slug = folded.replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
  return slug === '' ? 'section' : slug;
}

export interface OutlineEntry {
  id: string;
  title: string;
}

/** The level-2 headings of a Markdown body with unique anchors: the "On this page" list content-service sends. */
export function outlineOf(markdown: string): OutlineEntry[] {
  const used = new Map<string, number>();
  const out: OutlineEntry[] = [];
  for (const line of markdown.split('\n')) {
    if (!line.startsWith('## ')) continue;
    const title = line.slice(3).trim();
    const slug = slugOf(title);
    const n = (used.get(slug) ?? 0) + 1;
    used.set(slug, n);
    out.push({ id: n === 1 ? slug : `${slug}-${n}`, title });
  }
  return out;
}
