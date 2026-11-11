import { PROVIDER_ORG, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { citiesLine, ServiceAreaPanel } from '@/components/provider/ServiceAreaPanel';
import { ServicesView } from '@/components/provider/ServicesView';

import { asRole, mockApi } from './mock-api';
import { renderScreen } from './render';

vi.mock('next/navigation', () => ({ useSearchParams: () => new URLSearchParams(), usePathname: () => '/services', useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));

const openSheet = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(await screen.findByRole('button', { name: 'Set service area' }));
  return screen.findByRole('dialog', { name: 'Service area' });
};

describe('Provider Hub: service area', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('tells a provider with no area that customers cannot find them by location yet', async () => {
    await renderScreen(<ServiceAreaPanel />, PROVIDER_ORG);
    expect(await screen.findByText('Customers can’t find you by location yet')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Set service area' })).toBeInTheDocument();
  });

  it('saves a base ZIP and a radius within the staff-set bounds, with a live preview of the cities and zone', async () => {
    const user = userEvent.setup();
    await renderScreen(<ServiceAreaPanel />, PROVIDER_ORG);
    const dialog = await openSheet(user);
    const slider = within(dialog).getByRole('slider');
    expect(slider).toHaveAttribute('min', '5');
    expect(slider).toHaveAttribute('max', '40'); // hair & beauty
    expect(within(dialog).getByText(/Between 5 and 40 miles for your services/)).toBeInTheDocument();

    await user.type(within(dialog).getByLabelText('ZIP code'), '78664');
    const preview = within(dialog).getByTestId('service-area-preview');
    await waitFor(() => expect(preview).toHaveTextContent(/Greater Austin: Round Rock/));
    fireEvent.change(slider, { target: { value: '30' } });
    expect(within(dialog).getByText('How far you travel to customers: 30 miles')).toBeInTheDocument();
    expect(within(dialog).getByText(/it never limits them/)).toBeInTheDocument();
    expect(dialog.textContent).not.toMatch(/travel limit|smaller of your radius/i);
    await waitFor(() => expect(preview).toHaveTextContent(/Georgetown/));
    expect(preview).not.toHaveTextContent(/Brooklyn/);

    await user.click(within(dialog).getByRole('button', { name: 'Save service area' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Service area' })).not.toBeInTheDocument());
    const summary = await screen.findByTestId('service-area-summary');
    expect(summary).toHaveTextContent('Based in Round Rock, TX · you travel up to 30 miles to customers · Greater Austin');
    expect((await mockApi().getServiceArea()).area).toMatchObject({ baseZip: '78664', radiusMiles: 30 });
  });

  it('validates the ZIP and the extra areas before anything is sent', async () => {
    const user = userEvent.setup();
    await renderScreen(<ServiceAreaPanel />, PROVIDER_ORG);
    const dialog = await openSheet(user);
    await user.click(within(dialog).getByRole('button', { name: 'Save service area' }));
    expect(await within(dialog).findByText('Enter a 5-digit ZIP code')).toBeInTheDocument();
    expect((await mockApi().getServiceArea()).area).toBeNull();

    await user.click(within(dialog).getByRole('button', { name: 'Add' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Choose a zone');
    await user.selectOptions(within(dialog).getByLabelText('Metro zone'), 'greater-austin');
    await user.click(within(dialog).getByRole('button', { name: 'Add' }));
    expect(within(dialog).getByRole('listitem')).toHaveTextContent('Greater Austin');
    await user.click(within(dialog).getByRole('button', { name: 'Remove Greater Austin' }));
    expect(within(dialog).queryByRole('listitem')).not.toBeInTheDocument();
  });

  it('shows the server’s verdict on a radius outside the bounds', async () => {
    await mockApi().setActiveOrg(PROVIDER_ORG);
    await expect(mockApi().saveServiceArea({ baseZip: '78664', radiusMiles: 90 })).rejects.toMatchObject({ fieldErrors: { radiusMiles: expect.stringContaining('Between 5 and 40') } });
    await expect(mockApi().saveServiceArea({ baseZip: '00000', radiusMiles: 20 })).rejects.toMatchObject({ fieldErrors: { baseZip: expect.any(String) } });
  });

  it('lists the cities a radius reaches: the first few, then +N', () => {
    const cities = ['Georgetown', 'Round Rock', 'Hutto', 'Pflugerville', 'Cedar Park', 'Leander', 'Taylor', 'Austin'].map((city) => ({ city }));
    expect(citiesLine(cities)).toEqual({ text: 'Georgetown, Round Rock, Hutto, Pflugerville, Cedar Park, Leander', more: 2 });
    expect(citiesLine([{ city: 'Hutto' }, { city: 'Hutto' }])).toEqual({ text: 'Hutto', more: 0 });
  });
});

describe('Provider Hub: a service is in person only', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('offers exactly three ways to deliver (comes to you, you go to them, both) and no online option', async () => {
    const user = userEvent.setup();
    await renderScreen(<ServicesView />, PROVIDER_ORG);
    await user.click(await screen.findByRole('button', { name: 'Add service' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add service' });
    const radios = within(dialog).getAllByRole('radio');
    expect(radios.map((r) => r.closest('label')?.textContent?.split('·')[0]?.trim())).toEqual(['You go to them', 'Comes to you', 'Both']);
    expect(dialog.textContent).not.toMatch(/virtual|remote|video call/i);
    expect(within(dialog).getByText('AfroTransact services are in person only. There is no online or video option.')).toBeInTheDocument();
    const category = within(dialog).getByLabelText('Category');
    expect(within(category).getAllByRole('option').map((o) => o.textContent)).toEqual(['Choose a category', 'Hair & beauty', 'Catering', 'Tailoring', 'Tax & legal']);
  });

  it('saves the category, the delivery and the photo with the service', async () => {
    const user = userEvent.setup();
    await renderScreen(<ServicesView />, PROVIDER_ORG);
    await user.click(await screen.findByRole('button', { name: 'Add service' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add service' });
    await user.type(within(dialog).getByLabelText('Name'), 'Mobile silk press');
    await user.type(within(dialog).getByLabelText('Price ($)'), '95');
    await user.selectOptions(within(dialog).getByLabelText('Category'), 'hair & beauty');
    await user.click(within(dialog).getByRole('radio', { name: /Comes to you/ }));
    await user.type(within(dialog).getByLabelText('Photo URL'), 'https://img.example/press.jpg');
    await user.click(within(dialog).getByRole('button', { name: 'Save service' }));
    await waitFor(async () => expect((await mockApi().listServices()).find((s) => s.name === 'Mobile silk press')).toMatchObject({ category: 'hair & beauty', delivery: 'AT_CUSTOMER', imageUrl: 'https://img.example/press.jpg' }));
  });
});
