import { resetSellerDb,SIZE_CHART_IDS } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { SizeChartsView } from '@/components/size-charts/SizeChartsView';

import { mockApi } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

beforeEach(() => {
  resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  nav.pathname = '/listings/size-charts';
});

const editor = () => screen.findByTestId('size-chart-editor');

describe('My size charts', () => {
  it('lists the organisation’s charts with what they hold, and hides retired ones until asked', async () => {
    await renderScreen(<SizeChartsView />);
    expect(await screen.findByRole('heading', { level: 1, name: 'My size charts' })).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'My size charts' });
    await within(table).findByText('Adire dresses');
    expect(within(table).getByText('Kaftans and shirts')).toBeInTheDocument();
    expect(within(table).getByText('Leather sandals')).toBeInTheDocument();
    const row = within(table).getByText('Adire dresses').closest('tr');
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).getByText('XS, S, M, L, XL, UK 12, Free size')).toBeInTheDocument();
    expect(within(row as HTMLElement).getByText('Bust, Waist, Hip')).toBeInTheDocument();

    await mockApi().retireSizeChart(SIZE_CHART_IDS.shoes);
    fireEvent.click(screen.getByLabelText('Show retired charts'));
    expect(await within(table).findByText('Retired')).toBeInTheDocument();
  });

  it('writes a new chart from what the category suggests, with the table the shopper reads beside it', async () => {
    await renderScreen(<SizeChartsView />);
    fireEvent.click(await screen.findByRole('button', { name: 'New size chart' }));
    const ed = within(await editor());
    fireEvent.change(ed.getByLabelText('Made for'), { target: { value: 'pt-dresses' } });
    await waitFor(() => expect(ed.getByLabelText('Measurement 1')).toHaveValue('Bust'));
    expect(ed.getByLabelText('Measurement 3')).toHaveValue('Hip');
    ['M', 'L', 'XL', '2XL'].forEach((size) => fireEvent.click(ed.getByRole('button', { name: `Remove size ${size}` })));

    // Saving an unfinished chart says which cells are open and does not call the API.
    fireEvent.click(ed.getByRole('button', { name: 'Create chart' }));
    expect(await ed.findAllByText(/^Enter the bust of size/)).not.toHaveLength(0);
    expect(ed.getByText(/things to fix/)).toBeInTheDocument();

    fireEvent.click(ed.getByText('Paste rows from a spreadsheet'));
    fireEvent.change(ed.getByLabelText('Paste rows from a spreadsheet'), { target: { value: 'Size\tBust\tWaist\tHip\nXS\t80-83\t62-65\t88-91\nS\t84-87\t66-69\t92-95' } });
    fireEvent.click(ed.getByRole('button', { name: 'Add to chart' }));
    expect(await ed.findByText('2 rows read, 0 skipped.')).toBeInTheDocument();
    fireEvent.change(ed.getByLabelText('Chart name'), { target: { value: 'Test dresses' } });

    const preview = within(ed.getByTestId('chart-preview'));
    expect(preview.getByRole('row', { name: /^XS 80–83 62–65 88–91/ })).toBeInTheDocument();
    fireEvent.click(preview.getByRole('button', { name: 'in' }));
    expect(preview.getByRole('row', { name: /^XS 31.5–32.7/ })).toBeInTheDocument();

    fireEvent.click(ed.getByRole('button', { name: 'Create chart' }));
    expect(await screen.findByText('Size chart “Test dresses” created')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId('size-chart-editor')).not.toBeInTheDocument());
    const made = (await mockApi().listSizeCharts({ q: 'Test dresses' })).items[0];
    expect(made).toMatchObject({ name: 'Test dresses', unitSystem: 'CM', productTypeCode: 'dress' });
    expect(made?.rows.map((r) => r.size)).toEqual(['XS', 'S']);
    expect(await screen.findByText('Test dresses')).toBeInTheDocument();
  });

  it('shows the server’s answer on the field it names (a name already used)', async () => {
    await renderScreen(<SizeChartsView />);
    fireEvent.click(await screen.findByRole('button', { name: 'New size chart' }));
    const ed = within(await editor());
    fireEvent.change(ed.getByLabelText('Chart name'), { target: { value: 'adire dresses' } });
    fireEvent.change(ed.getByLabelText('Size of row 1'), { target: { value: 'M' } });
    fireEvent.change(ed.getByLabelText('Measurement 1'), { target: { value: 'Bust' } });
    fireEvent.change(ed.getByLabelText('Bust of size M'), { target: { value: '88-92' } });
    fireEvent.click(ed.getByRole('button', { name: 'Create chart' }));
    expect(await ed.findByText('You already have a size chart called adire dresses')).toBeInTheDocument();
    expect(ed.getByLabelText('Chart name')).toHaveAttribute('aria-invalid', 'true');
  });

  it('edits a chart and saves it, and asks before closing with unsaved changes', async () => {
    await renderScreen(<SizeChartsView />);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit Kaftans and shirts' }));
    const ed = within(await editor());
    expect(ed.getByText('1 products use this chart.')).toBeInTheDocument();
    expect(ed.getByRole('button', { name: 'Save changes' })).toBeDisabled();
    expect(ed.getByLabelText('Chest of size S')).toHaveValue('35–37');

    fireEvent.change(ed.getByLabelText('Chest of size S'), { target: { value: '36-38' } });
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(await screen.findByRole('heading', { name: 'Discard your changes?' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Discard your changes?' })).not.toBeInTheDocument());
    expect(ed.getByLabelText('Chest of size S')).toHaveValue('36-38');

    fireEvent.click(ed.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Size chart “Kaftans and shirts” saved')).toBeInTheDocument();
    const saved = await mockApi().getSizeChart(SIZE_CHART_IDS.mensTops);
    expect(saved.rows[0]?.values.chest).toEqual({ min: 36, max: 38 });
    expect(saved.version).toBe(1);
  });

  it('leaves without asking when nothing changed, and discards on request', async () => {
    await renderScreen(<SizeChartsView />);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit Leather sandals' }));
    await editor();
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByTestId('size-chart-editor')).not.toBeInTheDocument());

    fireEvent.click(await screen.findByRole('button', { name: 'Edit Leather sandals' }));
    const ed = within(await editor());
    fireEvent.change(ed.getByLabelText('Chart name'), { target: { value: 'Sandals' } });
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Discard changes' }));
    await waitFor(() => expect(screen.queryByTestId('size-chart-editor')).not.toBeInTheDocument());
    expect((await mockApi().getSizeChart(SIZE_CHART_IDS.shoes)).name).toBe('Leather sandals');
  });

  it('keeps the row of a size that products still sell, and offers to load the latest version after a conflict', async () => {
    await renderScreen(<SizeChartsView />);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit Kaftans and shirts' }));
    const ed = within(await editor());
    // Someone else saves first.
    const fresh = await mockApi().getSizeChart(SIZE_CHART_IDS.mensTops);
    await mockApi().updateSizeChart(SIZE_CHART_IDS.mensTops, { name: fresh.name, unitSystem: fresh.unitSystem, productTypeCode: fresh.productTypeCode, columns: fresh.columns.map((c) => ({ key: c.key, label: c.label })),
      rows: fresh.rows.map((r) => ({ size: r.size, values: Object.fromEntries(Object.entries(r.values).map(([k, v]) => [k, { min: v.min, max: v.max }])) })), version: fresh.version });
    fireEvent.change(ed.getByLabelText('Chart name'), { target: { value: 'Kaftans' } });
    fireEvent.click(ed.getByRole('button', { name: 'Save changes' }));
    expect(await ed.findByText('This chart changed.')).toBeInTheDocument();
    fireEvent.click(ed.getByRole('button', { name: 'Load latest version' }));
    await waitFor(() => expect(screen.queryByText('This chart changed.')).not.toBeInTheDocument());
  });

  it('duplicates a chart and retires one after a confirmation that says how many products use it', async () => {
    await renderScreen(<SizeChartsView />);
    fireEvent.click(await screen.findByRole('button', { name: 'Duplicate Adire dresses' }));
    expect(await screen.findByText('Created “Adire dresses (copy)”')).toBeInTheDocument();
    expect(await screen.findByText('Adire dresses (copy)')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Retire Kaftans and shirts' }));
    expect(await screen.findByRole('heading', { name: 'Retire “Kaftans and shirts”?' })).toBeInTheDocument();
    expect(screen.getByText(/1 products use it\. They keep showing it/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retire chart' }));
    expect(await screen.findByText('“Kaftans and shirts” retired')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('Kaftans and shirts')).not.toBeInTheDocument());
  });
});
