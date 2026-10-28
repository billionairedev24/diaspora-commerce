import { bpsToPercentText, type FeeScheduleView, type FeesOverview, formatBps, parsePercent } from '@afrotransact/api-client';
import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { FeeSchedulesPanel } from '@/components/fees/FeeSchedulesPanel';
import { affectedSellers, diffDraft, formFrom, impactRows, parseDollars, toDraft } from '@/components/fees/model';

import { asRoles, mockApi } from './mock-api';
import { renderScreen } from './render';

const NOW = new Date('2026-10-10T15:00:00Z');

const base: FeeScheduleView = {
  id: 'fee-2', version: 2, status: 'ACTIVE', effectiveFrom: '2026-08-01T00:00:00Z', currency: 'USD', defaultCommissionBps: 1000,
  categoryRates: [
    { category: 'fashion', label: 'Fashion', commissionBps: 1150 },
    { category: 'groceries', label: 'Groceries', commissionBps: 800 }
  ],
  overrides: [{ orgId: 'org-kente', orgName: 'Kente Naturals', category: 'beauty', categoryLabel: 'Beauty', commissionBps: 800 }],
  cardPctBps: 290, cardFixedMinor: 30, fbaPerUnitMinor: 325, reverseCommissionOnRefund: true, note: 'Q3', createdBy: 'Musa Bello', createdAt: null,
  deliveryPricing: {
    enabled: true, maxRadiusMiles: 25,
    customerCharge: { mode: 'DISTANCE', flatPerPackageMinor: null, baseMinor: 499, perMileMinor: 85, bands: [], minMinor: 599, maxMinor: 2499 },
    sellerFee: { mode: 'FLAT', flatPerPackageMinor: 200, baseMinor: null, perMileMinor: null, bands: [], minMinor: null, maxMinor: null },
    sellerOverrides: [{ orgId: 'org-kente', orgName: 'Kente Naturals', sellerFee: { mode: 'FLAT', flatPerPackageMinor: 150, baseMinor: null, perMileMinor: null, bands: [], minMinor: null, maxMinor: null } }]
  },
  cancelledAt: null, cancelledBy: null, cancelReason: null, cancellable: false,
  planRates: [], planFba: [], connectionFee: null
};

const overview: Pick<FeesOverview, 'minEffectiveFrom' | 'immediateAllowed' | 'retail' | 'limits'> = {
  minEffectiveFrom: NOW.toISOString(),
  immediateAllowed: true,
  retail: { orgId: 'org-afrotransact-retail', name: 'AfroTransact Retail', note: 'No commission, no fees (merchant of record)' },
  limits: { commissionMaxBps: 5000, cardPctMaxBps: 1000, cardFixedMaxMinor: 1000, fbaPerUnitMaxMinor: 10_000, noteMinLength: 3, noteMaxLength: 500, maxCategoryRates: 100, maxOverrides: 500,
    deliveryMaxRadiusMiles: 200, deliveryAmountMaxMinor: 10_000, deliveryMaxBands: 20, maxDeliveryOverrides: 500 }
};
const cats = [
  { key: 'fashion', label: 'Fashion', source: 'TAXONOMY' as const },
  { key: 'groceries', label: 'Groceries', source: 'TAXONOMY' as const },
  { key: 'beauty', label: 'Beauty', source: 'TAXONOMY' as const }
];
const names = (id: string) => ({ 'org-kente': 'Kente Naturals', 'org-lagos': 'Lagos Threads' })[id] ?? id;

describe('fee editor model', () => {
  it('converts percentages and dollars to basis points and minor units exactly', () => {
    expect(parsePercent('12.5')).toBe(1250);
    expect(parsePercent('12.50 %')).toBe(1250);
    expect(parsePercent('0')).toBe(0);
    expect(parsePercent('0.07')).toBe(7);
    expect(parsePercent('33.33')).toBe(3333);
    expect(parsePercent('12.555')).toBeNull();
    expect(parsePercent('-1')).toBeNull();
    expect(parsePercent('abc')).toBeNull();
    expect(parsePercent('')).toBeNull();
    expect(bpsToPercentText(1250)).toBe('12.50');
    expect(bpsToPercentText(5)).toBe('0.05');
    expect(formatBps(800)).toBe('8.00%');
    expect(parseDollars('3.25')).toBe(325);
    expect(parseDollars('$3.5')).toBe(350);
    expect(parseDollars('3.256')).toBeNull();
    for (const bps of [0, 1, 99, 290, 1150, 5000]) expect(parsePercent(bpsToPercentText(bps))).toBe(bps);
  });

  it('starts from the base version with percentages and validates like the BFF', () => {
    const form = formFrom(base, overview, NOW);
    expect(form.defaultCommission).toBe('10.00');
    expect(form.categories.map((c) => [c.category, c.rate])).toEqual([['fashion', '11.50'], ['groceries', '8.00']]);
    expect(form.overrides[0]).toMatchObject({ orgName: 'Kente Naturals', category: 'beauty', rate: '8.00' });
    expect(form.fbaPerUnit).toBe('3.25');
    expect(new Date(form.effectiveAt).getTime()).toBeGreaterThan(NOW.getTime());

    const ok = toDraft({ ...form, note: 'Fashion to 12.5 %' }, overview, NOW);
    expect(ok.errors).toEqual({});
    expect(ok.draft?.categoryRates[0]).toEqual({ category: 'fashion', commissionBps: 1150 });
    expect(ok.draft?.cardFixedMinor).toBe(30);

    const bad = toDraft(
      {
        ...form,
        effectiveAt: '2026-10-09T10:00',
        defaultCommission: '50.01',
        cardPct: '2.9.0',
        note: '',
        categories: [...form.categories, { rowId: 'x', category: ' Fashion ', rate: '10' }],
        overrides: [{ rowId: 'y', orgId: 'org-afrotransact-retail', orgName: 'AfroTransact Retail', category: '', rate: '0' }]
      },
      overview,
      NOW
    );
    expect(bad.draft).toBeNull();
    expect(bad.errors.effectiveFrom).toMatch(/future/);
    expect(bad.errors.defaultCommissionBps).toBe('Enter a rate between 0.00% and 50.00%');
    expect(bad.errors.cardPctBps).toMatch(/percentage/);
    expect(bad.errors.note).toMatch(/Explain/);
    expect(bad.errors['categoryRates.2.category']).toMatch(/twice/);
    expect(bad.errors['overrides.0.orgId']).toMatch(/merchant of record/);

    const blocked = toDraft({ ...form, immediate: true, note: 'Now please' }, { ...overview, immediateAllowed: false }, NOW);
    expect(blocked.errors.immediate).toMatch(/already scheduled/);
  });

  it('diffs against the current version and previews the impact on a $100 sale', () => {
    const form = formFrom(base, overview, NOW);
    const changed = toDraft(
      {
        ...form,
        note: 'Fashion up, Lagos deal',
        categories: [{ rowId: 'a', category: 'fashion', rate: '12.50' }, { rowId: 'b', category: 'books', rate: '9' }],
        overrides: [{ rowId: 'c', orgId: 'org-lagos', orgName: 'Lagos Threads', category: 'fashion', rate: '10.00' }]
      },
      overview,
      NOW
    );
    const draft = changed.draft;
    if (!draft) throw new Error(JSON.stringify(changed.errors));
    const diff = diffDraft(base, draft, cats, names);
    expect(diff.map((d) => [d.label, d.kind, d.from, d.to])).toEqual([
      ['Books commission', 'added', '—', '9.00%'],
      ['Fashion commission', 'changed', '11.50%', '12.50%'],
      ['Groceries commission', 'removed', '8.00%', '—'],
      ['Kente Naturals · Beauty', 'removed', '8.00%', '—'],
      ['Lagos Threads · Fashion', 'added', '—', '10.00%']
    ]);
    const impact = impactRows(base, draft, cats, 10_000);
    expect(impact.find((r) => r.category === 'fashion')).toMatchObject({ beforeMinor: 1150, afterMinor: 1250 });
    // Groceries lose their rate and fall back to the 10.00 % default.
    expect(impact.find((r) => r.category === 'groceries')).toMatchObject({ beforeMinor: 800, afterMinor: 1000 });
    expect(impact.at(-1)).toMatchObject({ label: 'Any other category', beforeMinor: 1000, afterMinor: 1000 });
    expect(affectedSellers(base, draft, cats, names)).toEqual([
      { orgId: 'org-kente', orgName: 'Kente Naturals', changes: [{ category: 'Beauty', from: 800, to: null }] },
      { orgId: 'org-lagos', orgName: 'Lagos Threads', changes: [{ category: 'Fashion', from: null, to: 1000 }] }
    ]);
  });
});

describe('fee schedules screen', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
  });

  it('is read-only for staff without payments:configure', async () => {
    asRoles('platform.auditor');
    await renderScreen(<FeeSchedulesPanel />);
    const table = await screen.findByRole('table', { name: 'Fee schedule versions' });
    expect(within(table).getByText('Scheduled')).toBeInTheDocument();
    expect(within(table).getByText('Cancelled')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New version' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Cancel v/ })).not.toBeInTheDocument();
    expect(screen.getByText(/needs payments:configure/)).toBeInTheDocument();
    expect(screen.getByText(/No commission, no fees \(merchant of record\)/)).toBeInTheDocument();
  });

  it('creates a version with a category change and a seller override, after review', async () => {
    asRoles('platform.finance');
    const user = userEvent.setup();
    await renderScreen(<FeeSchedulesPanel />);
    await user.click(await screen.findByRole('button', { name: 'New version' }));
    const fashion = screen.getByLabelText('Commission for fashion (%)');
    expect(fashion).toHaveValue('13.00');
    await user.clear(fashion);
    await user.type(fashion, '12.5');
    await user.type(screen.getByRole('combobox', { name: 'Seller or provider' }), 'retail');
    const retail = await within(await screen.findByRole('listbox')).findByRole('option', { name: /AfroTransact Retail/ });
    expect(retail).toHaveAttribute('aria-disabled', 'true');
    await user.clear(screen.getByRole('combobox', { name: 'Seller or provider' }));
    await user.type(screen.getByRole('combobox', { name: 'Seller or provider' }), 'lagos');
    await user.click(await within(await screen.findByRole('listbox')).findByRole('option', { name: /Lagos Threads/ }));
    await user.selectOptions(screen.getByLabelText('Category for Lagos Threads'), 'fashion');
    const rate = screen.getByLabelText('Commission for Lagos Threads (%)');
    await user.clear(rate);
    await user.type(rate, '9.75');
    await user.click(screen.getByRole('button', { name: 'Review changes' }));
    expect(await screen.findByText(/Explain the change/, { selector: 'p' })).toBeInTheDocument();
    await user.type(screen.getByLabelText(/Note explaining the change/), 'Fashion to 12.50 %, Lagos Threads deal');
    await user.click(screen.getByRole('button', { name: 'Review changes' }));

    const changes = await screen.findByRole('table', { name: 'Changes' });
    expect(within(changes).getByRole('row', { name: /Fashion commission 13.00% 12.50%/ })).toBeInTheDocument();
    expect(within(changes).getByRole('row', { name: /Lagos Threads · Fashion \(added\) — 9.75%/ })).toBeInTheDocument();
    const impact = screen.getByRole('table', { name: 'Commission impact' });
    expect(within(impact).getByRole('row', { name: /Fashion \$11.50 \(11.50%\) \$12.50 \(12.50%\) \+\$1.00/ })).toBeInTheDocument();
    expect(within(screen.getByRole('list', { name: 'Sellers affected' })).getByText('Lagos Threads')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Publish version' }));

    const table = await screen.findByRole('table', { name: 'Fee schedule versions' });
    await waitFor(() => expect(within(table).getByText('v5')).toBeInTheDocument());
    const fees = await mockApi().getFees();
    const v5 = fees.schedules.find((s) => s.version === 5);
    expect(v5?.categoryRates.find((c) => c.category === 'fashion')?.commissionBps).toBe(1250);
    expect(v5?.overrides.find((o) => o.orgId === 'org-lagos')).toMatchObject({ category: 'fashion', commissionBps: 975 });
  }, 30_000);

  it('cancels a scheduled version with a reason', async () => {
    asRoles('platform.finance');
    const user = userEvent.setup();
    await renderScreen(<FeeSchedulesPanel />);
    await user.click(await screen.findByRole('button', { name: 'Cancel v4' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancel fee schedule v4' });
    await user.type(within(dialog).getByLabelText(/Reason/), 'Holiday promo postponed');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel version' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const fees = await mockApi().getFees();
    expect(fees.schedules.find((s) => s.version === 4)).toMatchObject({ status: 'CANCELLED', cancelReason: 'Holiday promo postponed' });
  }, 20_000);
});

describe('FBA fee by plan', () => {
  it('sends only the plans that have their own fee and checks the amounts', () => {
    const form = formFrom({ ...base, planFba: [{ planCode: 'seller_pro', planName: 'Seller Pro', feeMinor: 200 }] }, overview, NOW);
    expect(form.planFba).toEqual({ seller_pro: '2.00' });
    const ok = toDraft({ ...form, note: 'Pro plan FBA discount' }, overview, NOW);
    expect(ok.draft?.planFba).toEqual([{ planCode: 'seller_pro', feeMinor: 200 }]);
    const bad = toDraft({ ...form, planFba: { seller_pro: 'abc' }, note: 'Pro plan FBA discount' }, overview, NOW);
    expect(bad.errors['planFba.seller_pro']).toBeDefined();
  });
});
