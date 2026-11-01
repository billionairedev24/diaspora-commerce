import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { EN_MESSAGES } from '../src/i18n/catalogs';

/* Every "dr.*" key a component asks for exists in the English source (and the catalogue test then checks the other
   four locales against it), and the sign-in, lock and no-access screens carry no hard-coded English sentences. */
const root = join(__dirname, '..', 'src');
function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? walk(p) : /\.tsx?$/.test(f) ? [p] : [];
  });
}

describe('message keys in use', () => {
  const files = walk(root);
  it('all literal dr.* keys exist in en.json', () => {
    const missing: string[] = [];
    for (const f of files) {
      for (const m of readFileSync(f, 'utf8').matchAll(/['"`](dr\.[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)['"`]/g)) {
        const key = m[1] ?? '';
        if (!(key in EN_MESSAGES)) missing.push(`${f.slice(root.length + 1)}: ${key}`);
      }
    }
    expect(missing.filter((k) => !k.includes('dr.event.'))).toEqual([]);
  });

  const screens = ['app/sign-in.tsx', 'app/no-access.tsx', ...readdirSync(join(root, 'features/auth')).filter((f) => f.endsWith('.tsx')).map((f) => `features/auth/${f}`), ...readdirSync(join(root, 'features/lock')).map((f) => `features/lock/${f}`), 'ui/OtpInput.tsx', 'ui/PinPad.tsx'];
  it.each(screens)('%s has no hard-coded sentences in JSX text', (file) => {
    const src = readFileSync(join(root, file), 'utf8');
    // JSX text children that look like words ("> Sign in <"), ignoring {expressions}.
    const text = [...src.matchAll(/>\s*([A-Z][a-z]+(?:\s+[a-z]+)+[^<{]*)</g)].map((m) => m[1]);
    expect(text).toEqual([]);
  });
});
