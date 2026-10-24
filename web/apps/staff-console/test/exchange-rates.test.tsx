import { createStaffApiClient } from '@afrotransact/api-client';
import { MOCK_STAFF_ROLE_COOKIE, resetHubDb, resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { handleStaffMockRequest } from '@afrotransact/api-client/mocks/staff-runtime';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { checkCost, emptyCost } from '@/components/inventory/CostFields';
import { OverviewPanel } from '@/components/inventory/OverviewPanel';
import { ReceivingPanel } from '@/components/inventory/ReceivingPanel';
import { LotSheet } from '@/components/inventory/StockPanel';
import { SettingsHub } from '@/components/settings/SettingsHub';
import { ConsoleShell } from '@/components/shell/ConsoleShell';
import { tEn } from '@/i18n/t';

import { asRoles } from './mock-api';
import { renderScreen, renderWithProviders } from './render';
import { nav } from './setup';

/* ADR 0005 §7: stock is received in the currency actually paid and valued in USD. */

const ASN = 'dddd0001-0000-4000-8000-000000000002';
const NOW = new Date('2026-10-24T12:00:00Z');

beforeEach(() => {
  resetStaffDb(NOW);
  resetHubDb(NOW);
  asRoles(null);
});

describe('the cost of a counted line', () => {
  const rates = [{ currency: 'MXN', rateDate: '2026-10-12', rateToUsd: 0.0577 }];
  const draft = (over: object) => ({ ...emptyCost(), ...over });

  it('is optional: nothing typed means no cost and no errors', () => {
    const c = checkCost(emptyCost(), rates, tEn);
    expect(c.input).toBeUndefined();
    expect(c).toMatchObject({ errors: {}, usdUnit: null });
  });

  it('takes the rate from the table until the receiver types one, and values the unit in USD', () => {
    const table = checkCost(draft({ currency: 'MXN', unit: '120.00' }), rates, tEn);
    expect(table.rate).toBe('0.0577');
    expect(table.usdUnit).toBe(692);
    expect(table.input).toEqual({ unitLandedCostMinor: 12_000, currency: 'MXN' });
    const typed = checkCost(draft({ currency: 'MXN', unit: '120.00', rate: '0.06', rateTouched: true }), rates, tEn);
    expect(typed.usdUnit).toBe(720);
    expect(typed.input).toEqual({ unitLandedCostMinor: 12_000, currency: 'MXN', fxRateToUsd: 0.06 });
  });

  it('adds the components up, in the currency’s own minor units', () => {
    const c = checkCost(draft({ currency: 'MXN', breakdown: true, goods: '100.00', freight: '15', duty: '4.00', other: '1' }), rates, tEn);
    expect(c.input?.unitLandedCostMinor).toBe(12_000);
    expect(c.input?.costComponents).toEqual({ goodsMinor: 10_000, freightMinor: 1_500, dutyMinor: 400, otherMinor: 100 });
    const cfa = checkCost(draft({ currency: 'XOF', unit: '6000', rate: '0.00165', rateTouched: true }), [], tEn);
    expect(cfa.input?.unitLandedCostMinor).toBe(6_000);
    expect(cfa.usdUnit).toBe(990);
    expect(checkCost(draft({ currency: 'XOF', unit: '6000.5', rate: '0.00165', rateTouched: true }), [], tEn).errors.unit).toBeDefined();
  });

  it('says what is wrong: no rate on file, a rate that is not positive, a cost that is not a number', () => {
    expect(checkCost(draft({ currency: 'ZAR', unit: '20' }), rates, tEn).errors.rate).toBe('No ZAR rate on file: enter the rate.');
    expect(checkCost(draft({ currency: 'MXN', unit: '20', rate: '0', rateTouched: true }), rates, tEn).errors.rate).toBe('Enter a positive rate with at most 10 decimals.');
    expect(checkCost(draft({ currency: 'MXN', unit: '20', rate: '-1', rateTouched: true }), rates, tEn).errors.rate).toBeDefined();
    expect(checkCost(draft({ currency: 'USD', unit: 'abc' }), rates, tEn).errors.unit).toBe('Enter the cost as a number, for example 120.00.');
    expect(checkCost(draft({ currency: 'USD', unit: '2.50' }), rates, tEn).input).toEqual({ unitLandedCostMinor: 250, currency: 'USD' });
  });
});

describe('receiving stock paid in another currency', () => {
  it('prefills the rate from the rates table, shows the USD total and posts lots valued in USD', async () => {
    nav.set(`/inventory?tab=receiving&receive=${ASN}`);
    await renderScreen(<ReceivingPanel />);
    const user = userEvent.setup();
    const screenEl = await screen.findByTestId('receiving-screen');
    await user.type(within(screenEl).getByLabelText('Good units'), '10');
    const cost = within(screenEl).getByTestId('cost-fields');
    expect(within(cost).queryByLabelText(/Rate \(USD per 1/)).not.toBeInTheDocument();
    await user.selectOptions(within(cost).getByLabelText('Currency paid'), 'MXN');
    await user.type(within(cost).getByLabelText('Unit cost (MXN)'), '120.00');
    const rate = await within(cost).findByLabelText('Rate (USD per 1 MXN)');
    await waitFor(() => expect(rate).toHaveValue('0.0577'));
    expect(within(cost).getByText(/Suggested from the rates table/)).toBeInTheDocument();
    expect(within(cost).getByTestId('usd-total')).toHaveTextContent('MXN 120.00 paid = $6.92 per unit in US dollars · $69.20 for 10 units');

    await user.clear(rate);
    await user.type(rate, '0.06');
    expect(within(cost).getByTestId('usd-total')).toHaveTextContent('$7.20 per unit in US dollars · $72.00 for 10 units');
    expect(within(cost).queryByText(/Suggested from the rates table/)).not.toBeInTheDocument();

    await user.click(within(screenEl).getByRole('button', { name: 'Post receipt' }));
    const lots = await screen.findByTestId('created-lots');
    expect(lots).toHaveTextContent('MXN 120.00 per unit = $7.20 (rate 0.06)');
  });

  it('refuses a currency without a rate on file and blocks posting until a rate is typed', async () => {
    nav.set(`/inventory?tab=receiving&receive=${ASN}`);
    await renderScreen(<ReceivingPanel />);
    const user = userEvent.setup();
    const screenEl = await screen.findByTestId('receiving-screen');
    await user.type(within(screenEl).getByLabelText('Good units'), '5');
    const cost = within(screenEl).getByTestId('cost-fields');
    await user.selectOptions(within(cost).getByLabelText('Currency paid'), 'ZAR');
    await user.type(within(cost).getByLabelText('Unit cost (ZAR)'), '20');
    expect(await within(cost).findByText('No ZAR rate on file: enter the rate.')).toBeInTheDocument();
    expect(within(screenEl).getByRole('button', { name: 'Post receipt' })).toBeDisabled();
    await user.type(within(cost).getByLabelText('Rate (USD per 1 ZAR)'), '0.054');
    expect(within(screenEl).getByRole('button', { name: 'Post receipt' })).toBeEnabled();
    expect(within(cost).getByTestId('usd-total')).toHaveTextContent('$1.08 per unit');
  });

  it('shows no cost for seller (FBA) units', async () => {
    nav.set('/inventory?tab=receiving');
    await renderScreen(<ReceivingPanel />);
    const user = userEvent.setup();
    const dock = await screen.findAllByTestId('dock-shipment');
    await user.click(dock.find((d) => d.textContent?.includes('FBA-IN-006601')) as HTMLElement);
    const screenEl = await screen.findByTestId('receiving-screen');
    expect(within(screenEl).queryByTestId('cost-fields')).not.toBeInTheDocument();
  });
});

describe('lots and valuation show what was paid and the USD value', () => {
  const client = () => createStaffApiClient({ baseUrl: 'http://staff.test/api/bff', headers: { cookie: `${MOCK_STAFF_ROLE_COOKIE}=platform.super_admin` }, fetch: (req) => handleStaffMockRequest(req) });

  it('the lot sheet gives the cost paid, the rate with its date and source, the USD cost and the components', async () => {
    const lots = await client().inventory.lots({ sku: 'AT-JOL-200061', page: 0, size: 50 });
    const mx = lots.items.find((l) => l.lotNo === 'JOL-MX-2610');
    await renderScreen(<LotSheet id={mx?.id ?? null} onClose={() => undefined} />);
    const sheet = await screen.findByTestId('lot-sheet');
    expect(within(sheet).getByText('MXN 120.00 / unit')).toBeInTheDocument();
    expect(within(sheet).getByText(/1 MXN = 0\.0577 USD · .* · from the rates table/)).toBeInTheDocument();
    expect(within(sheet).getByText('$6.92 / unit')).toBeInTheDocument();
    expect(within(sheet).getByText(/Goods MXN 100\.00 · freight MXN 15\.00 · duty MXN 4\.00 · other MXN 1\.00/)).toBeInTheDocument();
  });

  it('a US dollar lot shows only its cost', async () => {
    const lots = await client().inventory.lots({ sku: 'AT-JOL-200061', page: 0, size: 50 });
    const us = lots.items.find((l) => l.lotNo === 'JOL-2607');
    await renderScreen(<LotSheet id={us?.id ?? null} onClose={() => undefined} />);
    const sheet = await screen.findByTestId('lot-sheet');
    expect(within(sheet).getByText('$3.12 / unit')).toBeInTheDocument();
    expect(within(sheet).queryByText('Exchange rate')).not.toBeInTheDocument();
  });

  it('the overview totals stock in USD with the pesos paid alongside', async () => {
    await renderScreen(<OverviewPanel />);
    const kpi = (await screen.findByText('Sellable stock')).parentElement as HTMLElement;
    expect(kpi).toHaveTextContent(/paid in MXN\s*7,200\.00/);
    const houston = (await screen.findByRole('cell', { name: 'Houston FC' })).parentElement as HTMLElement;
    expect(within(houston).getByText(/MXN\s*7,200\.00/)).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Value (USD)' })).toBeInTheDocument();
  });
});

describe('Settings › Exchange rates', () => {
  it('lists the rates, newest first, and sets one with a reason', async () => {
    nav.set('/settings?section=exchange-rates');
    renderWithProviders(
      <ConsoleShell>
        <SettingsHub />
      </ConsoleShell>
    );
    expect(await screen.findByRole('heading', { name: 'Exchange rates' })).toBeInTheDocument();
    expect(await screen.findByText(/Customers always pay in US dollars/)).toBeInTheDocument();
    expect((await screen.findAllByText('Banxico FIX')).length).toBeGreaterThan(0);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'New' }));
    const dialog = await screen.findByRole('dialog', { name: 'Set an exchange rate' });
    await user.click(within(dialog).getByRole('button', { name: 'Save rate' }));
    expect(within(dialog).getAllByText('Required').length).toBeGreaterThan(0);
    await user.selectOptions(within(dialog).getByLabelText('Currency'), 'ZAR');
    await user.type(within(dialog).getByLabelText('Day'), '2026-10-22');
    await user.type(within(dialog).getByLabelText('USD per unit'), '0.054');
    await user.type(within(dialog).getByLabelText('Reason'), 'First ZAR purchase');
    await user.click(within(dialog).getByRole('button', { name: 'Save rate' }));
    await waitFor(() => expect(nav.search).toContain('open=ZAR_2026-10-22'));
  });

  it('is read only for finance: the rates are visible but cannot be changed', async () => {
    asRoles('platform.finance');
    nav.set('/settings?section=exchange-rates');
    renderWithProviders(
      <ConsoleShell>
        <SettingsHub />
      </ConsoleShell>
    );
    expect(await screen.findByRole('heading', { name: 'Exchange rates' })).toBeInTheDocument();
    expect(await screen.findByText(/Read only/)).toBeInTheDocument();
    await screen.findAllByText('Banxico FIX');
    expect(screen.queryByRole('button', { name: 'New' })).not.toBeInTheDocument();
  });
});
