import { getSellerDb, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { OnboardingWizard } from '@/components/onboarding/OnboardingView';
import { isLogoAddress, mapServerErrors, toBusinessInput, validateBusiness } from '@/lib/onboarding-form';

import { mockApi } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/** The applicant's first step, filled in, with the logo address the media service returned. */
async function withLogo(logoUrl: string) {
  getSellerDb().orgs = [];
  await mockApi().startOnboarding('SELLER');
  await mockApi().saveOnboardingBusiness({ offering: 'PRODUCTS', entityType: 'SINGLE_MEMBER_LLC', country: 'US', storeName: 'Titi Foods', contactEmail: 't@titi.test', phone: '+17132490142' });
  const business = getSellerDb().onboarding.org_new?.form.business;
  if (business) business.logoUrl = logoUrl;
}

describe('onboarding logo', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    nav.pathname = '/onboarding/business';
    nav.push.mockReset();
    nav.replace.mockReset();
  });

  it('a logo on the local media host (http://localhost) saves and continues', async () => {
    const user = userEvent.setup();
    await withLogo('http://localhost:9000/media/storefront/a1.webp');
    await renderScreen(<OnboardingWizard slug="business" />);
    expect(await screen.findByText('Replace logo')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith('/onboarding/business-details'));
    expect(getSellerDb().onboarding.org_new?.form.business.logoUrl).toBe('http://localhost:9000/media/storefront/a1.webp');
  });

  it('a logo on a production CDN host (https) saves and continues', async () => {
    const user = userEvent.setup();
    await withLogo('https://media.afrotransact.com/storefront/a1.webp');
    await renderScreen(<OnboardingWizard slug="business" />);
    await user.click(await screen.findByRole('button', { name: 'Save and continue' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith('/onboarding/business-details'));
  });

  it('a logo address the service refuses is explained next to the logo, and the seller can carry on without it', async () => {
    const user = userEvent.setup();
    await withLogo('http://insecure.example/logo.png');
    await renderScreen(<OnboardingWizard slug="business" />);
    await user.click(await screen.findByRole('button', { name: 'Save and continue' }));
    // Client check passes (it is an address); the service refuses it: the message sits in the logo field, not only in a form-wide error.
    const logo = (await screen.findByText('Replace logo')).closest('[id="ob-logoUrl"]') as HTMLElement;
    expect(await within(logo).findByText(/We couldn’t use this logo/)).toBeInTheDocument();
    expect(nav.push).not.toHaveBeenCalled();
    await user.click(within(logo).getByRole('button', { name: 'Continue without a logo' }));
    expect(screen.queryByText('Replace logo')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith('/onboarding/business-details'));
    expect(getSellerDb().onboarding.org_new?.form.business.logoUrl).toBeNull();
  });
});

describe('logo and colour rules', () => {
  const base = { offering: 'PRODUCTS' as const, entityType: 'SINGLE_MEMBER_LLC' as const, storeName: 'Titi', contactEmail: 'a@b.co', phone: '(713) 249-0142', description: '', brandColor: '', logoUrl: '' };

  it('maps the seller service’s branding field names onto the logo and colour fields', () => {
    expect(
      mapServerErrors({ 'business.branding.logoUrl': 'x', 'business.branding.brandColor': 'y', 'business.branding.description': 'z' })
    ).toEqual({ logoUrl: 'x', brandColor: 'y', description: 'z' });
  });

  it('an optional logo only needs to be an address; the client does not guess which hosts are allowed', () => {
    for (const ok of ['https://media.afrotransact.com/a.png', 'http://localhost:9000/media/a.png', 'http://192.168.1.4:9000/m/a.png']) expect(isLogoAddress(ok)).toBe(true);
    for (const bad of ['', 'logo.png', 'javascript:alert(1)', 'https://', 'https://a b/c.png', '/media/a.png']) expect(isLogoAddress(bad)).toBe(false);
    expect(validateBusiness({ ...base, logoUrl: '' }, 'SELLER').logoUrl).toBeUndefined();
    expect(validateBusiness({ ...base, logoUrl: 'logo.png' }, 'SELLER').logoUrl).toMatch(/couldn’t use this logo/);
  });

  it('sends the colour as #rrggbb lower case whatever the seller typed, and none when it is the default', () => {
    expect(toBusinessInput({ ...base, brandColor: '1A7F4B' }).brandColor).toBe('#1a7f4b');
    expect(toBusinessInput({ ...base, brandColor: '#FA0' }).brandColor).toBe('#ffaa00');
    expect(toBusinessInput({ ...base, brandColor: '' }).brandColor).toBeUndefined();
    expect(validateBusiness({ ...base, brandColor: 'FA0' }, 'SELLER').brandColor).toBeUndefined();
    expect(validateBusiness({ ...base, brandColor: 'green' }, 'SELLER').brandColor).toMatch(/hex code/);
  });
});
