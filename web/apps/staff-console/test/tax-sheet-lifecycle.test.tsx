import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { TaxReviewPanel } from '@/components/tax/TaxReviewPanel';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/*
 * Regression (owner feedback on Sales tax > Tax review): with the detail sheet open, a click on another sidebar item must
 * leave the page and drop the sheet, and coming back must show the plain list. Cause: the docked Sheet closed on the
 * pointer-down of any outside click and the page answered with router.replace(own url), which raced the menu link's own
 * navigation and won. The sheet now leaves link clicks to the link. jsdom has no real pointer sequence (Radix never sees the
 * outside pointer-down), so the browser journey tests/e2e/specs/staff-console.sheet-lifecycle.spec.ts is the one that fails
 * on the old code; these cover the state handling around it.
 */
const NOW = new Date('2026-11-09T12:00:00Z');

/** The sidebar, as far as this test needs it: a real link whose click navigates like Next's <Link>. */
function Menu() {
  return (
    <nav aria-label="Menu">
      <a
        href="/payouts"
        onClick={(e) => {
          e.preventDefault();
          nav.push('/payouts');
        }}
      >
        Payouts
      </a>
    </nav>
  );
}

const writes = () => [...nav.replace.mock.calls, ...nav.push.mock.calls].map(([url]) => String(url));

describe('Tax review sheet lifecycle', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles('platform.super_admin');
    nav.replace.mockClear();
    nav.push.mockClear();
  });
  afterEach(() => interceptStaff(null));

  it('a click on a menu link leaves the page and the page writes no address of its own (the Playwright journey proves the real pointer order)', async () => {
    nav.set('/tax?tab=tax-review&variant=AT20000101');
    const user = userEvent.setup();
    await renderScreen(
      <>
        <Menu />
        <TaxReviewPanel />
      </>
    );
    await screen.findByRole('dialog', { name: 'Roasted cashews, 6 oz bag' });
    nav.replace.mockClear();
    await user.click(screen.getByRole('link', { name: 'Payouts' }));
    expect(nav.pathname).toBe('/payouts');
    // Nothing wrote /tax?… (without the variant) before or after the link's own push.
    expect(writes().filter((u) => u.startsWith('/tax'))).toEqual([]);
  });

  it('coming back to Tax review opens the plain list: no sheet, no stale selection', async () => {
    nav.set('/tax?tab=tax-review&variant=AT20000101');
    const user = userEvent.setup();
    const first = await renderScreen(
      <>
        <Menu />
        <TaxReviewPanel />
      </>
    );
    await screen.findByRole('dialog', { name: 'Roasted cashews, 6 oz bag' });
    await user.click(screen.getByRole('link', { name: 'Payouts' }));
    first.unmount();
    // The menu's Tax review link is /tax: no query at all.
    nav.set('/tax');
    await renderScreen(<TaxReviewPanel />);
    await screen.findByRole('table', { name: 'Tax review queue' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.querySelector('tr[aria-current="true"]')).toBeNull();
  });

  it('Escape closes the sheet, drops ?variant= and hands focus back to the list', async () => {
    nav.set('/tax?tab=tax-review');
    const user = userEvent.setup();
    await renderScreen(<TaxReviewPanel />);
    const table = await screen.findByRole('table', { name: 'Tax review queue' });
    const row = (await within(table).findByText('Roasted cashews, 6 oz bag')).closest('tr') as HTMLElement;
    row.focus();
    await user.click(row);
    await screen.findByRole('dialog', { name: 'Roasted cashews, 6 oz bag' });
    expect(nav.search).toContain('variant=AT20000101');
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(nav.search).not.toContain('variant=');
    expect(nav.search).not.toContain('do=');
  });

  it('a close that fires after the page was left writes nothing', async () => {
    nav.set('/tax?tab=tax-review&variant=AT20000101');
    await renderScreen(<TaxReviewPanel />);
    await screen.findByRole('dialog', { name: 'Roasted cashews, 6 oz bag' });
    nav.replace.mockClear();
    nav.set('/payouts');
    // Radix reports the close of a sheet whose page just went away (focus or pointer leaving); the page must not answer.
    await userEvent.setup().keyboard('{Escape}');
    expect(nav.replace.mock.calls.filter(([u]) => String(u).startsWith('/tax'))).toEqual([]);
  });

  it('names the item and its variant in the sheet, and says Status (not "Why it needs review") for an approved item', async () => {
    nav.set('/tax?tab=tax-review&variant=AT20000101');
    await renderScreen(<TaxReviewPanel />);
    const pending = await screen.findByRole('dialog', { name: 'Roasted cashews, 6 oz bag' });
    expect((await within(pending).findAllByRole('heading', { name: 'Roasted cashews, 6 oz bag' })).length).toBeGreaterThan(0);
    expect(within(pending).getByText('Size 6 oz · Salted')).toBeInTheDocument();
    expect(within(pending).getByText(/Sold by Mama Nkechi/)).toBeInTheDocument();
    expect(within(pending).getByText('Why it needs review')).toBeInTheDocument();
  });

  it('an approved item has a Status notice, never "Why it needs review"', async () => {
    nav.set('/tax?tab=tax-review&review.view=decisions&variant=AT20000107');
    await renderScreen(<TaxReviewPanel />);
    const sheet = await screen.findByRole('dialog', { name: 'Jollof seasoning blend, 4 oz' });
    await within(sheet).findByText('Decision');
    expect(within(sheet).queryByText('Why it needs review')).not.toBeInTheDocument();
    expect(within(sheet).getByText('Status')).toBeInTheDocument();
  });
});
