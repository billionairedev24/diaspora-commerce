import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Header } from '@/components/shell/Header';
import { LOLA_LABEL_KEY, LolaFab } from '@/components/shell/LolaFab';
import { LolaProvider } from '@/components/shell/LolaLauncher';

import { installMockBff } from './mockBff';
import { renderWithProviders } from './render';

const nav = vi.hoisted(() => ({ path: '/' }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => nav.path,
  useSearchParams: () => new URLSearchParams()
}));

installMockBff();

const shell = () => (
  <LolaProvider>
    <LolaFab />
  </LolaProvider>
);

beforeEach(() => {
  nav.path = '/';
  localStorage.clear();
});

describe('Lola launcher', () => {
  it('renders exactly once, labelled, and the header has no Lola entry', async () => {
    renderWithProviders(
      <LolaProvider>
        <Header />
        <LolaFab />
      </LolaProvider>
    );
    expect(screen.getAllByRole('button', { name: 'Chat with Lola' })).toHaveLength(1);
    expect(within(screen.getByTestId('site-header')).queryByText(/Lola/)).not.toBeInTheDocument();
    expect(within(screen.getByTestId('site-header')).queryByRole('button', { name: /Lola/ })).not.toBeInTheDocument();
  });

  it('is not shown on the checkout (nor under it)', () => {
    nav.path = '/checkout';
    const { unmount } = renderWithProviders(shell());
    expect(screen.queryByTestId('lola-launcher')).not.toBeInTheDocument();
    unmount();
    nav.path = '/checkout/complete';
    renderWithProviders(shell());
    expect(screen.queryByTestId('lola-launcher')).not.toBeInTheDocument();
  });

  it('opens the chat panel and closes it with the close button and with Esc, returning focus to the launcher', async () => {
    const user = userEvent.setup();
    renderWithProviders(shell());
    const launcher = screen.getByRole('button', { name: 'Chat with Lola' });
    expect(launcher).toHaveAttribute('aria-expanded', 'false');
    await user.click(launcher);
    const panel = await screen.findByRole('dialog', { name: 'Lola' });
    expect(within(panel).getByRole('log', { name: 'Conversation with Lola' })).toBeInTheDocument();
    // Starter questions are real features: orders, delivery to a ZIP code, finding a product.
    expect(within(panel).getByRole('button', { name: 'Track my order' })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Delivery to my ZIP code' })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Help me find a product' })).toBeInTheDocument();
    await user.click(within(panel).getByRole('button', { name: 'Close chat' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('lola-launcher')).toHaveFocus());
    await user.click(screen.getByTestId('lola-launcher'));
    await screen.findByRole('dialog', { name: 'Lola' });
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByTestId('lola-launcher')).toHaveFocus();
  });

  it('traps focus inside the open panel', async () => {
    const user = userEvent.setup();
    renderWithProviders(shell());
    await user.click(screen.getByTestId('lola-launcher'));
    const panel = await screen.findByRole('dialog', { name: 'Lola' });
    for (let i = 0; i < 12; i += 1) {
      await user.tab();
      expect(panel.contains(document.activeElement)).toBe(true);
    }
    for (let i = 0; i < 12; i += 1) {
      await user.tab({ shift: true });
      expect(panel.contains(document.activeElement)).toBe(true);
    }
  });

  it('an answer that fails says Lola is unavailable; nothing is made up', async () => {
    const user = userEvent.setup();
    const real = globalThis.fetch;
    // The AI budget is reached (429) or the gateway is down.
    globalThis.fetch = vi.fn(async () => new Response(JSON.stringify({ title: 'Too many requests', status: 429 }), { status: 429, headers: { 'Content-Type': 'application/problem+json' } })) as typeof fetch;
    try {
      renderWithProviders(shell());
      await user.click(screen.getByTestId('lola-launcher'));
      const panel = await screen.findByRole('dialog', { name: 'Lola' });
      await user.click(within(panel).getByRole('button', { name: 'Track my order' }));
      const alert = await within(panel).findByRole('alert');
      expect(alert).toHaveTextContent('Lola is unavailable right now');
      expect(within(panel).getAllByTestId('lola-answer').filter((a) => a.querySelector('p')?.textContent && !a.querySelector('[role="alert"]'))).toHaveLength(1); // only the greeting
    } finally {
      globalThis.fetch = real;
    }
  });
});

describe('first-visit label', () => {
  it('shows once, can be dismissed, and is remembered', async () => {
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(shell());
    const label = await screen.findByTestId('lola-fab-label');
    expect(label).toHaveTextContent('Ask Lola');
    await user.click(within(label).getByRole('button', { name: 'Dismiss' }));
    expect(screen.queryByTestId('lola-fab-label')).not.toBeInTheDocument();
    expect(localStorage.getItem(LOLA_LABEL_KEY)).toBe('1');
    unmount();
    renderWithProviders(shell());
    expect(screen.queryByTestId('lola-fab-label')).not.toBeInTheDocument();
  });

  it('still works when storage throws', async () => {
    const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    try {
      renderWithProviders(shell());
      expect(await screen.findByTestId('lola-launcher')).toBeInTheDocument();
      expect(await screen.findByTestId('lola-fab-label')).toBeInTheDocument();
    } finally {
      get.mockRestore();
      set.mockRestore();
    }
  });

  it('is gone once the chat is opened', async () => {
    const user = userEvent.setup();
    renderWithProviders(shell());
    await screen.findByTestId('lola-fab-label');
    await user.click(screen.getByTestId('lola-launcher'));
    await screen.findByRole('dialog', { name: 'Lola' });
    expect(screen.queryByTestId('lola-fab-label')).not.toBeInTheDocument();
  });
});

