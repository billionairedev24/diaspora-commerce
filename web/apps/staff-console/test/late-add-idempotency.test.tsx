import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PreordersView } from '@/components/preorders/PreordersView';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const isLateAdd = (req: Request) => req.method === 'POST' && new URL(req.url).pathname.endsWith('/late-orders');

/** Opens the dialog and fills a complete late add (saved card, saved address, one item). */
async function fillLateAdd() {
  const user = userEvent.setup();
  nav.set('/preorders/ama-4');
  await renderScreen(<PreordersView screen={{ kind: 'detail', id: 'ama-4' }} />);
  await user.click(await screen.findByRole('button', { name: 'Late add' }));
  const dialog = await screen.findByRole('dialog', { name: 'Late add · Amarillo Pre-order #4' });
  const people = within(dialog).getByRole('combobox', { name: 'Matching customers' });
  await waitFor(() => expect(within(people).getAllByRole('option').length).toBeGreaterThan(1));
  await user.selectOptions(people, 'c0');
  await user.selectOptions(within(dialog).getByRole('combobox', { name: /^Deliver to/ }), 'a1');
  await user.click(await within(dialog).findByRole('button', { name: /Ijebu Garri, 4 lb/ }));
  await user.click(within(dialog).getByRole('checkbox', { name: /Phone read back and confirmed/ }));
  return { user, dialog };
}

describe('Late add idempotency (no double charge)', () => {
  beforeEach(() => {
    // The mock pre-order service runs on its own clock; the browser's clock matches it here.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-10T15:00:00Z'));
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
    asRoles('platform.customer_support');
  });
  afterEach(() => {
    interceptStaff(null);
    vi.useRealTimers();
  });

  it('offers the pay-now link with the AfroTransact payment page wording', async () => {
    const { dialog } = await fillLateAdd();
    expect(within(dialog).getByRole('option', { name: /Send a pay-now link by SMS \(opens the AfroTransact payment page\)/ })).toBeInTheDocument();
  });

  it('reuses the same Idempotency-Key on the retry after a server error', async () => {
    const keys: string[] = [];
    let fail = true;
    interceptStaff(async (req, next) => {
      if (isLateAdd(req)) {
        keys.push(req.headers.get('Idempotency-Key') ?? '');
        if (fail) {
          fail = false;
          return new Response(JSON.stringify({ title: 'Service unavailable', status: 503, code: 'UPSTREAM_UNAVAILABLE' }), { status: 503, headers: { 'Content-Type': 'application/problem+json' } });
        }
      }
      return next(req);
    });
    const { user, dialog } = await fillLateAdd();
    await user.click(within(dialog).getByRole('button', { name: 'Add $20.98 order' }));
    // The lookup finds nothing (the request never reached delivery-runs): retry, with the same key.
    await user.click(await within(dialog).findByRole('button', { name: 'Retry · add $20.98 order' }));
    expect(await screen.findByText(/Late order AMA-L90 added and logged/)).toBeInTheDocument();
    expect(keys).toHaveLength(2);
    expect(keys[0]).toMatch(/^late-.{16,}/);
    expect(keys[1]).toBe(keys[0]);
  });

  it('looks the late add up after a lost answer instead of adding it again', async () => {
    let posts = 0;
    interceptStaff(async (req, next) => {
      if (isLateAdd(req)) {
        posts += 1;
        await next(req); // delivery-runs added it…
        throw new TypeError('Failed to fetch'); // …but the answer was lost (timeout)
      }
      return next(req);
    });
    const { user, dialog } = await fillLateAdd();
    await user.click(within(dialog).getByRole('button', { name: 'Add $20.98 order' }));
    expect(await screen.findByText(/Late order AMA-L90 added and logged/)).toBeInTheDocument();
    expect(posts).toBe(1);
  });
});
