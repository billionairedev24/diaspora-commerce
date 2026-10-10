import { checkCatalogs } from '@afrotransact/i18n/testing';
import { describe, expect, it } from 'vitest';

import { CATALOG_FILES, messagesFor } from '@/i18n/catalogs';
import { tEn, translate } from '@/i18n/t';

describe('storefront messages', () => {
  it('interpolates variables and falls back to English', () => {
    expect(tEn('header.cartAria', { count: 3 })).toBe('Cart, 3 items');
    expect(translate('fr', 'account.welcomeGuest')).toBe('Welcome, Guest');
    expect(translate('fr', 'account.welcomeGuest', undefined, messagesFor('fr'))).toBe('Bienvenue, invité');
    expect(translate('ar', 'header.cartAria', { count: 3 }, messagesFor('ar'))).toContain('3');
  });

  it('every locale has every key, the same placeholders, and is marked for review when machine-translated', () => {
    expect(checkCatalogs(CATALOG_FILES)).toEqual([]);
  });

  it('only sends a catalogue for non-English locales', () => {
    expect(messagesFor('en')).toBeUndefined();
    expect(Object.keys(messagesFor('yo') ?? {})).not.toContain('$meta');
  });
});
