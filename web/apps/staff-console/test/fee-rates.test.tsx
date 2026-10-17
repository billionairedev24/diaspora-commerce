import { type FeePlan, type FeeScheduleView,type FeesOverview } from '@afrotransact/api-client';
import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { NegotiatedRatesPanel } from '@/components/fees/NegotiatedRatesPanel';
import { RateCalculator } from '@/components/fees/RateCalculator';
import { RateMatrixPanel } from '@/components/fees/RateMatrixPanel';
import { addRow, columnRange, draftFromMatrix, matrixFrom, OTHER, setCell } from '@/components/fees/rates-model';

import { asRoles } from './mock-api';
import { renderScreen } from './render';

const NOW = new Date('2026-10-10T15:00:00Z');

const plan = (code: string, kind: 'SELLER' | 'PROVIDER', name: string): FeePlan => ({
  code, kind, name, status: 'ACTIVE', visibility: 'PUBLIC', featureText: null, advertised: null, configured: null, warning: null
});
const plans = [plan('seller_growth', 'SELLER', 'Seller Growth'), plan('provider_solo', 'PROVIDER', 'Provider Solo')];

const base: FeeScheduleView = {
  id: 'fee-2', version: 2, status: 'ACTIVE', effectiveFrom: '2026-08-01T00:00:00Z', currency: 'USD', defaultCommissionBps: 1000,
  categoryRates: [{ category: 'groceries', label: 'Groceries', commissionBps: 800 }],
  overrides: [],
  cardPctBps: 290, cardFixedMinor: 30, fbaPerUnitMinor: 325, reverseCommissionOnRefund: true, note: 'Q3', createdBy: 'Musa Bello', createdAt: null,
  deliveryPricing: null,
  cancelledAt: null, cancelledBy: null, cancelReason: null, cancellable: false,
  planFba: [], planRates: [
    { planCode: 'seller_growth', planName: 'Seller Growth', category: '*', label: 'Other categories', bps: 1000 },
    { planCode: 'seller_growth', planName: 'Seller Growth', category: 'fashion', label: 'Fashion', bps: 1200 }
  ],
  connectionFee: {
    defaultBps: 1000,
    categoryRates: [],
    planRates: [{ planCode: 'provider_solo', planName: 'Provider Solo', category: '*', label: 'Other services', bps: 1000 }]
  }
} as FeeScheduleView;

const overview: Pick<FeesOverview, 'minEffectiveFrom' | 'immediateAllowed' | 'retail' | 'limits'> = {
  minEffectiveFrom: NOW.toISOString(),
  immediateAllowed: true,
  retail: { orgId: 'org-afrotransact-retail', name: 'AfroTransact Retail', note: '' },
  limits: { commissionMaxBps: 5000, cardPctMaxBps: 1000, cardFixedMaxMinor: 1000, fbaPerUnitMaxMinor: 10_000, noteMinLength: 3, noteMaxLength: 500, maxCategoryRates: 100, maxOverrides: 500,
    deliveryMaxRadiusMiles: 200, deliveryAmountMaxMinor: 10_000, deliveryMaxBands: 20, maxDeliveryOverrides: 500 }
};

describe('rate matrix model', () => {
  it('lays out the commission section as category × plan with the default first', () => {
    const m = matrixFrom(base, plans, 'PRODUCT');
    expect(m.plans.map((p) => p.code)).toEqual(['seller_growth']);
    expect(m.rows.map((r) => [r.key, r.base, r.plans.seller_growth])).toEqual([
      [OTHER, '10.00', '10.00'],
      ['fashion', '', '12.00'],
      ['groceries', '8.00', '']
    ]);
    expect(columnRange(m, 'seller_growth')).toBe('10–12%');
  });

  it('builds a draft that replaces only the connection-fee section', () => {
    let m = matrixFrom(base, plans, 'SERVICE');
    expect(m.plans.map((p) => p.code)).toEqual(['provider_solo']);
    m = addRow(m, ' Hair & Beauty ');
    m = setCell(m, 'hair & beauty', 'provider_solo', '8');
    m = setCell(m, OTHER, null, '9.5');
    const out = draftFromMatrix(m, base, overview, { immediate: true, effectiveFrom: null }, 'Solo 8 % on hair', NOW);
    expect(out.errors).toEqual({});
    expect(out.draft?.connectionFee).toEqual({
      defaultBps: 950,
      categoryRates: [],
      planRates: [
        { planCode: 'provider_solo', category: '*', bps: 1000 },
        { planCode: 'provider_solo', category: 'hair & beauty', bps: 800 }
      ]
    });
    expect(out.draft?.defaultCommissionBps).toBe(1000);
    expect(out.draft?.planRates).toBeUndefined();
  });

  it('keys errors by cell and requires the default', () => {
    let m = matrixFrom(base, plans, 'PRODUCT');
    m = setCell(m, OTHER, null, '');
    m = setCell(m, 'fashion', 'seller_growth', '50.01');
    const out = draftFromMatrix(m, base, overview, { immediate: true, effectiveFrom: null }, '', NOW);
    expect(out.draft).toBeNull();
    expect(out.errors['other:base']).toBe('Enter a rate');
    expect(out.errors['fashion:seller_growth']).toBe('Enter 0.00 to 50.00');
    expect(out.errors.note).toBeTruthy();
  });
});

describe('Fees & commissions screens', () => {
  beforeEach(() => {
    resetStaffDb();
    asRoles(null);
  });

  it('shows the seeded plan rates and saves a new version from the commission matrix', async () => {
    const user = userEvent.setup();
    await renderScreen(<RateMatrixPanel kind="PRODUCT" />);
    const table = await screen.findByRole('table', { name: 'Commission by category and plan' });
    expect(within(table).getByText('Seller Growth')).toBeInTheDocument();
    expect(within(table).getByText('8–12%')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Edit rates' }));
    const cell = screen.getByLabelText('Groceries · Seller Growth');
    await user.clear(cell);
    await user.type(cell, '7.5');
    await user.click(screen.getByLabelText('Take effect immediately'));
    await user.type(screen.getByLabelText('Why the rates change'), 'Growth groceries to 7.5 %');
    await user.click(screen.getByRole('button', { name: 'Save as a new version' }));
    expect(await screen.findByText(/Fee schedule v\d+ saved/)).toBeInTheDocument();
  });

  it('lists negotiated rates with filters and ends one with a reason', async () => {
    const user = userEvent.setup();
    asRoles('platform.finance');
    await renderScreen(<NegotiatedRatesPanel />);
    const table = await screen.findByRole('table', { name: 'Negotiated rates' });
    expect(await within(table).findByText(/Wholesale partner/)).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Type', { selector: 'select' }), 'CONNECTION_FEE');
    await waitFor(() => expect(within(table).queryByText(/Wholesale partner/)).not.toBeInTheDocument());
    expect(within(table).getByText(/Launch provider/)).toBeInTheDocument();
    await user.click(within(table).getByRole('button', { name: 'End' }));
    await user.click(screen.getByRole('button', { name: 'End now' }));
    expect(await screen.findByText(/Say why/)).toBeInTheDocument();
    await user.type(screen.getByLabelText(/Why it ends/), 'Promotion over');
    await user.click(screen.getByRole('button', { name: 'End now' }));
    expect(await screen.findByText(/Negotiated rate of .* ended/)).toBeInTheDocument();
  });

  it('requires a reason when negotiating a rate', async () => {
    const user = userEvent.setup();
    asRoles('platform.finance');
    await renderScreen(<NegotiatedRatesPanel org={{ orgId: 'org-lagos', name: 'Lagos Threads' }} />);
    await user.click(await screen.findByRole('button', { name: 'Negotiate a rate' }));
    await user.type(screen.getByLabelText('Rate (%)'), '7');
    await user.click(screen.getByRole('button', { name: 'Negotiate' }));
    expect(await screen.findByText(/Say why/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('Reason (kept in the audit trail)'), 'Matching a competitor offer');
    await user.click(screen.getByRole('button', { name: 'Negotiate' }));
    expect(await screen.findByText('7.00% negotiated with Lagos Threads')).toBeInTheDocument();
  });

  it('explains the effective rate in the calculator', async () => {
    const user = userEvent.setup();
    await renderScreen(<RateCalculator />);
    await user.type(await screen.findByRole('searchbox'), 'kente');
    await user.click(await screen.findByRole('button', { name: /Kente Naturals/ }));
    await user.type(screen.getByLabelText('Category'), 'groceries');
    await user.click(screen.getByRole('button', { name: 'Calculate' }));
    const quote = await screen.findByTestId('rate-quote');
    expect(within(quote).getByText('6.00%')).toBeInTheDocument();
    expect(within(quote).getByText(/Negotiated/i)).toBeInTheDocument();
  });
});
