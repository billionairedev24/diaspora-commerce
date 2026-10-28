import { describe, expect, it } from 'vitest';

import { backOfficeLabel, backOfficeUrl } from '../src/domain/backOffices';

describe('account screen back offices', () => {
  it('labels Seller Center, an application in progress, Provider Hub and the staff console', () => {
    expect(backOfficeLabel({ app: 'seller-center', state: 'active' })).toBe('cu.account.sellerCenter');
    expect(backOfficeLabel({ app: 'seller-center', state: 'application' })).toBe('cu.account.sellerApplication');
    expect(backOfficeLabel({ app: 'provider-hub', state: 'active' })).toBe('cu.account.providerHub');
    expect(backOfficeLabel({ app: 'provider-hub', state: 'application' })).toBe('cu.account.providerApplication');
    expect(backOfficeLabel({ app: 'staff-console', state: 'active' })).toBe('cu.account.staffConsole');
  });

  it('opens the storefront back-office link of the environment', () => {
    expect(backOfficeUrl('https://dev.afrotransact.com/', { app: 'seller-center', state: 'active' })).toBe('https://dev.afrotransact.com/back-office/seller-center');
    expect(backOfficeUrl('http://localhost:3000', { app: 'seller-center', state: 'application' })).toBe('http://localhost:3000/back-office/seller-center?state=application');
  });
});
