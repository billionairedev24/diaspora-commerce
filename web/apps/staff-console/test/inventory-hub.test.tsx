import { GoodsReceiptSchema, type Lot, type RetailItem } from '@afrotransact/api-client';
import { resetHubDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { CountsPanel } from '@/components/inventory/CountsPanel';
import { markdownSuggestion } from '@/components/inventory/ExpiryPanel';
import { draftErrors } from '@/components/inventory/InboundPanel';
import { palletSscc } from '@/components/inventory/Labels';
import { ReceivingPanel } from '@/components/inventory/ReceivingPanel';
import { pickRanks } from '@/components/inventory/StockPanel';
import { rowsOf } from '@/components/retail/ReceiptScreen';
import { RetailView } from '@/components/retail/RetailView';

import { renderScreen } from './render';
import { nav } from './setup';

const item = (over: Partial<RetailItem>): RetailItem =>
  ({ id: 'i1', sku: 'AT-JOL-200061', gtin: '086001200069', name: 'Jollof', atin: 'ATJOLLOF01', supplierItems: [], shelfLifeDays: 540, storage: 'AMBIENT', reorderPoint: 0, casePack: 24, priceMinor: 649, currency: 'USD', listingStatus: 'LIVE', version: 0, ...over }) as RetailItem;
const line = (over: Record<string, unknown> = {}) => ({ key: 'k', itemSku: '', atin: 'ATABCDEF12', sku: '', qty: '12', casePack: '', lotNo: '', mfgDate: '', expDate: '', perishable: false, ...over });

describe('inbound shipment form rules (S281)', () => {
  const today = '2026-10-11';
  it('requires a lot and an expiry for perishables and for shelf-life items', () => {
    expect(draftErrors([line({ perishable: true })], new Map(), today)).toMatchObject({ 'lines.0.expDate': expect.stringMatching(/required/), 'lines.0.lotNo': expect.stringMatching(/required/) });
    const items = new Map([['AT-JOL-200061', item({})]]);
    expect(draftErrors([line({ itemSku: 'AT-JOL-200061', atin: '' })], items, today)['lines.0.expDate']).toMatch(/required/);
    expect(draftErrors([line({})], new Map(), today)).toEqual({});
  });

  it('checks whole cases, quantities, ATINs and linked items', () => {
    expect(draftErrors([line({ qty: '10', casePack: '4' })], new Map(), today)['lines.0.qty']).toMatch(/multiple of 4/);
    expect(draftErrors([line({ qty: '0' })], new Map(), today)['lines.0.qty']).toBeDefined();
    expect(draftErrors([line({ atin: 'nope' })], new Map(), today)['lines.0.atin']).toBeDefined();
    const unlinked = new Map([['AT-OKR', item({ sku: 'AT-OKR', atin: null })]]);
    expect(draftErrors([line({ itemSku: 'AT-OKR', atin: '', lotNo: 'L1', expDate: '2027-01-01' })], unlinked, today)['lines.0.atin']).toMatch(/Link this item/);
    expect(draftErrors([], new Map(), today).lines).toBeDefined();
  });
});

describe('FEFO, expiry and labels', () => {
  const lot = (id: string, sku: string, nodeId: string, expDate: string | null, status = 'SELLABLE', qtyAvailable = 5) => ({ id, sku, nodeId, expDate, status, qtyAvailable, lotNo: id, receivedAt: '2026-09-01' }) as Lot;
  it('ranks lots by earliest expiry within each SKU and FC', () => {
    const r = pickRanks([lot('b', 'S', 'HOU', '2027-02-01'), lot('a', 'S', 'HOU', '2026-12-01'), lot('c', 'S', 'EWR', '2026-11-01'), lot('q', 'S', 'HOU', '2026-10-20', 'QUARANTINE')]);
    expect(r.get('a')).toBe(1);
    expect(r.get('b')).toBe(2);
    expect(r.get('c')).toBe(1);
    expect(r.has('q')).toBe(false);
  });
  it('suggests markdowns and write-offs', () => {
    expect(markdownSuggestion(20)).toBe('30 % off');
    expect(markdownSuggestion(45)).toBe('15 % off');
    expect(markdownSuggestion(-1)).toBe('Write off');
    expect(markdownSuggestion(200)).toBe('—');
  });
  it('builds a valid SSCC for the pallet label', () => {
    const s = palletSscc('FBA-IN-006601');
    expect(s).toMatch(/^\d{18}$/);
    expect(s.startsWith('00860012')).toBe(true);
  });
});

describe('receiving and cycle count screens', () => {
  beforeEach(() => {
    resetHubDb(new Date());
  });

  it('counts an arriving shipment, shows the variance and posts the lots', async () => {
    nav.set('/inventory?tab=receiving');
    await renderScreen(<ReceivingPanel />);
    const user = userEvent.setup();
    const dock = await screen.findAllByTestId('dock-shipment');
    await user.click(dock.find((d) => d.textContent?.includes('FBA-IN-006601')) as HTMLElement);
    const screenEl = await screen.findByTestId('receiving-screen');
    await user.type(within(screenEl).getByLabelText(/Barcode, LPN, GS1-128/), 'LPN7Q4K2M9X{Enter}');
    expect(await within(screenEl).findByText(/Box LPN7Q4K2M9X: 36 units counted/)).toBeInTheDocument();
    const good = within(screenEl).getByLabelText('Good units');
    await user.clear(good);
    await user.type(good, '34');
    await user.type(within(screenEl).getByLabelText('Damaged units'), '1');
    expect(within(screenEl).getByTestId('variance')).toHaveTextContent(/Short \(-1\)/);
    await user.click(within(screenEl).getByRole('button', { name: 'Post receipt' }));
    const lots = await screen.findByTestId('created-lots');
    expect(lots).toHaveTextContent('MT-1007');
    expect(lots).toHaveTextContent('MT-1007-DMG');
  });

  it('starts a count, needs a reason for a variance and posts the adjustment', async () => {
    nav.set('/inventory?tab=counts');
    await renderScreen(<CountsPanel />);
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('SKU (optional)'), 'AT-SHI-200078');
    await user.click(screen.getByRole('button', { name: 'Start count' }));
    const sheet = await screen.findByTestId('count-sheet');
    const lines = within(sheet).getAllByTestId('count-line');
    for (const [i, row] of lines.entries()) {
      const input = within(row).getByLabelText(/^Counted /);
      const system = within(row).getAllByRole('cell')[3]?.textContent ?? '0';
      await user.type(input, i === 0 ? String(Number(system) - 2) : system);
    }
    await waitFor(() => expect(within(lines[0] as HTMLElement).getByTestId('count-variance')).toHaveTextContent('-2'));
    expect(within(lines[1] as HTMLElement).getByTestId('count-variance')).toHaveTextContent('0');
    await user.click(within(sheet).getByRole('button', { name: 'Save counts' }));
    await waitFor(() => expect(within(sheet).getByText(/variances/)).toBeInTheDocument());
    await user.selectOptions(within(lines[0] as HTMLElement).getByLabelText(/^Reason for/), 'LOST');
    await user.click(within(sheet).getByRole('button', { name: 'Save counts' }));
    const post = within(sheet).getByRole('button', { name: 'Post adjustments' });
    await waitFor(() => expect(post).toBeEnabled());
    await user.click(post);
    expect(await within(sheet).findByText(/-2 posted/)).toBeInTheDocument();
  });
});

describe('retail purchase order: costs of the inbound shipment', () => {
  beforeEach(() => {
    resetHubDb(new Date());
  });

  it('shows the associated costs and saves new ones on the shipment', async () => {
    nav.set('/retail?tab=pos');
    await renderScreen(<RetailView />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('PO-1P-000322'));
    const sheet = await screen.findByRole('dialog', { name: /Purchase order PO-1P-000322/ });
    const costs = await within(sheet).findByTestId('po-shipment-costs');
    expect(costs).toHaveTextContent(/Freight \$1,840\.00 \(MSC freight invoice 4412\)/);
    await user.click(within(costs).getByRole('button', { name: 'Edit costs' }));
    await user.click(within(costs).getByRole('button', { name: /Add a cost/ }));
    const amounts = within(costs).getAllByLabelText('Amount');
    await user.type(amounts[amounts.length - 1]!, '620.50');
    await user.click(within(costs).getByRole('button', { name: 'Save costs' }));
    await waitFor(() => expect(within(sheet).getByTestId('po-shipment-costs')).toHaveTextContent(/\$620\.50/));
  });
});

describe('suppliers: edit, stop doing business', () => {
  beforeEach(() => {
    resetHubDb(new Date());
  });

  it('edits a supplier with country and currency lists, then stops and resumes business with a reason', async () => {
    nav.set('/retail?tab=suppliers');
    await renderScreen(<RetailView />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('Accra Spice Co.'));
    const sheet = await screen.findByRole('dialog', { name: /Accra Spice Co\./ });
    expect(within(sheet).getByLabelText('Country')).toBeDisabled();
    const currency = within(sheet).getByLabelText('Currency we pay in');
    expect(within(currency).getAllByRole('option').some((o) => o.textContent?.startsWith('GHS'))).toBe(true);
    const name = within(sheet).getByLabelText('Supplier name');
    await user.clear(name);
    await user.type(name, 'Accra Spice Company');
    await user.click(within(sheet).getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('dialog', { name: /Accra Spice Company/ })).toBeInTheDocument();
    const dialog = screen.getByRole('dialog', { name: /Accra Spice Company/ });
    await user.click(within(dialog).getByRole('button', { name: 'Stop doing business' }));
    expect(within(dialog).getByRole('button', { name: 'Stop doing business' })).toBeDisabled();
    await user.type(within(dialog).getByLabelText(/Why\?/), 'Quality problems');
    await user.click(within(dialog).getByRole('button', { name: 'Stop doing business' }));
    expect(await within(screen.getByRole('dialog')).findByRole('button', { name: 'Resume business' })).toBeInTheDocument();
    expect(within(screen.getByRole('dialog')).getByText('Quality problems')).toBeInTheDocument();
  });
});

describe('retail goods receipt rows (S281)', () => {
  const grn = GoodsReceiptSchema.parse({
    id: 'g1', number: 'GRN-1', poId: 'p1', shipmentId: 's1', nodeId: 'HOU-FC', status: 'OPEN',
    lines: [
      { lineNo: 1, poLineNo: 1, itemId: 'i1', sku: 'AT-COC-200100', expectedQty: 96, asnLotNo: 'CC2610A', lotNo: null, mfgDate: null, expDate: null, qtyGood: 0, qtyDamaged: 0 },
      { lineNo: 2, poLineNo: 2, itemId: 'i2', sku: 'AT-JOL-200061', expectedQty: 24, lotNo: 'J1', mfgDate: '2026-01-01', expDate: '2027-06-30', qtyGood: 24, qtyDamaged: 0 }
    ]
  });
  const asn = [
    { lineNo: 1, poLineNo: 1, itemId: 'i1', sku: 'AT-COC-200100', qty: 96, lotNo: 'CC2610A', mfgDate: '2026-09-20', expDate: '2027-09-20' },
    { lineNo: 2, poLineNo: 2, itemId: 'i2', sku: 'AT-JOL-200061', qty: 24, lotNo: 'J0', mfgDate: '2025-12-01', expDate: '2027-01-01' }
  ];
  it('starts an uncounted line from the batch announced on the ASN', () => {
    expect(rowsOf(grn, asn)[0]).toMatchObject({ lotNo: 'CC2610A', mfgDate: '2026-09-20', expDate: '2027-09-20', asnLot: 'CC2610A', good: '' });
  });
  it('keeps what the receiver already counted', () => {
    expect(rowsOf(grn, asn)[1]).toMatchObject({ lotNo: 'J1', mfgDate: '2026-01-01', expDate: '2027-06-30', good: '24' });
    expect(rowsOf(grn)[0]).toMatchObject({ lotNo: 'CC2610A', mfgDate: '', expDate: '' });
  });
});
