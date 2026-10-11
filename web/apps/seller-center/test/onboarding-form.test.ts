import { type OnboardingForm } from '@afrotransact/api-client';
import { describe, expect, it } from 'vitest';

import { displayPhone, formValues, isPrefilled, prefillNote, toBusinessInput, toDetailsInput } from '@/lib/onboarding-form';

const prefilled: OnboardingForm = {
  business: { offering: null, entityType: null, country: null, storeName: null, contactEmail: 'customer.houston@afrotransact.test', phone: '+17135550101', website: null },
  details: {
    legalFirstName: 'Chioma', legalLastName: 'Okafor', legalName: null,
    address: { line1: '4410 Bellaire Blvd', line2: 'Apt 2', city: 'Houston', state: 'TX', postalCode: '77025', country: 'US' }
  },
  prefilled: ['business.contactEmail', 'business.phone', 'details.address', 'details.legalFirstName', 'details.legalLastName'],
  editable: true
};

describe('application prefill (S286)', () => {
  it('fills the wizard from the account: name, email, phone (national format) and the default address', () => {
    expect(formValues(prefilled, 'SELLER')).toEqual({
      offering: 'PRODUCTS', entityType: '', storeName: '', contactEmail: 'customer.houston@afrotransact.test', phone: '(713) 555-0101', website: '',
      legalFirstName: 'Chioma', legalLastName: 'Okafor', legalName: '', line1: '4410 Bellaire Blvd', line2: 'Apt 2', city: 'Houston', state: 'TX', postalCode: '77025'
    });
    expect(formValues(prefilled, 'PROVIDER').offering).toBe('SERVICES');
    expect(isPrefilled(prefilled, 'city')).toBe(true);
    expect(isPrefilled(prefilled, 'storeName')).toBe(false);
    expect(displayPhone('+442071234567')).toBe('+442071234567');
  });

  it('explains what was filled in, with the address, and nothing when nothing was', () => {
    expect(prefillNote(prefilled)).toBe(
      'We filled in your name, email, phone and address from your AfroTransact account (4410 Bellaire Blvd, Houston). Check them — your legal and business details may differ.'
    );
    expect(prefillNote({ ...prefilled, prefilled: ['business.storeName'] })).toBe(
      'We filled in your business name from your AfroTransact account. Check them — your legal and business details may differ.'
    );
    expect(prefillNote({ ...prefilled, prefilled: [] })).toBeNull();
  });

  it('saves the two steps: blanks left out, the address only once started', () => {
    const v = { ...formValues(prefilled, 'SELLER'), entityType: 'SOLE_PROPRIETOR' as const, state: 'tx', website: '  ' };
    expect(toBusinessInput(v)).toEqual({ offering: 'PRODUCTS', entityType: 'SOLE_PROPRIETOR', country: 'US', storeName: undefined,
      contactEmail: 'customer.houston@afrotransact.test', phone: '(713) 555-0101', website: undefined });
    expect(toDetailsInput(v).address).toEqual({ line1: '4410 Bellaire Blvd', line2: 'Apt 2', city: 'Houston', state: 'TX', postalCode: '77025', country: 'US' });
    expect(toDetailsInput({ ...v, line1: '', line2: '', city: '', state: '', postalCode: '' }).address).toBeUndefined();
  });
});
