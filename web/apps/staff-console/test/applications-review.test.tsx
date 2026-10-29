import { getStaffDb, resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/** APP-4413 (app-203) is awaiting final approval; step 1 was decided by someone else (stf-2). */
const isFinalApprove = (req: Request) => req.method === 'POST' && new URL(req.url).pathname.endsWith('/applications/app-203/actions/final-approve');

const problem = (status: number, code: string, detail: string) =>
  new Response(JSON.stringify({ type: 'about:blank', title: code, status, code, detail }), { status, headers: { 'Content-Type': 'application/problem+json' } });

async function openFinalApproval() {
  const user = userEvent.setup();
  nav.set('/applications?open=app-203');
  await renderScreen(<ResourceTable resource="applications" />);
  await user.click(await screen.findByRole('button', { name: 'Final approval' }));
  const dialog = await screen.findByRole('dialog', { name: 'Give final approval' });
  return { user, dialog };
}

describe('Application review decisions (one request per decision)', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-12T14:00:00Z'));
    asRoles(null);
  });
  afterEach(() => interceptStaff(null));

  it('disables every decision button while the final approval is in flight and sends it once', async () => {
    const keys: string[] = [];
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    interceptStaff(async (req, next) => {
      if (isFinalApprove(req)) {
        keys.push(req.headers.get('Idempotency-Key') ?? '');
        await gate;
      }
      return next(req);
    });
    const { user, dialog } = await openFinalApproval();
    const approve = within(dialog).getByRole('button', { name: 'Approve' });
    await user.click(approve);
    await waitFor(() => expect(keys).toHaveLength(1));

    // In flight: the submit button and every action of the sheet are disabled; a second click sends nothing.
    expect(approve).toBeDisabled();
    for (const name of ['Final approval', 'Send back', 'Reject']) {
      expect(screen.getByRole('button', { name, hidden: true })).toBeDisabled();
    }
    await user.click(approve);
    await user.keyboard('{Enter}');

    release();
    expect(await screen.findByText('Final approval — done.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Give final approval' })).not.toBeInTheDocument());
    expect(screen.getAllByText('Approved').length).toBeGreaterThan(0);
    expect(keys).toHaveLength(1);
    expect(screen.getByRole('button', { name: /Final approval/ })).toBeDisabled();
  });

  it('never retries a failed decision; trying again replays the same Idempotency-Key', async () => {
    const keys: string[] = [];
    interceptStaff(async (req, next) => {
      if (isFinalApprove(req)) {
        keys.push(req.headers.get('Idempotency-Key') ?? '');
        if (keys.length === 1) return problem(503, 'UPSTREAM_UNAVAILABLE', 'The seller service is temporarily unavailable.');
      }
      return next(req);
    });
    const { user, dialog } = await openFinalApproval();
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Not saved.');
    expect(keys).toHaveLength(1);

    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));
    expect(await screen.findByText('Final approval — done.')).toBeInTheDocument();
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it('shows "already approved" as information with the current state, not as an error', async () => {
    interceptStaff(async (req, next) =>
      isFinalApprove(req) ? problem(409, 'ALREADY_DONE', 'This application is already approved (by Ada Obi, 12 Oct 2026, 14:03 UTC)') : next(req)
    );
    const { user, dialog } = await openFinalApproval();
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Give final approval' })).not.toBeInTheDocument());
    const notice = (await screen.findAllByRole('status')).find((n) => n.textContent?.includes('Already done'));
    expect(notice).toHaveTextContent('This application is already approved (by Ada Obi, 12 Oct 2026, 14:03 UTC)');
    expect(screen.queryByText('Not saved.')).not.toBeInTheDocument();
    expect(screen.queryByText(/needs a reviewer decision/)).not.toBeInTheDocument();
  });

  it('a second approver on an approved application is told who approved it and when (mock BFF)', async () => {
    const { user, dialog } = await openFinalApproval();
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));
    expect(await screen.findByText('Final approval — done.')).toBeInTheDocument();

    // A stale sheet in another tab: the approver's own repeat is answered with the record (200).
    const again = await mockRunFinalApproval(null);
    expect(again.status).toBe(200);
    // Someone else approved it (the mock knows one staff member, so the approval is re-attributed): 409 ALREADY_DONE
    // naming the approver.
    const app = getStaffDb().records.applications.find((r) => r.id === 'app-203');
    if (!app?.activity[0]) throw new Error('no approval logged');
    app.activity[0].actor = 'Ada Obi';
    const other = await mockRunFinalApproval('platform.trust_safety');
    expect(other.status).toBe(409);
    const body = (await other.json()) as { code: string; detail: string };
    expect(body.code).toBe('ALREADY_DONE');
    expect(body.detail).toMatch(/^This application is already approved \(by Ada Obi, \d{1,2} [A-Z][a-z]{2,3} \d{4}, \d\d:\d\d UTC\)$/);
  });
});

async function mockRunFinalApproval(role: string | null): Promise<Response> {
  const { handleStaffMockRequest } = await import('@afrotransact/api-client/mocks/staff-runtime');
  const { MOCK_STAFF_ROLE_COOKIE } = await import('@afrotransact/api-client/mocks/staff');
  return handleStaffMockRequest(
    new Request('http://staff.test/api/bff/v1/staff/resources/applications/app-203/actions/final-approve', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `repeat-${role ?? 'me'}-0123456789`, ...(role ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${role}` } : {}) },
      body: JSON.stringify({ values: {} })
    })
  );
}
