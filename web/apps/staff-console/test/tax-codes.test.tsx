import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { TaxCodeMapPanel } from '@/components/tax/TaxCodeMapPanel';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const NOW = new Date('2026-11-09T12:00:00Z');
const CODE = 'AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS';

describe('Provider codes', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles('platform.super_admin');
    nav.set('/fees?tab=tax-codes');
  });

  it('shows the provider in use with a completeness meter for the outcomes still needing a verified code', async () => {
    await renderScreen(<TaxCodeMapPanel />);
    const meter = await screen.findByRole('progressbar', { name: 'Stripe Tax' });
    expect(meter).toHaveAttribute('aria-valuemax', '104');
    expect(Number(meter.getAttribute('aria-valuenow'))).toBeGreaterThan(0);
    expect(meter).toHaveAttribute('aria-valuetext', expect.stringMatching(/\d+ of 104 outcomes verified/));
    expect(screen.getByText(/have codes that are not verified|has a code that is not verified/)).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Stripe Tax codes' });
    expect(within(table).getAllByText(/^(Verified|Code set, not verified)$/).length).toBeGreaterThan(0);
  });

  it('switches to Avalara, where nothing is mapped yet', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxCodeMapPanel />);
    await screen.findByRole('table', { name: 'Stripe Tax codes' });
    await user.click(screen.getByRole('button', { name: 'Avalara' }));
    const meter = await screen.findByRole('progressbar', { name: 'Avalara' });
    expect(meter).toHaveAttribute('aria-valuenow', '0');
    expect(await screen.findByText(/104 outcomes have no code yet/)).toBeInTheDocument();
    expect(nav.search).toContain('codes.provider=AVALARA');
  });

  it('edits one code with a reason, checks its format, and verifies it', async () => {
    const user = userEvent.setup();
    nav.set('/fees?tab=tax-codes&codes.provider=AVALARA');
    await renderScreen(<TaxCodeMapPanel />);
    const table = await screen.findByRole('table', { name: 'Avalara codes' });
    await user.type(screen.getByRole('searchbox'), 'PLAIN_MULTI_SERVING');
    const row = (await within(table).findByText(CODE)).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: /Actions for/ }));
    await user.click(await screen.findByRole('menuitem', { name: 'Edit code…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Edit the Avalara code' });
    const save = within(dialog).getByRole('button', { name: 'Save code' });
    expect(save).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Provider code'), 'not a code!');
    await user.type(within(dialog).getByLabelText('Reason'), 'From the Avalara tax code list');
    expect(save).toBeEnabled();
    await user.click(save);
    expect((await within(dialog).findAllByText(/An Avalara tax code is letters and digits/)).length).toBeGreaterThan(0);
    await user.clear(within(dialog).getByLabelText('Provider code'));
    await user.type(within(dialog).getByLabelText('Provider code'), 'PF050099');
    await user.click(within(dialog).getByRole('checkbox', { name: /Verified/ }));
    await user.click(save);
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Edit the Avalara code' })).not.toBeInTheDocument());
    await waitFor(() => expect(within(table).getByText('PF050099')).toBeInTheDocument());
    expect(within(table).getByText('Verified')).toBeInTheDocument();
  });

  it('offers no editing without tax:classify-admin', async () => {
    const user = userEvent.setup();
    asRoles('platform.auditor');
    await renderScreen(<TaxCodeMapPanel />);
    const table = await screen.findByRole('table', { name: 'Stripe Tax codes' });
    expect(screen.queryByRole('button', { name: 'Import CSV…' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Export CSV' })).toBeInTheDocument();
    await user.click((await within(table).findAllByRole('button', { name: /Actions for/ }))[0]!);
    expect(await screen.findByRole('menuitem', { name: /Edit code/ })).toHaveAttribute('aria-disabled', 'true');
  });

  it('exports the map as a CSV file', async () => {
    const user = userEvent.setup();
    const created: Blob[] = [];
    const url = vi.spyOn(URL, 'createObjectURL').mockImplementation((b) => (created.push(b as Blob), 'blob:test'));
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    await renderScreen(<TaxCodeMapPanel />);
    await user.click(await screen.findByRole('button', { name: 'Export CSV' }));
    expect(await screen.findByText(/Exported 105 outcomes to tax-code-map-stripe.csv/)).toBeInTheDocument();
    expect(url).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    const text = await new Promise<string>((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.readAsText(created[0]!);
    });
    expect(text).toMatch(/^internal_tax_code,category,outcome,provider,provider_code,verified/);
    vi.restoreAllMocks();
  });

  it('previews an imported CSV before saving, refuses a file with a bad row, and applies a good one with a reason', async () => {
    const user = userEvent.setup();
    nav.set('/fees?tab=tax-codes&codes.provider=AVALARA');
    await renderScreen(<TaxCodeMapPanel />);
    await user.click(await screen.findByRole('button', { name: 'Import CSV…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Import Avalara codes from CSV' });
    const choose = async (text: string) => {
      const input = within(dialog).getByLabelText('CSV file') as HTMLInputElement;
      const file = new File([text], 'codes.csv', { type: 'text/csv' });
      Object.defineProperty(file, 'text', { value: () => Promise.resolve(text) });
      fireEvent.change(input, { target: { files: [file] } });
    };
    const submit = within(dialog).getByRole('button', { name: 'Import' });
    expect(submit).toBeDisabled();
    await choose(`internal_tax_code,provider_code,verified\n${CODE},PF050099,false\nAT.NO.SUCH_CODE,PF050099,false\n`);
    expect(await within(dialog).findByText(/1 row can’t be used/)).toBeInTheDocument();
    expect(within(dialog).getByText(/Line 3: No such internal tax code/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Reason'), 'Import of the checked list');
    expect(within(dialog).getByRole('button', { name: /Import/ })).toBeDisabled();
    await choose(`internal_tax_code,provider_code,verified\n${CODE},PF050099,true\n`);
    expect(await within(dialog).findByText(/1 rows? read: 1 would change, 0 already match/)).toBeInTheDocument();
    expect(within(dialog).getByText(new RegExp(`${CODE}: no code → PF050099 \\(verified\\)`))).toBeInTheDocument();
    const apply = await within(dialog).findByRole('button', { name: 'Import 1 change' });
    await waitFor(() => expect(apply).toBeEnabled());
    await user.click(apply);
    expect(await screen.findByText('1 Avalara code imported.')).toBeInTheDocument();
  });
});
