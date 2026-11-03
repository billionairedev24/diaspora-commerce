import { File as NodeFile } from 'node:buffer';

import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { columnName } from '@/components/size-charts/import-model';
import { SizeChartImportView } from '@/components/size-charts/SizeChartImportView';

import { mockApi } from './mock-api';
import { renderScreen } from './render';

/* Uploads go through the Fetch API's Request, which is Node's (jsdom has none): use Node's FormData and File so the
   multipart body reaches the mock BFF, as it does in a browser. */
const NodeFormData = (await new Response('a=1', { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }).formData()).constructor as typeof FormData;
globalThis.FormData = NodeFormData;
globalThis.File = NodeFile as unknown as typeof File;

beforeEach(() => {
  resetSellerDb(new Date('2026-10-10T15:00:00Z'));
});

const HEADER = 'Size chart name *,Unit,Size *,Bust *,Waist *,Size fit,How to measure';
const GOOD = [HEADER, 'Wedding guest dresses,cm,S,84-87,66-69,Runs small,', 'Wedding guest dresses,,M,88-92,70-74,,'].join('\n');
// "Adire dresses" is a chart the organisation already has: a different table updates it.
const UPDATE = ['Adire dresses,cm,S,85-88,66-69,,', 'Adire dresses,,M,88-92,70-74,,'].join('\n');

const csv = (text: string, name = 'charts.csv') => new File([text], name, { type: 'text/csv' });
const choose = async (file: File) => fireEvent.change(await screen.findByTestId('chart-file'), { target: { files: [file] } });

describe('size chart import', () => {
  it('checks the file without saving, reads it row by row, then imports the charts', async () => {
    await renderScreen(<SizeChartImportView />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Import size charts' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Check the file' })).toBeDisabled();
    await choose(csv(`${HEADER}\n${GOOD.split('\n').slice(1).join('\n')}\n${UPDATE}`));
    fireEvent.click(screen.getByRole('button', { name: 'Check the file' }));

    const report = within(await screen.findByTestId('chart-report'));
    expect(await report.findByRole('heading', { name: 'What charts.csv would do' })).toBeInTheDocument();
    expect(report.getByTestId('sum-created')).toHaveTextContent('1');
    expect(report.getByTestId('sum-updated')).toHaveTextContent('1');
    expect(report.getByTestId('sum-failed')).toHaveTextContent('0');
    const results = report.getAllByTestId('chart-result').map((r) => r.textContent);
    expect(results[0]).toContain('Wedding guest dresses');
    expect(results.some((r) => r?.includes('Adire dresses') && r.includes('Update'))).toBe(true);
    // A check saves nothing.
    expect((await mockApi().listSizeCharts({ q: 'Wedding' })).items).toHaveLength(0);
    expect(report.getByTestId('sum-rowsWithErrors')).toHaveTextContent('0');

    fireEvent.click(report.getByRole('button', { name: 'Import 2 charts' }));
    expect(await screen.findByText('Imported 2 size charts')).toBeInTheDocument();
    expect(await report.findByRole('heading', { name: 'Imported charts.csv' })).toBeInTheDocument();
    expect(report.getByRole('link', { name: 'View my size charts' })).toHaveAttribute('href', '/listings/size-charts');
    const wedding = (await mockApi().listSizeCharts({ q: 'Wedding' })).items[0];
    expect(wedding).toMatchObject({ name: 'Wedding guest dresses', unitSystem: 'CM', fitNote: 'RUNS_SMALL' });
    expect(wedding?.rows.map((r) => r.size)).toEqual(['S', 'M']);
    // The same file again changes nothing.
    const again = await mockApi().importSizeCharts(csv(`${GOOD}\n${UPDATE}`), { dryRun: true });
    expect(again).toMatchObject({ created: 0, updated: 0, unchanged: 2 });
  });

  it('names each bad cell, saves a chart whole or not at all, and still imports the others', async () => {
    await renderScreen(<SizeChartImportView />);
    const bad = [HEADER, 'Good chart,cm,S,84-87,66-69,,', 'Good chart,,M,88-92,70-74,,', 'Broken chart,cm,S,abc,66-69,,', 'Broken chart,,M,95-90,70-74,Roomy,'].join('\n');
    await choose(csv(bad, 'broken.csv'));
    fireEvent.click(screen.getByRole('button', { name: 'Check the file' }));
    const report = within(await screen.findByTestId('chart-report'));
    await report.findByRole('heading', { name: 'What broken.csv would do' });
    expect(report.getByTestId('sum-failed')).toHaveTextContent('1');
    expect(report.getByTestId('sum-rowsWithErrors')).toHaveTextContent('2');
    expect(report.getByText('1 chart cannot be saved.')).toBeInTheDocument();
    expect(report.getByText(/You can import the other chart now/)).toBeInTheDocument();

    // Only the rows with problems, each naming its column.
    const rows = report.getAllByTestId('chart-row');
    expect(rows).toHaveLength(2);
    expect(rows.every((r) => r.getAttribute('data-status') === 'ERROR')).toBe(true);
    expect(rows[0]).toHaveTextContent('Bust: "abc" is not a number or a range like 86-90');
    expect(rows[1]).toHaveTextContent('Bust: The smallest value (95) is above the largest (90)');
    expect(rows[1]).toHaveTextContent('Fit note: Choose one of Runs small, True to size, Runs large');

    fireEvent.click(report.getByLabelText(/Only rows with problems/));
    expect(report.getAllByTestId('chart-row')).toHaveLength(4);

    fireEvent.click(report.getByRole('button', { name: 'Import 1 chart' }));
    expect(await screen.findByText('Imported 1 size chart')).toBeInTheDocument();
    expect((await mockApi().listSizeCharts({ q: 'Good chart' })).items).toHaveLength(1);
    expect((await mockApi().listSizeCharts({ q: 'Broken chart' })).items).toHaveLength(0);
  });

  it('refuses a file it cannot read before sending it, and explains a header that is missing', async () => {
    await renderScreen(<SizeChartImportView />);
    await choose(new File(['x'], 'charts.xls'));
    expect(await screen.findByTestId('chart-file-error')).toHaveTextContent('save the file as .xlsx');
    expect(screen.getByRole('button', { name: 'Check the file' })).toBeDisabled();

    await choose(csv('Name,Waist\nA,70', 'wrong.csv'));
    fireEvent.click(screen.getByRole('button', { name: 'Check the file' }));
    await waitFor(() => expect(screen.getByTestId('chart-file-error')).toHaveTextContent(/Missing required column/));
    expect(screen.queryByTestId('chart-report')).not.toBeInTheDocument();
  });

  it('says there is nothing to import when every chart is already saved like this', async () => {
    await renderScreen(<SizeChartImportView />);
    await mockApi().importSizeCharts(csv(GOOD));
    await choose(csv(GOOD, 'same.csv'));
    fireEvent.click(screen.getByRole('button', { name: 'Check the file' }));
    const report = within(await screen.findByTestId('chart-report'));
    expect(await report.findByText('Nothing to import.')).toBeInTheDocument();
    expect(report.getByRole('button', { name: 'Import 0 charts' })).toBeDisabled();
  });
});

describe('size chart import: reading the report', () => {
  it('names a column the way the seller sees it', () => {
    expect(columnName('m:foot_length')).toBe('Foot length');
    expect(columnName('chart_name')).toBe('Chart name');
    expect(columnName(null)).toBeNull();
    expect(columnName('m:')).toBeNull();
  });
});
