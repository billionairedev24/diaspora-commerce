import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const web = resolve(__dirname, '../../..');
const css = (dir: string): string[] =>
  readdirSync(dir).flatMap((name) => {
    if (name === 'node_modules' || name === '.next' || name === 'dist') return [];
    const path = join(dir, name);
    return statSync(path).isDirectory() ? css(path) : name.endsWith('.css') ? [path] : [];
  });
const files = ['packages/ui/src', 'packages/tokens/src', 'apps/seller-center/src', 'apps/storefront/src', 'apps/staff-console/src', 'packages/account/src'].flatMap((d) => css(join(web, d)));
const read = (path: string) => readFileSync(path, 'utf8');

describe('one focus ring for every control', () => {
  const tokens = read(join(web, 'packages/tokens/src/tokens.css'));

  it('is defined once, as a token: a quiet 2px line in the focus colour', () => {
    expect(tokens).toMatch(/--at-focus-ring:\s*2px solid var\(--at-color-focus\)/);
    expect(tokens.match(/^:focus-visible\s*\{[^}]*outline:\s*var\(--at-focus-ring\)/gm)).toHaveLength(1);
  });

  it('is what a field wrapper shows too, and a field inside a bordered wrapper draws none of its own', () => {
    expect(tokens).toMatch(/:where\(label, span\):has\(> input[^{]*:focus-visible\)\s*\{\s*outline:\s*var\(--at-focus-ring\)/);
    expect(tokens).toMatch(/:where\(label, span\) > input[^{]*:focus-visible\s*\{\s*outline:\s*none/);
  });

  it('is not replaced by the old thick green box (a 3px shadow with a recoloured border) anywhere', () => {
    expect(tokens).not.toMatch(/--at-shadow-focus/);
    files.forEach((file) => expect(read(file), file).not.toMatch(/--at-shadow-focus/));
  });

  it('is not turned off on a field without drawing the same ring on it or its wrapper', () => {
    const field = read(join(web, 'packages/ui/src/form/Field.module.css'));
    expect(field).not.toMatch(/\.control:focus\b/);
    expect(field).toMatch(/\.country:focus-within\s*\{\s*outline:\s*var\(--at-focus-ring\)/);
    const multi = read(join(web, 'packages/ui/src/form/MultiCombobox.module.css'));
    expect(multi).toMatch(/\.box:focus-within\s*\{\s*outline:\s*var\(--at-focus-ring\)/);
  });
});
