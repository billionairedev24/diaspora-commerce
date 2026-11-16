import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '../next.config.ts'), 'utf8');

function policy(): string {
  const match = /key:\s*'Permissions-Policy',\s*value:\s*'([^']*)'/.exec(source);
  expect(match, 'next.config.ts must set a Permissions-Policy header').not.toBeNull();
  return match?.[1] ?? '';
}

describe('storefront Permissions-Policy', () => {
  it('lets the page itself use the microphone so Lola dictation works', () => {
    expect(policy()).toContain('microphone=(self)');
    expect(policy()).not.toContain('microphone=()');
  });

  it('keeps the camera blocked and never opens a feature to every origin', () => {
    expect(policy()).toContain('camera=()');
    expect(policy()).not.toMatch(/=\*/);
  });
});
