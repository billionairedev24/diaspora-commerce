import { describe, expect, it } from 'vitest';

import { createFormatters, createTranslator, dirOf, interpolate, messagesOf, negotiateLocale, parseAcceptLanguage } from '../src';
import { checkCatalogs, placeholdersOf } from '../src/testing';

describe('locale negotiation', () => {
  it('prefers the explicit choice, then Accept-Language, then English', () => {
    expect(negotiateLocale('ar', 'fr')).toBe('ar');
    expect(negotiateLocale(undefined, 'de-DE,fr-CA;q=0.8,en;q=0.5')).toBe('fr');
    expect(negotiateLocale('xx', 'yo-NG')).toBe('yo');
    expect(negotiateLocale(null, null)).toBe('en');
    expect(negotiateLocale(undefined, ['es-MX'])).toBe('es');
    expect(parseAcceptLanguage('en;q=0.1, ar')).toEqual(['ar', 'en']);
  });

  it('flips Arabic to right-to-left', () => {
    expect(dirOf('ar')).toBe('rtl');
    expect(dirOf('fr')).toBe('ltr');
  });
});

describe('interpolate', () => {
  it('fills placeholders and leaves unknown ones', () => {
    expect(interpolate('Cart, {count} items', { count: 3 })).toBe('Cart, 3 items');
    expect(interpolate('Hi {name}', {})).toBe('Hi {name}');
  });

  it('selects plural forms with Intl.PluralRules', () => {
    const tpl = '{count, plural, =0 {No items} one {# item} other {# items}} in {where}';
    expect(interpolate(tpl, { count: 0, where: 'cart' })).toBe('No items in cart');
    expect(interpolate(tpl, { count: 1, where: 'cart' })).toBe('1 item in cart');
    expect(interpolate(tpl, { count: 1200, where: 'cart' })).toBe('1,200 items in cart');
    expect(interpolate('{n, plural, one {# article} other {# articles}}', { n: 1200 }, 'fr-FR')).toBe('1 200 articles');
  });
});

describe('translator', () => {
  it('falls back to English for keys missing in the overlay', () => {
    const en = { 'a.hello': 'Hello {name}', 'a.bye': 'Bye' } as const;
    const t = createTranslator(en, 'fr', messagesOf({ $meta: { locale: 'fr' }, 'a.hello': 'Bonjour {name}' }));
    expect(t('a.hello', { name: 'Ada' })).toBe('Bonjour Ada');
    expect(t('a.bye')).toBe('Bye');
  });
});

describe('formatters', () => {
  it('formats currency, numbers and dates per locale with Intl', () => {
    expect(createFormatters('en').money(185000)).toBe('$1,850.00');
    expect(createFormatters('fr').money(185000)).toMatch(/1\s?850,00\s?\$US/);
    expect(createFormatters('es').money(1850, 'EUR')).toContain('18.50');
    expect(createFormatters('ar').number(12)).toMatch(/١٢|12/);
    expect(createFormatters('en').date('2026-10-10')).toBe('Oct 10, 2026');
    expect(createFormatters('fr').date('2026-10-10', 'medium')).toBe('10 octobre 2026');
    expect(createFormatters('en').moneyMajor(20)).toBe('$20');
  });
});

describe('catalogue checks', () => {
  it('reports missing, extra, placeholder and $meta problems', () => {
    const en = { a: 'A {x}', b: 'B' };
    const ok = { $meta: { locale: 'fr', source: 'machine', needsReview: true }, a: 'A {x}', b: 'B' };
    const problems = checkCatalogs({
      en,
      fr: ok,
      es: { $meta: { locale: 'es', source: 'machine', needsReview: false }, a: 'A {y}', c: 'C' },
      ar: { ...ok, $meta: { locale: 'ar', source: 'machine', needsReview: true } },
      yo: { ...ok, $meta: { locale: 'yo', source: 'machine', needsReview: true } }
    });
    expect(problems).toEqual([
      { locale: 'es', missing: ['b'], extra: ['c'], placeholderMismatch: ['a'], empty: [], meta: ['machine translation must be marked needsReview'] }
    ]);
    expect(placeholdersOf('{n, plural, one {# x} other {# {y}}}')).toEqual(['n', 'y']);
    expect(placeholdersOf('{n, plural, one {There is # problem} other {There are # problems}}')).toEqual(['n']);
  });
});
