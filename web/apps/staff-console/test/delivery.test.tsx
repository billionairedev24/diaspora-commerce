import { type DeliveryPricing, type FeeScheduleView, tariffSummary } from '@afrotransact/api-client';
import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { PickupsPanel } from '@/components/dispatch/PickupsPanel';
import { deliveryFormFrom, diffDelivery, previewQuote, toDeliveryDraft } from '@/components/fees/delivery-model';
import { FeeSchedulesPanel } from '@/components/fees/FeeSchedulesPanel';
import { tEn } from '@/i18n/t';

import { asRoles, mockApi } from './mock-api';
import { renderScreen } from './render';

const pricing: DeliveryPricing = {
  enabled: true,
  maxRadiusMiles: 25,
  customerCharge: { mode: 'DISTANCE', flatPerPackageMinor: null, baseMinor: 499, perMileMinor: 85, bands: [], minMinor: 599, maxMinor: 2499 },
  sellerFee: { mode: 'FLAT', flatPerPackageMinor: 200, baseMinor: null, perMileMinor: null, bands: [], minMinor: null, maxMinor: null },
  sellerOverrides: [{ orgId: 'org-kente', orgName: 'Kente Naturals', sellerFee: { mode: 'FLAT', flatPerPackageMinor: 150, baseMinor: null, perMileMinor: null, bands: [], minMinor: null, maxMinor: null } }]
};
const base = { version: 4, deliveryPricing: pricing } as FeeScheduleView;
const names = (id: string) => ({ 'org-kente': 'Kente Naturals', 'org-ade': "Ade's Kitchen" })[id] ?? id;

describe('delivery pricing model (S255)', () => {
  it('starts from the base version and leaves the section out until it is edited (carried over)', () => {
    const form = deliveryFormFrom(base);
    expect(form).toMatchObject({ include: false, enabled: true, radius: '25' });
    expect(form.customer).toMatchObject({ mode: 'DISTANCE', base: '4.99', perMile: '0.85', min: '5.99', max: '24.99' });
    expect(toDeliveryDraft(form, {}, tEn)).toBeUndefined();
    expect(deliveryFormFrom(null).customer.base).toBe('4.99'); // launch defaults when no version priced it yet
  });

  it('converts dollars and miles exactly and reports typing errors with the BFF’s keys', () => {
    const form = deliveryFormFrom(base);
    const errors: Record<string, string> = {};
    const draft = toDeliveryDraft(
      { ...form, include: true, radius: '30', customer: { ...form.customer, perMile: '0.95', max: '', bands: [{ rowId: 'b', from: '0', to: '3', fee: '3.99' }] } },
      errors,
      tEn
    );
    expect(errors).toEqual({});
    expect(draft?.maxRadiusMiles).toBe(30);
    expect(draft?.customerCharge).toMatchObject({ perMileMinor: 95, maxMinor: null, bands: [{ fromMiles: 0, toMiles: 3, feeMinor: 399 }] });
    expect(draft?.sellerOverrides[0]).toMatchObject({ orgId: 'org-kente', sellerFee: { mode: 'FLAT', flatPerPackageMinor: 150 } });
    const bad: Record<string, string> = {};
    toDeliveryDraft({ ...form, include: true, radius: 'far', seller: { ...form.seller, flat: 'two' } }, bad, tEn);
    expect(Object.keys(bad).sort()).toEqual(['deliveryPricing.maxRadiusMiles', 'deliveryPricing.sellerFee.flatPerPackageMinor']);
  });

  it('diffs the section and previews quotes like payments', () => {
    const next = { ...pricing, maxRadiusMiles: 30, sellerOverrides: [{ orgId: 'org-ade', sellerFee: pricing.sellerOverrides[0]?.sellerFee ?? pricing.customerCharge! }] };
    const rows = diffDelivery(pricing, next, names, tEn);
    expect(rows.map((r) => [r.label, r.kind, r.from, r.to])).toEqual([
      ['AfroTransact Delivery radius', 'changed', '25 mi', '30 mi'],
      ["Ade's Kitchen · seller delivery fee", 'added', '—', '$1.50 per package'],
      ['Kente Naturals · seller delivery fee', 'removed', '$1.50 per package', '—']
    ]);
    expect(diffDelivery(pricing, undefined, names, tEn)).toEqual([]);
    expect(previewQuote(pricing, 'org-kente', 7.4, 2, 'org-retail')).toEqual({ available: true, reason: null, customerMinor: 1128, sellerMinor: 300, source: 'OVERRIDE' });
    expect(previewQuote(pricing, 'org-retail', 7.4, 1, 'org-retail')).toMatchObject({ sellerMinor: 0, source: 'RETAIL' });
    expect(previewQuote(pricing, '', 31, 1, 'org-retail')).toMatchObject({ available: false, reason: 'BEYOND_MAX_RADIUS' });
    expect(previewQuote(null, '', 3, 1, '')).toMatchObject({ available: false, reason: 'NOT_CONFIGURED' });
    expect(tariffSummary(pricing.customerCharge)).toBe('$4.99 + $0.85/mi, at least $5.99, at most $24.99');
  });
});

describe('fee editor: AfroTransact Delivery pricing section', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
  });

  it('edits the radius and the customer tariff, previews a quote, shows the diff on review and publishes', async () => {
    asRoles('platform.finance');
    const user = userEvent.setup();
    await renderScreen(<FeeSchedulesPanel />);
    expect(await screen.findByTestId('current-delivery-pricing')).toHaveTextContent('$4.99 + $0.85/mi');
    await user.click(await screen.findByRole('button', { name: 'New version' }));
    expect(screen.getByText(/delivery pricing carries over unchanged/)).toBeInTheDocument();
    const preview = screen.getByTestId('delivery-quote-preview');
    expect(within(preview).getAllByText('$11.28')).toHaveLength(2);
    await user.click(screen.getByLabelText(/Change AfroTransact Delivery pricing in this version/));
    const radius = screen.getByLabelText('Maximum radius (miles)');
    await user.clear(radius);
    await user.type(radius, '30');
    const perMile = within(screen.getByRole('group', { name: 'Customer delivery charge' })).getByLabelText('Per-mile rate ($)');
    await user.clear(perMile);
    await user.type(perMile, '0.95');
    await waitFor(() => expect(within(screen.getByTestId('delivery-quote-preview')).getByText('$12.02')).toBeInTheDocument());
    await user.type(screen.getByLabelText(/Note explaining the change/), 'Wider delivery radius');
    await user.click(screen.getByRole('button', { name: 'Review changes' }));

    const changes = await screen.findByRole('table', { name: 'Changes' });
    expect(within(changes).getByRole('row', { name: /AfroTransact Delivery radius 25 mi 30 mi/ })).toBeInTheDocument();
    expect(within(changes).getByRole('row', { name: /Customer delivery charge .*\$0\.85\/mi.*\$0\.95\/mi/ })).toBeInTheDocument();
    const examples = screen.getByRole('table', { name: 'AfroTransact Delivery examples' });
    expect(within(examples).getByRole('row', { name: /10 mi \$13\.49 \$14\.49 \$2\.00 \$2\.00/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Publish version' }));

    await waitFor(async () => {
      const fees = await mockApi().getFees();
      expect(fees.schedules.find((s) => s.version === 5)?.deliveryPricing).toMatchObject({ maxRadiusMiles: 30, customerCharge: { perMileMinor: 95 } });
    });
  }, 30_000);

  it('points at the field when the radius is out of range', async () => {
    asRoles('platform.finance');
    const user = userEvent.setup();
    await renderScreen(<FeeSchedulesPanel />);
    await user.click(await screen.findByRole('button', { name: 'New version' }));
    await user.click(screen.getByLabelText(/Change AfroTransact Delivery pricing in this version/));
    const radius = screen.getByLabelText('Maximum radius (miles)');
    await user.clear(radius);
    await user.type(radius, '250');
    await user.type(screen.getByLabelText(/Note explaining the change/), 'Too far');
    await user.click(screen.getByRole('button', { name: 'Review changes' }));
    expect(await screen.findByRole('link', { name: /at most 200 miles/ })).toHaveAttribute('href', '#dp-maxRadiusMiles');
  }, 30_000);
});

describe('dispatch: seller pickups', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
  });

  it('lists the nearest drivers and overrides with a required reason, then shows the history', async () => {
    asRoles('platform.dispatcher');
    const user = userEvent.setup();
    await renderScreen(<PickupsPanel />);
    const rows = await screen.findAllByTestId('pickup-row');
    expect(rows).toHaveLength(3);
    await user.click(screen.getByRole('button', { name: 'Nearest drivers for AT-2026-004812' }));
    const drivers = await screen.findAllByTestId('driver-suggestion');
    expect(drivers).toHaveLength(5);
    expect(drivers[0]).toHaveTextContent('2.6 mi by road · 8 min');
    expect(drivers[4]).toHaveTextContent('straight line');
    await user.click(screen.getByRole('button', { name: 'Assign to this route' }));
    expect(await screen.findByText('Choose a route')).toBeInTheDocument();
    await user.click(screen.getByLabelText('Assign to HOU Southwest · Ngozi (Ngozi Eze)'));
    await user.type(screen.getByLabelText(/Reason/), 'ok');
    await user.click(screen.getByRole('button', { name: 'Assign to this route' }));
    expect(await screen.findByText(/\(5 to 500 characters\)/)).toBeInTheDocument();
    await user.type(screen.getByLabelText(/Reason/), ' — Tunde’s van is full');
    await user.click(screen.getByRole('button', { name: 'Assign to this route' }));
    await waitFor(() => expect(screen.getAllByTestId('pickup-row')).toHaveLength(2));
    const history = await screen.findAllByTestId('pickup-assignment');
    expect(history[0]).toHaveTextContent('Dispatch override');
    expect(history[0]).toHaveTextContent('HOU Southwest · Ngozi');
    expect(history[0]).not.toHaveTextContent('R-HOU-SW-07');
    expect(history[0]).not.toHaveTextContent('drv-ngozi');
  }, 30_000);

  it('is read-only without delivery:dispatch edit access', async () => {
    asRoles('platform.auditor');
    await renderScreen(<PickupsPanel />);
    await screen.findAllByTestId('pickup-row').catch(() => null);
    expect(screen.queryByRole('button', { name: 'Assign to this route' })).not.toBeInTheDocument();
  });
});
