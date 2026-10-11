import { getSellerDb, PENDING_ORG, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { act, configure, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AccountHealthView } from '@/components/onboarding/AccountHealthView';
import { OnboardingView, OnboardingWizard } from '@/components/onboarding/OnboardingView';
import { Stepper } from '@/components/onboarding/Stepper';
import { PayoutsStep } from '@/components/onboarding/steps/PayoutsStep';
import { type StepHandle } from '@/components/onboarding/WizardKit';
import { Header } from '@/components/shell/Header';
import { PortalShell } from '@/components/shell/PortalShell';

import { mockApi } from './mock-api';
import { renderScreen, renderWithProviders } from './render';
import { nav } from './setup';

// The wizard saves through the in-process mock BFF and the address search debounces: give slow CI machines room.
configure({ asyncUtilTimeout: 5000 });

vi.mock('@/lib/auth', async (orig) => ({
  ...(await orig<Record<string, unknown>>()),
  signOutEverywhere: vi.fn(() => Promise.resolve())
}));

/** A customer who just started a seller application (S286): the mock's DRAFT organisation, prefilled. */
async function started() {
  getSellerDb().orgs = [];
  await mockApi().startOnboarding('SELLER');
}

const ADDRESS = {
  line1: '1 Main St',
  city: 'Houston',
  state: 'TX',
  postalCode: '77002'
};

async function throughBusiness(entityType: 'SOLE_PROPRIETOR' | 'SINGLE_MEMBER_LLC' | 'CORPORATION' = 'SINGLE_MEMBER_LLC') {
  await mockApi().saveOnboardingBusiness({
    offering: 'PRODUCTS',
    entityType,
    country: 'US',
    storeName: 'Titi Foods',
    contactEmail: 't@titi.test',
    phone: '+17132490142'
  });
}

describe('application wizard', () => {
  beforeEach(async () => {
    resetSellerDb(new Date());
    nav.pathname = '/onboarding';
    nav.push.mockReset();
    nav.replace.mockReset();
    await started();
  });

  it('resumes at the first incomplete step on arrival', async () => {
    await throughBusiness();
    await renderScreen(<OnboardingView />);
    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith('/onboarding/business-details'));
  });

  it('"Save and continue" validates, shows the error summary and inline errors, then saves and opens the next step', async () => {
    const user = userEvent.setup();
    await renderScreen(<OnboardingWizard slug="business" />);
    expect(await screen.findByRole('heading', { level: 2, name: 'Your business' })).toBeInTheDocument();
    expect(screen.getAllByText('Step 1 of 8')).not.toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    const summary = (await screen.findByText(/There are 2 problems with this step/)).closest('[role="alert"]') as HTMLElement;
    expect(within(summary).getByText(/There are 2 problems with this step/)).toBeInTheDocument();
    expect(within(summary).getByRole('link', { name: 'Choose your business type' })).toHaveAttribute('href', '#ob-entityType');
    expect(within(summary).getByRole('link', { name: 'Enter your store name' })).toBeInTheDocument();
    expect(nav.push).not.toHaveBeenCalled();

    await user.click(screen.getByRole('radio', { name: /Single-member LLC/ }));
    await user.type(screen.getByLabelText(/Store or business display name/), 'Titi Foods');
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith('/onboarding/business-details'));
    expect(getSellerDb().onboarding.org_new?.form.business).toMatchObject({
      entityType: 'SINGLE_MEMBER_LLC',
      storeName: 'Titi Foods',
      phone: '(713) 249-0142'
    });
  });

  it('Back opens the previous step; the stepper links completed steps and locks later ones', async () => {
    const user = userEvent.setup();
    await throughBusiness();
    await renderScreen(<OnboardingWizard slug="business-details" />);
    expect(
      await screen.findByRole('heading', {
        level: 2,
        name: 'Business details & TIN'
      })
    ).toBeInTheDocument();
    const steps = screen.getByRole('navigation', { name: 'Application steps' });
    expect(within(steps).getByRole('link', { name: /Your business, complete/ })).toHaveAttribute('href', '/onboarding/business');
    expect(
      within(steps).getByRole('link', {
        name: /Business details & TIN, current step/
      })
    ).toHaveAttribute('aria-current', 'step');
    expect(within(steps).queryByRole('link', { name: /Payouts/ })).not.toBeInTheDocument();
    expect(within(steps).getByText('Payouts').closest('[aria-disabled]')).not.toBeNull();
    expect(within(steps).getByRole('progressbar')).toHaveAttribute('aria-valuetext', 'Step 2 of 8: Business details & TIN');
    await user.click(screen.getByRole('button', { name: 'Back' }));
    expect(nav.push).toHaveBeenCalledWith('/onboarding/business');
  });

  it('address autocomplete: picking a suggestion fills street, city, state and ZIP; the EIN shows its last 4 only', async () => {
    const user = userEvent.setup();
    await throughBusiness();
    await renderScreen(<OnboardingWizard slug="business-details" />);
    const street = await screen.findByRole('combobox', {
      name: /Registered business address/
    });
    await user.clear(street);
    await user.type(street, '9800 West');
    const option = await screen.findByRole('option', { name: /9800 Westheimer/ }, { timeout: 3000 });
    await user.click(option);
    expect(street).toHaveValue('9800 Westheimer Rd');
    expect(screen.getByLabelText('City')).toHaveValue('Houston');
    expect(screen.getByLabelText('State')).toHaveValue('TX');
    expect(screen.getByLabelText('ZIP code')).toHaveValue('77042');

    const ein = screen.getByLabelText('Employer Identification Number (EIN)');
    await user.type(ein, '123456789');
    expect(ein).toHaveValue('12-3456789');
    act(() => ein.blur());
    expect(ein).toHaveValue('••-•••6789');
  });

  it('a sole proprietor gets "About you" and no Owners step; saving sends the SSN once and shows it masked', async () => {
    const user = userEvent.setup();
    await throughBusiness('SOLE_PROPRIETOR');
    await renderScreen(<OnboardingWizard slug="business-details" />);
    expect(await screen.findByRole('heading', { level: 2, name: 'About you' })).toBeInTheDocument();
    expect(screen.getAllByText('Step 2 of 7')).not.toHaveLength(0);
    expect(screen.getByLabelText(/Legal first name/)).toHaveValue('Titilayo');
    await user.type(screen.getByLabelText('SSN or ITIN'), '123456789');
    fireEvent.change(screen.getByLabelText('Date of birth'), {
      target: { value: '1990-04-02' }
    });
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith('/onboarding/stripe-verification'));
    expect(getSellerDb().onboarding.org_new?.form.details.taxIdMasked).toBe('•••-••-6789');
  });

  it('owners: the ownership total updates and more than 100% is refused', async () => {
    const user = userEvent.setup();
    await throughBusiness('CORPORATION');
    await mockApi().saveOnboardingDetails({
      legalName: 'Titi Foods Inc',
      taxId: '12-3456789',
      stateOfFormation: 'TX',
      dateOfFormation: '2020-01-01',
      formationDocumentAssetId: 'asset-1',
      address: ADDRESS
    });
    await renderScreen(<OnboardingWizard slug="owners" />);
    const pct = await screen.findByLabelText('Ownership %');
    await user.type(pct, '70');
    await user.click(screen.getByRole('button', { name: /Add another person/ }));
    const both = screen.getAllByLabelText('Ownership %');
    await user.type(both[1] as HTMLElement, '40');
    expect(screen.getByText('Ownership listed: 110%')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    expect(await screen.findAllByText('Ownership adds up to more than 100% (110%)')).not.toHaveLength(0);
    expect(nav.push).not.toHaveBeenCalled();
  });

  it('Stripe verification: the local stand-in is labelled and completes the step', async () => {
    const user = userEvent.setup();
    await throughBusiness('SOLE_PROPRIETOR');
    await mockApi().saveOnboardingDetails({
      legalFirstName: 'Titi',
      legalLastName: 'A',
      taxId: '123-45-6789',
      dateOfBirth: '1990-01-01',
      address: ADDRESS
    });
    await renderScreen(<OnboardingWizard slug="stripe-verification" />);
    await user.click(await screen.findByRole('button', { name: 'Save and continue' }));
    expect(await screen.findByText('Finish Stripe verification to continue.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Start verification' }));
    expect(await screen.findByText(/Local test mode — Stripe isn’t configured on this machine/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Complete test verification' }));
    expect(await screen.findByText(/Verified · charges enabled/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith('/onboarding/payouts'));
  });

  it('Payouts: Stripe collects the bank, so before verification the step points back to Stripe verification', async () => {
    await throughBusiness('SOLE_PROPRIETOR');
    const st = await mockApi().getOnboarding();
    expect(st.form.payouts.status).toBe('NOT_STARTED');
    const handle = createRef<StepHandle>();
    await renderScreen(<PayoutsStep status={st} ref={handle} />);
    expect(screen.getByText('Stripe collects your payout bank account.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Stripe verification' })).toHaveAttribute('href', '/onboarding/stripe-verification');
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    let ok = true;
    await act(async () => {
      ok = (await handle.current?.save('continue')) ?? true;
    });
    expect(ok).toBe(false);
    expect(screen.getByText('Add your payout bank account in Stripe verification to continue.')).toBeInTheDocument();
  });

  it('Payouts: shows the bank Stripe holds, read-only, and changes it in Stripe’s account management', async () => {
    const user = userEvent.setup();
    await throughBusiness('SOLE_PROPRIETOR');
    await mockApi().createOnboardingStripeSession();
    await mockApi().refreshOnboardingStripe();
    await renderScreen(<OnboardingWizard slug="payouts" />);
    expect(await screen.findByText('STRIPE TEST BANK (local) ••6789')).toBeInTheDocument();
    expect(screen.getByText('Held by Stripe')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Change bank account' }));
    expect(await screen.findByRole('region', { name: 'Local test account management' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Local test account management' })).not.toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith('/onboarding/plan'));
  });
});

describe('Account health', () => {
  it('is read-only: each item names the Stripe form that collects it, and Stripe’s components open here', async () => {
    resetSellerDb(new Date());
    const user = userEvent.setup();
    nav.pathname = '/account-health';
    await renderScreen(<AccountHealthView />);
    expect(await screen.findByText('Payout bank account')).toBeInTheDocument();
    expect(screen.getByText('STRIPE TEST BANK ••6789')).toBeInTheDocument();
    expect(screen.getByText('In Stripe’s account settings')).toBeInTheDocument();
    expect(screen.getByText('In Stripe’s secure form')).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Resolve with Stripe' }));
    expect(await screen.findByRole('region', { name: 'Local test account management' })).toBeInTheDocument();
  });
});

describe('progress stepper', () => {
  it('collapses to "Step n of N · name" with a progress bar on phones', async () => {
    resetSellerDb(new Date());
    await started();
    const st = await mockApi().getOnboarding();
    renderWithProviders(<Stepper status={st} current="owners" />);
    expect(screen.getByText('Step 3 of 8')).toBeInTheDocument();
    expect(screen.getByText('· Owners')).toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '38');
  });
});

describe('Seller Center while the application is in progress', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    nav.replace.mockReset();
  });

  it('the header has only the logo, the portal tag and a plain Sign out (no account menu)', async () => {
    const applying = await mockApi().setActiveOrg(PENDING_ORG);
    const { unmount } = renderWithProviders(<Header session={applying} />);
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    expect(screen.getByText('Seller Central')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Account menu/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/My account|Open the storefront/)).not.toBeInTheDocument();
    unmount();
    const approved = await mockApi().setActiveOrg('org-ade-kitchen');
    renderWithProviders(<Header session={approved} />);
    expect(screen.getByRole('button', { name: /Account menu/ })).toBeInTheDocument();
  });

  it('My account and every other back-office route redirect to onboarding', async () => {
    await mockApi().setActiveOrg(PENDING_ORG);
    for (const path of ['/account', '/account/security', '/orders']) {
      nav.replace.mockReset();
      nav.pathname = path;
      const { unmount } = renderWithProviders(
        <PortalShell>
          <p>page</p>
        </PortalShell>
      );
      await waitFor(() => expect(nav.replace).toHaveBeenCalledWith('/onboarding'));
      expect(screen.queryByText('page')).not.toBeInTheDocument();
      unmount();
    }
  });
});
