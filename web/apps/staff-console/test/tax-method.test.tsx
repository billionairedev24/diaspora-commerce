import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { TaxMethodPanel } from '@/components/tax/TaxMethodPanel';
import { TaxShadowPanel } from '@/components/tax/TaxShadowPanel';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const NOW = new Date('2026-11-09T12:00:00Z');

describe('Who calculates tax', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles('platform.super_admin');
    nav.set('/fees?tab=tax-method');
  });
  afterEach(() => interceptStaff(null));

  it('explains each method in plain words and blocks the ones that are not set up or not verified', async () => {
    await renderScreen(<TaxMethodPanel />);
    expect(await screen.findByRole('heading', { name: 'Our own rules works out the tax now' })).toBeInTheDocument();
    const group = screen.getByRole('group', { name: 'Who calculates the tax' });
    expect(within(group).getByRole('radio', { name: /Our own rules \(in use now\)/ })).toBeChecked();
    expect(within(group).getByRole('radio', { name: /Stripe Tax/ })).toBeDisabled();
    expect(within(group).getByRole('radio', { name: /Avalara/ })).toBeDisabled();
    expect(within(group).getByText(/Can’t be chosen yet: .*Finish the Provider codes first/)).toBeInTheDocument();
    expect(within(group).getByText(/Can’t be chosen yet: Avalara isn’t set up yet/)).toBeInTheDocument();
    expect(screen.getByText(/The tax rules are a draft/)).toBeInTheDocument();
    const runbook = screen.getByRole('link', { name: /docs\/runbooks\/tax-provider-cutover.md/ });
    expect(runbook).toHaveAttribute('href', expect.stringContaining('/docs/runbooks/tax-provider-cutover'));
    expect(screen.getByRole('progressbar', { name: 'Avalara' })).toBeInTheDocument();
  });

  it('changes the shadow provider only with a reason of ten characters, and records it in the history', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxMethodPanel />);
    await screen.findByRole('heading', { name: 'Our own rules works out the tax now' });
    const save = screen.getByRole('button', { name: 'Save changes…' });
    expect(save).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('Shadow mode'), '');
    expect(save).toBeEnabled();
    await user.click(save);
    const dialog = await screen.findByRole('dialog', { name: 'Change who calculates sales tax?' });
    const change = within(dialog).getByRole('button', { name: 'Change' });
    expect(change).toBeDisabled();
    expect(within(dialog).getByText('At least 10 characters.')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Reason'), 'Too short');
    expect(change).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Reason'), ' no more');
    expect(change).toBeEnabled();
    await user.click(change);
    expect(await screen.findByText(/Shadow mode: off/)).toBeInTheDocument();
    const history = screen.getByRole('region', { name: 'History' });
    expect(within(history).getByText(/Too short no more/)).toBeInTheDocument();
  });

  it('is read only without tax:configure', async () => {
    asRoles('platform.auditor');
    await renderScreen(<TaxMethodPanel />);
    expect(await screen.findByText(/Changing them needs the tax:configure permission/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save changes…' })).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Our own rules/ })).toBeDisabled();
  });

  it('shows a loading state, then the unavailable state', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => (release = r));
    interceptStaff(async (req, next) => {
      if (new URL(req.url).pathname.endsWith('/v1/staff/tax/method')) {
        await gate;
        return new Response(JSON.stringify({ status: 503, code: 'UPSTREAM_UNAVAILABLE', title: 'Service unavailable' }), { status: 503, headers: { 'content-type': 'application/problem+json' } });
      }
      return next(req);
    });
    await renderScreen(<TaxMethodPanel />);
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0);
    expect(screen.queryByRole('heading', { name: /works out the tax now/ })).not.toBeInTheDocument();
    release();
    expect(await screen.findByText('This part of AfroTransact is temporarily unavailable')).toBeInTheDocument();
  });
});

describe('Shadow report', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles('platform.super_admin');
    nav.set('/fees?tab=tax-shadow');
  });

  it('says whether it is ready to cut over, check by check, with disagreement by category and sample lines', async () => {
    await renderScreen(<TaxShadowPanel />);
    const ready = await screen.findByRole('region', { name: 'Ready to cut over?' });
    expect(within(ready).getByText('Not ready to cut over')).toBeInTheDocument();
    expect(within(ready).getByText(/checks need work/)).toBeInTheDocument();
    expect(within(ready).getByText('Same exempt or taxable answer')).toBeInTheDocument();
    expect(within(ready).getAllByText(/: needs work/).length).toBeGreaterThan(0);
    expect(within(ready).getByText(/placeholders until the tax adviser/)).toBeInTheDocument();
    const byCategory = screen.getByRole('table', { name: /By category/ });
    expect(within(byCategory).getByText('Nuts seeds')).toBeInTheDocument();
    const samples = screen.getByRole('region', { name: 'Sample lines with both quotes' });
    expect(within(samples).getAllByText(/^AT-\d+/).length).toBeGreaterThan(0);
    expect(within(samples).getAllByText(/Exempt \(no tax\)/).length).toBeGreaterThan(0);
    expect(within(samples).getAllByText(/Taxable at 8.25%/).length).toBeGreaterThan(0);
  });

  it('explains that shadow mode is off instead of showing empty numbers', async () => {
    const user = userEvent.setup();
    nav.set('/fees?tab=tax-method');
    const { unmount } = await renderScreen(<TaxMethodPanel />);
    await screen.findByRole('heading', { name: 'Our own rules works out the tax now' });
    await user.selectOptions(screen.getByLabelText('Shadow mode'), '');
    await user.click(screen.getByRole('button', { name: 'Save changes…' }));
    await user.type(await screen.findByLabelText('Reason'), 'Comparison finished for now');
    await user.click(screen.getByRole('button', { name: 'Change' }));
    await screen.findByText(/Shadow mode: off/);
    unmount();
    nav.set('/fees?tab=tax-shadow');
    await renderScreen(<TaxShadowPanel />);
    expect(await screen.findByText('Shadow mode is off')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Ready to cut over?' })).not.toBeInTheDocument();
  });

  it('refuses an end date before the start date', async () => {
    nav.set('/fees?tab=tax-shadow&shadow.from=2026-11-09&shadow.to=2026-11-01');
    await renderScreen(<TaxShadowPanel />);
    expect(await screen.findByText('The end date must be after the start.')).toBeInTheDocument();
  });

  it('shows the unavailable state when the tax service is down', async () => {
    interceptStaff(async (req, next) => {
      if (new URL(req.url).pathname.endsWith('/v1/staff/tax/shadow')) return new Response(JSON.stringify({ status: 503, code: 'UPSTREAM_UNAVAILABLE', title: 'Service unavailable' }), { status: 503, headers: { 'content-type': 'application/problem+json' } });
      return next(req);
    });
    await renderScreen(<TaxShadowPanel />);
    await waitFor(() => expect(screen.getByText('This part of AfroTransact is temporarily unavailable')).toBeInTheDocument());
    interceptStaff(null);
  });
});
