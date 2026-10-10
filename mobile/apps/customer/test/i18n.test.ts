import { checkCatalogs } from '@afrotransact/i18n/testing';
import { describe, expect, it } from 'vitest';

import { CATALOG_FILES } from '../src/i18n/catalogs';

describe('app messages', () => {
  it('every locale has every key, the same placeholders, and is marked for review when machine-translated', () => {
    expect(checkCatalogs(CATALOG_FILES)).toEqual([]);
  });
});
