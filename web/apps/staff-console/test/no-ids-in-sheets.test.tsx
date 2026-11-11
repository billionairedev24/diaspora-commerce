import { getStaffDb, resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/*
 * Standing rule: staff UIs show names, never ids. The default (collapsed) sheet of the main resources must not show a
 * UUID-shaped string; raw ids live only behind the collapsed "Technical details" section.
 */

const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const ID_ROW = /\b(Reviewer|Final approver|Seller|Customer|Applicant|Assignee|Driver|Organisation|Payment|Order) ID\b/;

/** The dialog's text as a person sees it: what sits inside a closed <details> is left out. */
function visibleText(dialog: HTMLElement): string {
  const copy = dialog.cloneNode(true) as HTMLElement;
  copy.querySelectorAll('details').forEach((d) => {
    d.querySelectorAll(':scope > :not(summary)').forEach((c) => c.remove());
  });
  return copy.textContent ?? '';
}

const RESOURCES = [
  ['applications', '/applications'],
  ['orders', '/orders'],
  ['payouts', '/payouts'],
  ['organisations', '/organisations'],
  ['products', '/products']
] as const;

describe('Sheets show names, not ids', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-12T14:00:00Z'));
    asRoles(null);
  });
  afterEach(() => interceptStaff(null));

  it.each(RESOURCES)('the default %s sheet has no UUID and no "<label> ID" row outside Technical details', async (resource, path) => {
    const first = getStaffDb().records[resource][0];
    expect(first, `mock data for ${resource}`).toBeDefined();
    nav.set(`${path}?open=${encodeURIComponent(first!.id)}`);
    await renderScreen(<ResourceTable resource={resource} />);
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog.querySelectorAll('section').length).toBeGreaterThan(0));
    const text = visibleText(dialog);
    expect(text).not.toMatch(UUID);
    expect(text).not.toMatch(ID_ROW);
  });
});
