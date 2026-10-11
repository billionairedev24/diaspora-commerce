import { ToastProvider } from '@afrotransact/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { DataTable, type DataTableProps, pageNumbers, type TableExportRequest, useTableState } from '../src';

interface Order {
  id: string;
  number: string;
  customer: string;
  totalMinor: number;
  status: string;
}

const ORDERS: Order[] = [
  { id: 'o1', number: 'AT-9', customer: 'Ada Obi', totalMinor: 123456, status: 'PAID' },
  { id: 'o2', number: 'AT-10', customer: 'Bola Ade', totalMinor: 500, status: 'AWAITING_PICKUP' },
  { id: 'o3', number: 'AT-100', customer: 'Chidi Eze', totalMinor: 9900, status: 'CANCELLED' },
  { id: 'o4', number: 'AT-11', customer: 'Dayo Bello', totalMinor: 2500, status: 'PAID' },
  { id: 'o5', number: 'AT-12', customer: 'Efe Ojo', totalMinor: 7000, status: 'PAID' }
];

const columns: ColumnDef<Order>[] = [
  { id: 'number', accessorFn: (o) => o.number, header: 'Order', meta: { kind: 'mono' } },
  { id: 'customer', accessorFn: (o) => o.customer, header: 'Customer', meta: { kind: 'strong' } },
  { id: 'totalMinor', accessorFn: (o) => o.totalMinor, header: 'Total', meta: { kind: 'money', align: 'end' } },
  { id: 'status', accessorFn: (o) => o.status, header: 'Status', meta: { kind: 'status' } }
];

function Harness(props: Partial<DataTableProps<Order>> & { debounceMs?: number }) {
  const state = useTableState({ searchParams: new URLSearchParams(window.location.search), debounceMs: props.debounceMs ?? 0 });
  return (
    <ToastProvider>
      <DataTable<Order> id="test.orders" label="Orders" columns={columns} data={ORDERS} getRowId={(o) => o.id} rowLabel={(o) => o.number} state={state} mode="client" {...props} />
    </ToastProvider>
  );
}

const rowIds = () => screen.getAllByRole('row').map((r) => r.getAttribute('data-row-id')).filter(Boolean);
const box = (name: string) => screen.getByRole('checkbox', { name });

beforeEach(() => {
  window.localStorage.clear();
  window.history.replaceState(null, '', '/orders');
});

describe('DataTable', () => {
  it('selects with the header checkbox (indeterminate when some) and shift-clicks a range', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const all = box('Select all rows on this page') as HTMLInputElement;
    await user.click(box('Select AT-9'));
    expect(all.indeterminate).toBe(true);
    expect(screen.getByRole('region', { name: 'Selection' })).toHaveTextContent('1 selected');
    // Shift-click AT-11 (4th row): rows 1–4 are selected.
    await user.keyboard('{Shift>}');
    await user.click(box('Select AT-11'));
    await user.keyboard('{/Shift}');
    expect(['Select AT-9', 'Select AT-10', 'Select AT-100', 'Select AT-11'].every((n) => (box(n) as HTMLInputElement).checked)).toBe(true);
    expect((box('Select AT-12') as HTMLInputElement).checked).toBe(false);
    await user.click(all);
    expect(all.checked).toBe(true);
    expect(all.indeterminate).toBe(false);
    expect(screen.getByRole('region', { name: 'Selection' })).toHaveTextContent('5 selected');
    await user.click(screen.getByRole('button', { name: /Clear selection/ }));
    expect(screen.queryByRole('region', { name: 'Selection' })).toBeNull();
  });

  it('phone cards: every cell carries its column label and one Sort select stands in for the headers', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    // The stacked-card layout (container query) prints each cell's label from data-label.
    const firstRow = screen.getAllByRole('row').find((r) => r.getAttribute('data-row-id') === 'o1');
    expect(within(firstRow as HTMLElement).getByText('Ada Obi').closest('td')).toHaveAttribute('data-label', 'Customer');
    const sort = screen.getByRole('combobox', { name: 'Sort orders by' });
    await user.selectOptions(sort, 'totalMinor:desc');
    await waitFor(() => expect(rowIds()).toEqual(['o1', 'o3', 'o5', 'o4', 'o2']));
    expect(screen.getByRole('columnheader', { name: /^Total/ })).toHaveAttribute('aria-sort', 'descending');
    await user.selectOptions(sort, '');
    expect(screen.getByRole('columnheader', { name: /^Total/ })).toHaveAttribute('aria-sort', 'none');
  });

  it('never opens the row when the checkbox is clicked; a row click does', async () => {
    const user = userEvent.setup();
    const onRowClick = vi.fn();
    render(<Harness onRowClick={onRowClick} />);
    await user.click(box('Select AT-10'));
    expect(onRowClick).not.toHaveBeenCalled();
    await user.click(screen.getByText('Bola Ade'));
    expect(onRowClick).toHaveBeenCalledWith(ORDERS[1]);
  });

  it('has one search over every column (amounts, statuses) and keeps it in the URL', async () => {
    const user = userEvent.setup();
    render(<Harness debounceMs={20} />);
    const search = screen.getByRole('searchbox', { name: 'Search orders' });
    await user.type(search, '1,234.56');
    await waitFor(() => expect(rowIds()).toEqual(['o1']));
    expect(new URL(window.location.href).searchParams.get('q')).toBe('1,234.56');
    await user.clear(search);
    await user.type(search, 'awaiting pickup');
    await waitFor(() => expect(rowIds()).toEqual(['o2']));
    expect(window.location.search).toContain('q=awaiting+pickup');
    await user.clear(search);
    await user.type(search, 'nobody');
    await waitFor(() => expect(screen.getByText('Nothing matches “nobody”.')).toBeInTheDocument());
  });

  it('reads the search and sort from the URL', () => {
    window.history.replaceState(null, '', '/orders?q=paid&sort=totalMinor,desc');
    render(<Harness />);
    expect(screen.getByRole('searchbox')).toHaveValue('paid');
    expect(rowIds()).toEqual(['o1', 'o5', 'o4']);
  });

  it('sorts by a header (ascending, descending, none) with aria-sort, naturally and by amount', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const header = () => screen.getByRole('columnheader', { name: /Order/ });
    expect(header()).toHaveAttribute('aria-sort', 'none');
    await user.click(within(header()).getByRole('button'));
    expect(header()).toHaveAttribute('aria-sort', 'ascending');
    expect(rowIds()).toEqual(['o1', 'o2', 'o4', 'o5', 'o3']);
    expect(window.location.search).toContain('sort=number%2Casc');
    await user.click(within(header()).getByRole('button'));
    expect(header()).toHaveAttribute('aria-sort', 'descending');
    expect(rowIds()).toEqual(['o3', 'o5', 'o4', 'o2', 'o1']);
    await user.click(within(header()).getByRole('button'));
    expect(header()).toHaveAttribute('aria-sort', 'none');
    const total = screen.getByRole('columnheader', { name: /Total/ });
    await user.click(within(total).getByRole('button'));
    expect(rowIds()).toEqual(['o2', 'o4', 'o5', 'o3', 'o1']);
  });

  it('hides columns from the Columns menu and remembers it per table', async () => {
    const user = userEvent.setup();
    const { unmount } = render(<Harness />);
    await user.click(screen.getByRole('button', { name: /Columns/ }));
    await user.click(screen.getByRole('checkbox', { name: 'Customer' }));
    expect(screen.queryByRole('columnheader', { name: /Customer/ })).toBeNull();
    expect(JSON.parse(window.localStorage.getItem('at.table.test.orders') ?? '{}').visibility).toEqual({ customer: false });
    unmount();
    render(<Harness />);
    await waitFor(() => expect(screen.queryByRole('columnheader', { name: /Customer/ })).toBeNull());
    expect(screen.getByRole('columnheader', { name: /Total/ })).toBeInTheDocument();
  });

  it('survives unavailable storage', () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    render(<Harness />);
    expect(rowIds()).toHaveLength(5);
    spy.mockRestore();
  });

  it('exports the visible columns of the selected rows (money in currency units)', async () => {
    const user = userEvent.setup();
    const onExport = vi.fn(async (_req: TableExportRequest) => ({ id: 'e1', status: 'READY' as const, rows: 2, fileName: 'orders.xlsx', downloadUrl: '/f', error: null }));
    render(<Harness onExport={onExport} />);
    await user.click(box('Select AT-9'));
    await user.click(box('Select AT-10'));
    await user.click(screen.getByRole('button', { name: /Export 2 selected/ }));
    await user.click(await screen.findByRole('menuitem', { name: /Excel/ }));
    await waitFor(() => expect(onExport).toHaveBeenCalled());
    const req = onExport.mock.calls[0]?.[0];
    expect(req?.format).toBe('xlsx');
    expect(req?.ids).toEqual(['o1', 'o2']);
    expect(req?.columns).toEqual(['number', 'customer', 'totalMinor', 'status']);
    expect(req?.dataset.rows).toEqual([
      ['AT-9', 'Ada Obi', 1234.56, 'PAID'],
      ['AT-10', 'Bola Ade', 5, 'AWAITING_PICKUP']
    ]);
    expect(req?.dataset.scope).toContain('2 selected rows');
  });

  it('pages client-side with a page size selector', async () => {
    const user = userEvent.setup();
    render(<Harness pageSizes={[2, 25]} />);
    act(() => {
      fireEvent.change(screen.getByRole('combobox', { name: /Rows per page/ }), { target: { value: '2' } });
    });
    expect(rowIds()).toEqual(['o1', 'o2']);
    expect(screen.getByText('Showing 1–2 of 5')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Next/ }));
    expect(rowIds()).toEqual(['o3', 'o4']);
    expect(window.location.search).toContain('page=2');
    // Numbered pages: 3 pages of 2, the current one marked.
    const pages = screen.getByRole('navigation', { name: 'Orders pages' });
    expect(within(pages).getByRole('button', { name: 'Page 2' })).toHaveAttribute('aria-current', 'page');
    await user.click(within(pages).getByRole('button', { name: 'Page 3' }));
    expect(rowIds()).toEqual(['o5']);
    expect(within(pages).queryByRole('button', { name: 'Page 4' })).toBeNull();
  });

  it('remembers rows per page per table', async () => {
    const { unmount } = render(<Harness pageSizes={[2, 25]} />);
    act(() => {
      fireEvent.change(screen.getByRole('combobox', { name: /Rows per page/ }), { target: { value: '2' } });
    });
    expect(JSON.parse(window.localStorage.getItem('at.table.test.orders') ?? '{}').pageSize).toBe(2);
    unmount();
    window.history.replaceState(null, '', '/orders');
    render(<Harness pageSizes={[2, 25]} />);
    await waitFor(() => expect(rowIds()).toEqual(['o1', 'o2']));
    expect(screen.getByRole('combobox', { name: /Rows per page/ })).toHaveValue('2');
  });

  it('numbers pages with gaps', () => {
    expect(pageNumbers(0, 5)).toEqual([0, 1, 2, 3, 4]);
    expect(pageNumbers(9, 20)).toEqual([0, null, 8, 9, 10, null, 19]);
    expect(pageNumbers(0, 20)).toEqual([0, 1, 2, 3, null, 19]);
    expect(pageNumbers(19, 20)).toEqual([0, null, 16, 17, 18, 19]);
  });
});

describe('default density (My account › Preferences)', () => {
  it('uses the saved default until the table has its own choice', async () => {
    const { writeDefaultDensity, readDefaultDensity } = await import('../src');
    writeDefaultDensity('compact');
    expect(readDefaultDensity()).toBe('compact');
    render(<Harness />);
    await waitFor(() => expect(screen.getAllByRole('cell')[0]?.className).toContain('py-1.5'));
  });
});
