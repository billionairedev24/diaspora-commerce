import { describe, expect, it } from 'vitest';

import { t, translate } from '@/i18n/t';

describe('t()', () => {
  it('interpolates variables and falls back to English', () => {
    expect(t('header.cartAria', { count: 3 })).toBe('Cart, 3 items');
    expect(translate('fr', 'account.welcomeGuest')).toBe('Welcome, Guest');
  });
});
