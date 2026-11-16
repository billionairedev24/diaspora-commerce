import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { DeliveryView } from '@/components/delivery/DeliveryView';

import { asRoles } from './mock-api';
import { renderScreen } from './render';

/* The Delivery module: AfroTransact staff run AfroTransact-delivered orders end to end without the driver app, against the staff mocks. */
/** The queue row of an order (the queue sorts by the promised date, so rows are found by order number). */
const rowOf = async (order: string) => {
  const rows = await screen.findAllByTestId('delivery-row');
  const r = rows.find((x) => x.textContent?.includes(order));
  if (!r) throw new Error(`no row for ${order}`);
  return r;
};

describe('Delivery module', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    window.history.replaceState(null, '', '/delivery');
  });

  it('lists the orders AfroTransact delivers with names and cities, and never talks about a driver', async () => {
    asRoles('platform.dispatcher');
    await renderScreen(<DeliveryView />);
    const rows = await screen.findAllByTestId('delivery-row');
    expect(rows).toHaveLength(5);
    expect(rows[0]).toHaveTextContent(/AT-2026-00\d{4}/);
    // Names and cities, not ids.
    expect(screen.getByText('Ada Obi')).toBeInTheDocument();
    expect(screen.getAllByText('Houston').length).toBeGreaterThan(0);
    expect(screen.getByText("Mama Tee's Pantry")).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/shp-dl|stf-/);
    expect(document.body.textContent?.toLowerCase()).not.toContain('driver');
    expect(screen.getByRole('tab', { name: /In progress \(5\)/ })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Delivered \(1\)/ })).toBeInTheDocument();
  }, 30_000);

  it('filters the queue on the server: assignee, fulfilment and customer city', async () => {
    asRoles('platform.dispatcher');
    const user = userEvent.setup();
    await renderScreen(<DeliveryView />);
    await screen.findAllByTestId('delivery-row');
    await user.selectOptions(screen.getByLabelText('Assigned to'), 'unassigned');
    await waitFor(() => expect(screen.getAllByTestId('delivery-row')).toHaveLength(2));
    await user.selectOptions(screen.getByLabelText('Fulfilment'), 'AFROTRANSACT_DELIVERY');
    await waitFor(() => expect(screen.getAllByTestId('delivery-row')).toHaveLength(1));
    expect(screen.getByText('Ngozi Eze')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(screen.getAllByTestId('delivery-row')).toHaveLength(5));
    await user.type(screen.getByLabelText('Customer city'), 'pearl');
    await waitFor(() => expect(screen.getAllByTestId('delivery-row')).toHaveLength(1));
  }, 30_000);

  it('runs a delivery end to end: take, pick up, out for delivery, deliver with a photo, and shows the proof and who did what', async () => {
    asRoles('platform.dispatcher');
    // The file input filters by type like a browser would; the check under test is the app's own, so let any file through.
    const user = userEvent.setup({ applyAccept: false });
    await renderScreen(<DeliveryView />);
    await user.click(await rowOf('AT-2026-004901'));
    const sheet = await screen.findByTestId('delivery-sheet');
    expect(within(sheet).getByText(/117 Main St/)).toBeInTheDocument();
    expect(within(sheet).queryByRole('button', { name: /Mark delivered/ })).not.toBeInTheDocument();
    await user.click(within(sheet).getByRole('button', { name: 'Take this delivery' }));
    await user.click(await within(sheet).findByRole('button', { name: 'Picked up from the seller…' }));
    const pickup = await screen.findByRole('dialog', { name: 'Picked up from the seller' });
    await user.type(within(pickup).getByLabelText('Pickup note (optional)'), '2 boxes, handed over by Mama Tee');
    await user.click(within(pickup).getByRole('button', { name: 'Mark picked up' }));
    await user.click(await within(sheet).findByRole('button', { name: 'Mark out for delivery' }));
    await user.click(await within(sheet).findByRole('button', { name: /Mark delivered/ }));

    // A photo is required: nothing is delivered without one.
    const dialog = await screen.findByRole('dialog', { name: /Mark AT-2026-004901 delivered/ });
    await user.click(within(dialog).getByRole('button', { name: 'Mark delivered' }));
    expect(await within(dialog).findByText('Add at least one photo as proof of delivery.')).toBeInTheDocument();
    // A PDF is refused before any upload.
    await user.upload(within(dialog).getByLabelText('Add a photo'), new File(['x'], 'notes.pdf', { type: 'application/pdf' }));
    expect(await within(dialog).findByText('Use a JPEG, PNG or WebP photo')).toBeInTheDocument();
    // A real photo, who received it, and a note.
    await user.upload(within(dialog).getByLabelText('Add a photo'), new File([new Uint8Array([1, 2, 3])], 'door.jpg', { type: 'image/jpeg' }));
    expect(await within(dialog).findAllByTestId('proof-photo')).toHaveLength(1);
    await user.type(within(dialog).getByLabelText('Who received it (optional)'), 'Ada Obi');
    await user.type(within(dialog).getByLabelText('Note (optional)'), 'Left with the neighbour');
    await user.click(within(dialog).getByRole('button', { name: 'Mark delivered' }));

    const proof = await screen.findByTestId('delivery-proof-section');
    expect(within(proof).getByTestId('delivery-proof-photo')).toBeInTheDocument();
    expect(within(proof).getByText('Ada Obi')).toBeInTheDocument();
    expect(within(proof).getByText('Left with the neighbour')).toBeInTheDocument();
    const audit = within(screen.getByTestId('delivery-audit')).getAllByRole('listitem');
    expect(audit.map((a) => a.querySelector('strong')?.textContent)).toEqual(['Delivered with proof', 'Out for delivery', 'Picked up from the seller', 'Took the delivery']);
    // Names, never ids, say who did it.
    expect(within(screen.getByTestId('delivery-audit')).getAllByText(/Ifeoma Okafor|Amara|Felix|Okafor/).length).toBeGreaterThan(0);
  }, 60_000);

  it('records a failed attempt with a reason and offers a retry', async () => {
    asRoles('platform.dispatcher');
    const user = userEvent.setup();
    await renderScreen(<DeliveryView />);
    await user.click(await rowOf('AT-2026-004911'));
    const sheet = await screen.findByTestId('delivery-sheet');
    await user.click(within(sheet).getByRole('button', { name: /Record a failed attempt/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Record a failed attempt' });
    await user.click(within(dialog).getByRole('button', { name: 'Record failed attempt' }));
    expect(await within(dialog).findByText(/Say why it failed/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('What happened?'), 'Nobody home, left a card');
    await user.click(within(dialog).getByRole('button', { name: 'Record failed attempt' }));
    await waitFor(() => expect(within(screen.getByTestId('delivery-sheet')).getByRole('button', { name: /Try again/ })).toBeInTheDocument());
    expect(within(screen.getByTestId('delivery-sheet')).getByText('Nobody home, left a card')).toBeInTheDocument();
  }, 60_000);

  it('assigns another staff member by name', async () => {
    asRoles('platform.dispatcher');
    const user = userEvent.setup();
    await renderScreen(<DeliveryView />);
    await user.click(await rowOf('AT-2026-004901'));
    const sheet = await screen.findByTestId('delivery-sheet');
    await user.click(within(sheet).getByRole('button', { name: 'Assign to…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Assign this delivery' });
    await user.selectOptions(await within(dialog).findByLabelText('Courier'), 'Segun Lawal');
    await user.click(within(dialog).getByRole('button', { name: 'Assign' }));
    await waitFor(() => expect(within(screen.getByTestId('delivery-sheet')).getAllByText('Segun Lawal').length).toBeGreaterThan(0));
  }, 60_000);

  it('lets a reader look but never act', async () => {
    asRoles('platform.customer_support');
    const user = userEvent.setup();
    await renderScreen(<DeliveryView />);
    await user.click(await rowOf('AT-2026-004901'));
    const sheet = await screen.findByTestId('delivery-sheet');
    expect(within(sheet).getByText(/Running it needs the delivery:manage permission/)).toBeInTheDocument();
    for (const name of [/Take this delivery/, /Mark picked up/, /Mark delivered/, /Assign/]) expect(within(sheet).queryByRole('button', { name })).not.toBeInTheDocument();
    // The street and the phone are for people who run deliveries.
    expect(within(sheet).queryByText(/117 Main St/)).not.toBeInTheDocument();
    expect(within(sheet).getByText('Houston, TX')).toBeInTheDocument();
  }, 30_000);

  it('corrects a saved proof only with a reason', async () => {
    asRoles('platform.dispatcher');
    const user = userEvent.setup();
    await renderScreen(<DeliveryView />);
    await user.click(await screen.findByRole('tab', { name: /Delivered/ }));
    await user.click((await screen.findAllByTestId('delivery-row'))[0] as HTMLElement);
    const proof = await screen.findByTestId('delivery-proof-section');
    await user.click(within(proof).getByRole('button', { name: 'Correct the proof' }));
    const dialog = await screen.findByRole('dialog', { name: /Correct the proof of/ });
    await user.click(within(dialog).getByRole('button', { name: 'Save correction' }));
    expect(await within(dialog).findByText('Say why you are correcting the proof.')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Why are you correcting this?'), 'Wrong photo was attached');
    await user.click(within(dialog).getByRole('button', { name: 'Save correction' }));
    await waitFor(() => expect(within(screen.getByTestId('delivery-proof-section')).getByText(/1 · last by/)).toBeInTheDocument());
    expect(within(screen.getByTestId('delivery-audit')).getByText('Proof corrected')).toBeInTheDocument();
  }, 60_000);
});
