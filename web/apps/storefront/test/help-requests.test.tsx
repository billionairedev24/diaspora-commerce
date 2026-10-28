import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { HelpNewView } from '@/components/account/HelpNewView';
import { HelpView } from '@/components/account/HelpView';
import { accountMenuModel } from '@/components/shell/accountMenuItems';

import { installMockBff, sent, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

const photo = (name: string, type = 'image/jpeg', bytes = 2000) => new File([new Uint8Array(bytes)], name, { type });

describe('Get help with an order — the list', () => {
  it('shows my requests in plain words, the staff decision and the delivered orders to ask about', async () => {
    await signInDemo();
    renderWithProviders(<HelpView />);
    const card = await screen.findByTestId('help-request');
    expect(within(card).getByText('Resolved', { selector: 'span' })).toBeInTheDocument();
    expect(within(card).getByText('It arrived damaged')).toBeInTheDocument();
    expect(within(card).getByText('Refunded $18.99')).toBeInTheDocument();
    expect(within(card).getByText(/We have refunded the book/)).toBeInTheDocument();
    expect(within(card).getByText('The refund goes back to whoever paid, the same way they paid.')).toBeInTheDocument();
    // Names and references, never internal ids.
    expect(card.textContent).toContain('CLM-9001');
    expect(card.textContent).not.toContain('help-9001');
    const links = screen.getAllByRole('link', { name: /Get help/ });
    expect(links.map((a) => a.getAttribute('href'))).toEqual(
      expect.arrayContaining(['/account/help/new?order=AT-2026-104221', '/account/help/new?order=AT-2026-100873'])
    );
    await userEvent.setup().click(within(card).getByRole('button', { name: 'Details' }));
    expect(within(card).getByText(/cover was torn/)).toBeInTheDocument();
    expect(within(card).getByText('2 photos')).toBeInTheDocument();
  });

  it('is linked from the account menu', () => {
    const menu = accountMenuModel({ authenticated: true, user: { id: 'u', firstName: 'A', lastName: 'B', email: 'a@b.c', initials: 'AB', roles: [], backOffices: [] }, counts: { orders: 0, addresses: 0, paymentMethods: 0 } }, '/');
    expect(menu.items.find((i) => i.id === 'help')).toMatchObject({ href: '/account/help', label: 'account.help' });
    expect(menu.items.some((i) => i.href?.includes('/account/returns'))).toBe(false);
  });
});

describe('Get help with an order — report a problem', () => {
  it('asks for what is missing, then uploads photos with progress, lets one go and sends the request once', async () => {
    await signInDemo();
    const user = userEvent.setup({ applyAccept: false }); // the app, not the file picker, refuses the wrong types
    renderWithProviders(<HelpNewView orderNumber="AT-2026-104221" />);
    expect(await screen.findByRole('heading', { name: 'Report a problem with order AT-2026-104221' })).toBeInTheDocument();
    await screen.findByTestId('photo-input');

    // Nothing filled in: an error summary and field errors, no request sent.
    await user.click(screen.getByRole('button', { name: 'Send request' }));
    expect(await screen.findAllByText('Choose what went wrong.')).not.toHaveLength(0);
    expect(screen.getByText(/Describe the problem in at least 10 characters/, { selector: 'p' })).toBeInTheDocument();
    expect(sent.some((r) => r.method === 'POST' && r.url.endsWith('/v1/me/help-requests'))).toBe(false);

    await user.click(screen.getByRole('radio', { name: 'It arrived damaged' }));
    await user.type(screen.getByRole('textbox', { name: 'Tell us what happened' }), 'The jar arrived cracked and leaking.');

    // Photos: a wrong type and an oversized one are refused with their names; good ones upload.
    const input = screen.getByTestId('photo-input');
    await user.upload(input, [photo('receipt.pdf', 'application/pdf'), photo('huge.png', 'image/png', 11 * 1024 * 1024), photo('cracked-jar.jpg'), photo('label.heic', '')]);
    expect(await screen.findByText('receipt.pdf isn’t a JPEG, PNG, WebP or HEIC photo.')).toBeInTheDocument();
    expect(screen.getByText('huge.png is larger than 10 MB.')).toBeInTheDocument();
    const rows = screen.getAllByTestId('photo');
    expect(rows).toHaveLength(2);
    await waitFor(() => rows.forEach((row) => expect(within(row).getByText(/ added$/)).toBeInTheDocument()));
    await user.click(within(rows[1] as HTMLElement).getByRole('button', { name: 'Remove label.heic' }));
    expect(screen.getAllByTestId('photo')).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: 'Send request' }));
    const done = await screen.findByTestId('help-request-sent');
    expect(within(done).getByText(/Your reference is CLM-\d{4}/)).toBeInTheDocument();
    const posts = sent.filter((r) => r.method === 'POST' && r.url.endsWith('/v1/me/help-requests'));
    expect(posts).toHaveLength(1);
    expect(posts[0]?.headers.get('idempotency-key')).toMatch(/^help-/);
    const body = (await posts[0]?.clone().json()) as { orderNumber: string; type: string; evidenceAssetIds: string[]; shipmentId?: string };
    expect(body).toMatchObject({ orderNumber: 'AT-2026-104221', type: 'DAMAGED_ITEM' });
    expect(body.evidenceAssetIds).toHaveLength(1);
  });

  it('stops at five photos', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<HelpNewView orderNumber="AT-2026-104221" />);
    await user.upload(await screen.findByTestId('photo-input'), Array.from({ length: 6 }, (_, i) => photo(`p${i}.jpg`)));
    expect(await screen.findByText('You can add up to 5 photos.')).toBeInTheDocument();
    expect(screen.getAllByTestId('photo')).toHaveLength(5);
  });

  it('says so when the order is not the customer’s', async () => {
    await signInDemo();
    renderWithProviders(<HelpNewView orderNumber="AT-2026-NOPE" />);
    expect(await screen.findByText('We couldn’t find that order.')).toBeInTheDocument();
  });
});
