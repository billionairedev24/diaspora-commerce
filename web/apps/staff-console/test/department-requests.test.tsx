import { getStaffDb, resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { DepartmentRequestsView } from '@/components/departments/DepartmentRequestsView';
import { OrgDepartmentsSection } from '@/components/departments/OrgDepartmentsSection';
import { ModuleScreen } from '@/components/module/ModuleScreen';

import { asRoles, mockApi } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const NOW = new Date('2026-11-03T12:00:00Z');

describe('Department requests screen', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles('platform.operations_manager');
    nav.set('/department-requests');
  });

  it('lists the pending requests by name on the Pending tab and the decided ones on Decided', async () => {
    const user = userEvent.setup();
    await renderScreen(<DepartmentRequestsView />);
    expect(await screen.findByText("Ade's Kitchen")).toBeInTheDocument();
    expect(screen.getByText('Mama Efe Braids')).toBeInTheDocument();
    expect(screen.getByText('Kente Naturals')).toBeInTheDocument();
    expect(screen.queryByText('Nile Beauty')).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Pending (3)' })).toBeInTheDocument();
    // no ids on screen
    expect(screen.queryByText(/org-ade|7d6b1d2a/)).not.toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Decided' }));
    expect(await screen.findByText('Nile Beauty')).toBeInTheDocument();
    expect(screen.getAllByText('Approved').length).toBe(2);
    expect(screen.getByText('Rejected')).toBeInTheDocument();
    expect(screen.queryByText("Ade's Kitchen")).not.toBeInTheDocument();
    expect(screen.getAllByText('Amara Diallo').length).toBeGreaterThan(0);
  });

  it('filters by department name', async () => {
    const user = userEvent.setup();
    await renderScreen(<DepartmentRequestsView />);
    await screen.findByText("Ade's Kitchen");
    await user.type(screen.getByRole('textbox', { name: 'Department' }), 'beauty');
    await waitFor(() => expect(screen.queryByText("Ade's Kitchen")).not.toBeInTheDocument());
    expect(screen.getByText('Mama Efe Braids')).toBeInTheDocument();
  });

  it('opens a request with its compliance prompts, and approves with an optional note', async () => {
    const user = userEvent.setup();
    nav.set('/department-requests?open=7d6b1d2a-0003-4a6e-9c11-000000000003');
    await renderScreen(<DepartmentRequestsView />);
    expect(await screen.findByText('Food permit')).toBeInTheDocument();
    expect(screen.getByText(/Not on file yet/)).toBeInTheDocument();
    expect(screen.getAllByText('Fashion').length).toBeGreaterThan(0); // current departments by name

    await user.click(screen.getByRole('button', { name: 'Approve' }));
    const dialog = await screen.findByRole('dialog', { name: 'Approve this request?' });
    expect(within(dialog).getByText(/Not on file yet/)).toBeInTheDocument(); // warning about the missing permit
    await user.type(within(dialog).getByRole('textbox'), 'Permit seen by phone.');
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Approve this request?' })).not.toBeInTheDocument());
    const done = getStaffDb().departments.requests.find((r) => r.id.endsWith('3'));
    expect(done).toMatchObject({ status: 'APPROVED', decisionNote: 'Permit seen by phone.' });
  });

  it('will not reject without a reason, and sends the reason the seller reads', async () => {
    const user = userEvent.setup();
    nav.set('/department-requests?open=7d6b1d2a-0002-4a6e-9c11-000000000002');
    await renderScreen(<DepartmentRequestsView />);
    await user.click(await screen.findByRole('button', { name: 'Reject' }));
    const dialog = await screen.findByRole('dialog', { name: 'Reject this request?' });
    const submit = within(dialog).getByRole('button', { name: 'Reject' });
    expect(submit).toBeDisabled();
    await user.type(within(dialog).getByRole('textbox'), 'ab');
    expect(submit).toBeDisabled();
    await user.type(within(dialog).getByRole('textbox'), 'c, add labels first');
    expect(submit).toBeEnabled();
    await user.click(submit);
    await waitFor(() => expect(getStaffDb().departments.requests.find((r) => r.id.endsWith('2'))?.status).toBe('REJECTED'));
    expect(getStaffDb().departments.requests.find((r) => r.id.endsWith('2'))?.decisionNote).toBe('abc, add labels first');
  });

  it('shows a plain message when somebody already decided', async () => {
    const user = userEvent.setup();
    nav.set('/department-requests?open=7d6b1d2a-0001-4a6e-9c11-000000000001');
    await renderScreen(<DepartmentRequestsView />);
    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    const dialog = await screen.findByRole('dialog', { name: 'Approve this request?' });
    // someone else decides first
    await mockApi().departments.approve('7d6b1d2a-0001-4a6e-9c11-000000000001', '');
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));
    expect(await screen.findByText(/already approved/)).toBeInTheDocument();
  });

  it('is hidden for staff without the permission, and read-only without approve', async () => {
    asRoles('platform.marketing');
    await renderScreen(<DepartmentRequestsView />);
    expect(await screen.findByText(/don.t have access/i)).toBeInTheDocument();
  });

  it('puts the pending count on the nav entry', async () => {
    const session = await mockApi().getSession();
    const entry = session.navGroups.flatMap((g) => g.modules).find((m) => m.key === 'deptRequests');
    expect(entry).toMatchObject({ label: 'Department requests', badge: 3 });
    asRoles('platform.marketing');
    const other = await mockApi().getSession();
    expect(other.navGroups.flatMap((g) => g.modules).some((m) => m.key === 'deptRequests')).toBe(false);
  });

  it('renders through the module screen', async () => {
    await renderScreen(<ModuleScreen module="deptRequests" />);
    expect(await screen.findByRole('heading', { name: 'Department requests' })).toBeInTheDocument();
  });
});

describe('Organisation Departments section', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles('platform.operations_manager');
  });

  it('lists the departments by name and saves an override only when something changed and a reason is given', async () => {
    const user = userEvent.setup();
    await renderScreen(<OrgDepartmentsSection orgId="org-ade" />);
    expect(await screen.findByText('Groceries & Food')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const dialog = await screen.findByRole('dialog');
    const save = within(dialog).getByRole('button', { name: 'Save departments' });
    expect(save).toBeDisabled(); // unchanged
    await user.type(within(dialog).getByRole('textbox'), 'Seller added tailoring, by phone');
    expect(save).toBeDisabled(); // still unchanged: a reason alone is not a change
    await user.click(within(dialog).getByRole('checkbox', { name: 'Fashion' }));
    expect(save).toBeEnabled();
    await user.click(within(dialog).getByRole('checkbox', { name: 'Fashion' }));
    expect(save).toBeDisabled(); // back to the original set
    await user.click(within(dialog).getByRole('checkbox', { name: 'Fashion' }));
    await user.click(save);
    await waitFor(() => expect(getStaffDb().departments.orgs.find((o) => o.id === 'org-ade')?.departments).toEqual(['fashion', 'groceries']));
    expect(await screen.findByText('Fashion')).toBeInTheDocument();
  });

  it('needs a reason before saving', async () => {
    const user = userEvent.setup();
    await renderScreen(<OrgDepartmentsSection orgId="org-ade" />);
    await user.click(await screen.findByRole('button', { name: 'Edit' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('checkbox', { name: 'Fashion' }));
    expect(within(dialog).getByRole('button', { name: 'Save departments' })).toBeDisabled();
    await user.type(within(dialog).getByRole('textbox'), 'too short');
    expect(within(dialog).getByRole('button', { name: 'Save departments' })).toBeDisabled();
  });

  it('shows no Edit action without applications:approve, and nothing without applications:review', async () => {
    asRoles('platform.customer_support'); // no applications permissions
    const { unmount } = await renderScreen(<OrgDepartmentsSection orgId="org-ade" />);
    expect(screen.queryByText('Departments')).not.toBeInTheDocument();
    unmount();
  });

  it('says every department for AfroTransact Retail and offers no edit', async () => {
    await renderScreen(<OrgDepartmentsSection orgId="org-retail" />);
    expect(await screen.findByText(/Every department/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
  });
});
