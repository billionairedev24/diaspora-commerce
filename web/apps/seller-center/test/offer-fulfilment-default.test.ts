import { describe, expect, it } from 'vitest';

import { defaultOfferFulfilment } from '@/components/listings/add/match-model';

const at = (id: string, over: Partial<{ isDefault: boolean; position: unknown; enabled: boolean }> = {}) => ({
  id,
  isDefault: over.isDefault ?? false,
  position: 'position' in over ? over.position : { lat: 1, lng: 2 },
  afrotransactPickup: { enabled: over.enabled ?? false }
});

describe('a new offer defaults to Fulfilled by AfroTransact only where AfroTransact can collect (S632)', () => {
  it('follows the default location when it lets AfroTransact collect, is located and the organisation is approved', () => {
    expect(defaultOfferFulfilment([at('a', { isDefault: true, enabled: true }), at('b')], true)).toBe('AFROTRANSACT_DELIVERY');
  });

  it('a single location counts as the default', () => {
    expect(defaultOfferFulfilment([at('a', { enabled: true })], true)).toBe('AFROTRANSACT_DELIVERY');
  });

  it('is Pick up when collection is off on the default location, even if another location has it on', () => {
    expect(defaultOfferFulfilment([at('a', { isDefault: true }), at('b', { enabled: true })], true)).toBe('SELLER');
  });

  it('is Pick up when the address is not located, the organisation is not approved or there is no location', () => {
    expect(defaultOfferFulfilment([at('a', { isDefault: true, enabled: true, position: null })], true)).toBe('SELLER');
    expect(defaultOfferFulfilment([at('a', { isDefault: true, enabled: true })], false)).toBe('SELLER');
    expect(defaultOfferFulfilment([], true)).toBe('SELLER');
  });

  it('with several locations and no default it does not guess', () => {
    expect(defaultOfferFulfilment([at('a', { enabled: true }), at('b', { enabled: true })], true)).toBe('SELLER');
  });
});
