import { checkCatalogs } from '@afrotransact/i18n/testing';
import { describe, expect, it } from 'vitest';

import { CATALOG_FILES, messagesFor } from '@/i18n/catalogs';
import { EN_MESSAGES, type MessageKey, translate } from '@/i18n/t';

describe('back-office messages', () => {
  it('every locale has every key, the same placeholders, and is marked for review when machine-translated', () => {
    expect(checkCatalogs(CATALOG_FILES)).toEqual([]);
  });

  it('translates with an English fallback', () => {
    const key = Object.keys(EN_MESSAGES)[0] as MessageKey;
    expect(translate('en', key)).toBe(EN_MESSAGES[key]);
    expect(translate('fr', key, undefined, messagesFor('fr'))).not.toBe('');
  });
});
