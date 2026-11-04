import { http, HttpResponse, mockServer } from '@afrotransact/api-client/mocks/node';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { StockAlertForm } from '@/components/product/StockAlertForm';

import { installMockBff, sent } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

const alertRequests = () => sent.filter((r) => r.url.includes('/stock-alerts'));
const problem = (status: number, body: Record<string, unknown>) =>
  HttpResponse.json({ type: 'about:blank', title: 'x', status, ...body }, { status, headers: { 'content-type': 'application/problem+json' } });

describe('"Email me when available" for a guest', () => {
  it('subscribes with just an email: no sign-in, and says where the email will go', async () => {
    const user = userEvent.setup();
    renderWithProviders(<StockAlertForm atin="AT00000P18" />);
    expect(screen.getByText(/No account needed/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('Your email'), '  Ada@Example.com ');
    await user.click(screen.getByRole('button', { name: 'Email me when available' }));
    expect(await screen.findByText('✓ We’ll email you at ada@example.com when it’s back')).toBeInTheDocument();
    expect(screen.queryByLabelText('Your email')).not.toBeInTheDocument();
    const [req] = alertRequests();
    expect(req?.method).toBe('POST');
    expect(req?.url).toContain('/v1/products/AT00000P18/stock-alerts');
    expect(screen.queryByText(/sign in/i)).not.toBeInTheDocument();
  });

  it('explains a missing or malformed address in plain words and sends nothing', async () => {
    const user = userEvent.setup();
    renderWithProviders(<StockAlertForm atin="AT00000P18" />);
    const button = screen.getByRole('button', { name: 'Email me when available' });
    await user.click(button);
    expect(await screen.findByText('Enter your email address so we know where to write.')).toBeInTheDocument();
    for (const bad of ['nope', 'Ada <a@example.com>', 'a@b']) {
      await user.clear(screen.getByLabelText('Your email'));
      await user.type(screen.getByLabelText('Your email'), bad);
      await user.click(button);
      expect(await screen.findByText('Enter a valid email address, like name@example.com.')).toBeInTheDocument();
    }
    expect(alertRequests()).toHaveLength(0);
  });

  it('shows the server’s verdict when it disagrees (422), and keeps what was typed', async () => {
    mockServer.use(http.post('*/v1/products/:atin/stock-alerts', () => problem(422, { code: 'VALIDATION_FAILED', errors: { email: 'Enter a valid email address' } })));
    const user = userEvent.setup();
    renderWithProviders(<StockAlertForm atin="AT00000P18" />);
    await user.type(screen.getByLabelText('Your email'), 'ada@example.com');
    await user.click(screen.getByRole('button', { name: 'Email me when available' }));
    expect(await screen.findByText('Enter a valid email address, like name@example.com.')).toBeInTheDocument();
    expect(screen.getByLabelText('Your email')).toHaveValue('ada@example.com');
  });

  it('asks the visitor to wait on a 429 and to retry on a server error, never mentioning sign-in', async () => {
    const user = userEvent.setup();
    renderWithProviders(<StockAlertForm atin="AT00000P18" />);
    await user.type(screen.getByLabelText('Your email'), 'ada@example.com');
    mockServer.use(http.post('*/v1/products/:atin/stock-alerts', () => problem(429, { code: 'RATE_LIMITED' }), { once: true }));
    await user.click(screen.getByRole('button', { name: 'Email me when available' }));
    expect(await screen.findByText('That’s a lot of alerts. Please wait a little and try again.')).toBeInTheDocument();
    mockServer.use(http.post('*/v1/products/:atin/stock-alerts', () => problem(503, { code: 'UPSTREAM_UNAVAILABLE' }), { once: true }));
    await user.click(screen.getByRole('button', { name: 'Email me when available' }));
    expect(await screen.findByText('We couldn’t save your alert. Please try again in a moment.')).toBeInTheDocument();
    expect(screen.queryByText(/sign in/i)).not.toBeInTheDocument();
    // And the same form still works afterwards.
    await user.click(screen.getByRole('button', { name: 'Email me when available' }));
    await waitFor(() => expect(screen.getByText(/We’ll email you at ada@example.com/)).toBeInTheDocument());
  });
});

describe('"Email me when available" for a signed-in shopper', () => {
  it('prefills the account email, which can be used as is', async () => {
    const user = userEvent.setup();
    renderWithProviders(<StockAlertForm atin="AT00000P18" accountEmail="ada@example.com" />);
    expect(screen.getByLabelText('Your email')).toHaveValue('ada@example.com');
    await user.click(screen.getByRole('button', { name: 'Email me when available' }));
    expect(await screen.findByText(/We’ll email you at ada@example.com/)).toBeInTheDocument();
  });
});
