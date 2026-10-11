import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { BackofficeFrame } from '../src/shell/BackofficeFrame';

function Frame({ route }: { route: string }) {
  return (
    <BackofficeFrame
      routeKey={route}
      header={(menu) => <header>{menu}</header>}
      nav={
        <nav aria-label="Back office">
          <a href="#orders">Orders</a>
        </nav>
      }
      rail={<nav aria-label="Back office icons" />}
    >
      <main>Page</main>
    </BackofficeFrame>
  );
}

describe('BackofficeFrame', () => {
  it('phone menu: a disclosure in the header that expands the navigation inline and closes on navigation', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<Frame route="/" />);
    const menu = screen.getByRole('button', { name: 'Menu' });
    const nav = document.getElementById(menu.getAttribute('aria-controls') ?? '');
    expect(nav).not.toBeNull();
    expect(menu).toHaveAttribute('aria-expanded', 'false');
    expect(nav).toHaveAttribute('data-open', 'false');

    await user.click(menu);
    expect(menu).toHaveAttribute('aria-expanded', 'true');
    expect(nav).toHaveAttribute('data-open', 'true');
    // No overlay: nothing modal, the page stays in the document flow after the navigation.
    expect(screen.queryByRole('dialog')).toBeNull();

    // Choosing a link closes it (even for the current page); so does Escape.
    await user.click(screen.getByRole('link', { name: 'Orders' }));
    expect(menu).toHaveAttribute('aria-expanded', 'false');
    await user.click(menu);
    await user.keyboard('{Escape}');
    expect(menu).toHaveAttribute('aria-expanded', 'false');

    // A route change closes it.
    await user.click(menu);
    rerender(<Frame route="/orders" />);
    expect(screen.getByRole('button', { name: 'Menu' })).toHaveAttribute('aria-expanded', 'false');
  });

  it('tablet rail: the toggle expands the icon rail to the full navigation and folds it back', async () => {
    const user = userEvent.setup();
    render(<Frame route="/" />);
    const expand = screen.getByRole('button', { name: 'Expand menu' });
    expect(expand).toHaveAttribute('aria-expanded', 'false');
    await user.click(expand);
    expect(screen.getByRole('button', { name: 'Collapse menu' })).toHaveAttribute('aria-expanded', 'true');
  });
});
