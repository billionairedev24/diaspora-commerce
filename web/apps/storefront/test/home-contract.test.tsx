/* The storefront side of the dynamic-home contract: copy keys in five languages (S426), no literal department or community list
   anywhere (S419, S420), and a hero or tile without a real photo is text, never a striped box (S423). */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

import { type HeroSlide } from '@afrotransact/api-client';
import { metaOf } from '@afrotransact/i18n';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { Hero } from '@/components/home/Hero';
import { Tiles } from '@/components/home/HomeSections';
import { CATALOG_FILES } from '@/i18n/catalogs';

import { renderServer } from './serverTree';

vi.mock('@/i18n/server', async () => {
  const { tEn: t } = await import('@/i18n/t');
  return { getLocale: async () => 'en', getT: async () => t };
});

const ROOT = resolve(__dirname, '../../../..');
const copy = JSON.parse(readFileSync(join(ROOT, 'contracts/i18n/home-copy.json'), 'utf8')) as { locales: string[]; keys: Record<string, Record<string, string | string[]>> };

describe('home copy keys (S426)', () => {
  it('every key of contracts/i18n/home-copy.json is in all five storefront catalogues with the contract text, never a raw key', () => {
    expect(copy.locales).toEqual(['en', 'fr', 'es', 'ar', 'yo']);
    for (const [key, texts] of Object.entries(copy.keys)) {
      for (const locale of copy.locales) {
        const text = (CATALOG_FILES[locale as keyof typeof CATALOG_FILES] as Record<string, unknown>)[key];
        expect(text, `${locale}: ${key}`).toBe(texts[locale]);
        expect(String(text), `${locale}: ${key}`).not.toBe(key);
      }
    }
  });

  it('the non-English catalogues are marked as machine drafts that still need review', () => {
    for (const locale of ['fr', 'es', 'ar', 'yo'] as const) {
      const meta = metaOf(CATALOG_FILES[locale]);
      expect(meta?.needsReview, locale).toBe(true);
      const reviewed = new Set(meta?.reviewedKeys ?? []);
      for (const [key, texts] of Object.entries(copy.keys)) {
        if ((texts.needsReview as string[]).includes(locale)) expect(reviewed.has(key), `${locale}: ${key} is reviewed but the contract says it is a draft`).toBe(false);
      }
    }
  });
});

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? sources(p) : /\.(ts|tsx)$/.test(name) ? [p] : [];
  });
}

describe('no literal department or community list (S419, S420)', () => {
  const files = sources(resolve(__dirname, '../src'));
  it('the storefront has no DEPARTMENTS_LIST and no array of department or community names', () => {
    const departments = /['"](Groceries|Fashion|Beauty|Home & Art|Books|Health|Party|Electronics|Pets)['"]/g;
    const communities = /['"](Nigerian|Ghanaian|Ethiopian|Jamaican|Indian|Senegalese|Kenyan|Caribbean)['"]/g;
    for (const f of files) {
      const text = readFileSync(f, 'utf8');
      expect(text, f).not.toMatch(/DEPARTMENTS_LIST/);
      expect((text.match(departments) ?? []).length, `${f} lists departments`).toBeLessThan(3);
      expect((text.match(communities) ?? []).length, `${f} lists communities`).toBeLessThan(3);
    }
  });

  it('the gateway offers staff the live departments (no literal list) for tile rules and ads', () => {
    const go = readFileSync(join(ROOT, 'go/services/gateway-bff/internal/app/staff_placement_rules.go'), 'utf8');
    const fn = /func tileDepartments\(\)[\s\S]*?\n}\n/.exec(go)?.[0] ?? '';
    expect(fn).toContain('liveDepartments');
    expect(fn).not.toMatch(/"Groceries|"Fashion|"Beauty/);
  });
});

describe('a hero or tile without a real photo is text only (S423)', () => {
  const slide = (over: Partial<HeroSlide>): HeroSlide => ({ id: 's1', theme: 'ink', kicker: 'AfroTransact', title: 'Light up the table', sub: 'Pantry staples', cta: 'Shop groceries', href: '/s', label: 'AfroTransact', imageUrl: null, ...over });

  it.each([null, '', 'https://cdn.test/hero.svg', '/img/placeholder.png', 'data:image/png;base64,AAAA'])('hero image %j renders text only: no image, no striped box', (imageUrl) => {
    const { container } = render(<Hero slides={[slide({ imageUrl })]} flat />);
    expect(screen.getByRole('heading', { level: 1, name: 'Light up the table' })).toBeInTheDocument();
    expect(container.querySelectorAll('img')).toHaveLength(0);
    expect(container.querySelector('[class*="placeholder"]')).toBeNull();
    expect(screen.getByRole('link', { name: 'Shop groceries' })).toBeInTheDocument();
  });

  it('a real hero photo is the page’s one eager, high-priority image', () => {
    const { container } = render(<Hero slides={[slide({ imageUrl: 'https://cdn.test/hero.webp' })]} flat />);
    const img = container.querySelector('img');
    expect(img).toHaveAttribute('loading', 'eager');
    expect(img).toHaveAttribute('fetchpriority', 'high');
  });

  it('editorial tiles without a real photo keep their label and link, with no image box', async () => {
    const tiles = [{ id: 't1', title: 'Shop by mood', cta: 'See all', href: '/s', cells: [{ label: 'Cosy', href: '/s?q=cosy', imageUrl: null }, { label: 'Bold', href: '/s?q=bold', imageUrl: 'https://cdn.test/bold.webp' }] }];
    const { container } = await renderServer(await Tiles({ tiles }));
    expect(screen.getByRole('link', { name: 'Cosy' })).toBeInTheDocument();
    expect(container.querySelectorAll('img')).toHaveLength(1);
    expect(container.querySelector('[class*="placeholder"]')).toBeNull();
  });
});
