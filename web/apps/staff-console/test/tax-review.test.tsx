import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { TaxReviewPanel } from '@/components/tax/TaxReviewPanel';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const NOW = new Date('2026-11-09T12:00:00Z');

describe('Tax review', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles('platform.super_admin');
    nav.set('/fees?tab=tax-review');
  });
  afterEach(() => interceptStaff(null));

  it('lists what waits for review by product and seller name with the reason it needs a person', async () => {
    await renderScreen(<TaxReviewPanel />);
    const table = await screen.findByRole('table', { name: 'Tax review queue' });
    const row = (await within(table).findByText('Roasted cashews, 6 oz bag')).closest('tr') as HTMLElement;
    expect(within(row).getByText('Mama Nkechi’s Kitchen')).toBeInTheDocument();
    expect(within(row).getByText('Nuts & seeds')).toBeInTheDocument();
    expect(within(row).getByText('Exempt (no tax)')).toBeInTheDocument();
    expect(within(row).getByText(/claims a tax exemption and attached proof/)).toBeInTheDocument();
    expect(within(table).getByText('Yoruba for beginners (e-book)')).toBeInTheDocument();
    expect(table).not.toHaveTextContent(/org-|AT2000/);
    // Decided variants are not in the queue.
    expect(within(table).queryByText('Jollof seasoning blend, 4 oz')).not.toBeInTheDocument();
  });

  it('lists the decisions with who decided, when and the rule version', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxReviewPanel />);
    await user.click(await screen.findByRole('button', { name: 'Decisions' }));
    const table = await screen.findByRole('table', { name: 'Tax decisions' });
    const row = (await within(table).findByText('Jollof seasoning blend, 4 oz')).closest('tr') as HTMLElement;
    expect(within(row).getByText('Approved')).toBeInTheDocument();
    expect(within(row).getByText('Ada Okafor')).toBeInTheDocument();
    expect(within(row).getByText('tx-2026.11.09-draft.1')).toBeInTheDocument();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Decision' }), 'REJECTED');
    expect(await within(table).findByText('Ankara print tote bag')).toBeInTheDocument();
  });

  it('shows the facts as entered, the proof with text alternatives, and the proposed decision in words', async () => {
    nav.set('/fees?tab=tax-review&variant=AT20000101');
    await renderScreen(<TaxReviewPanel />);
    const sheet = await screen.findByRole('dialog', { name: 'Roasted cashews, 6 oz bag' });
    const facts = await within(sheet).findByRole('region', { name: 'Facts as entered' });
    expect(within(facts).getByText('Net weight (oz)')).toBeInTheDocument();
    expect(within(facts).getByText('6 oz')).toBeInTheDocument();
    const proof = within(sheet).getByRole('region', { name: 'Proof the seller attached' });
    expect(within(proof).getByRole('img', { name: 'Photo or PDF of the label: cashews-label.png' })).toBeInTheDocument();
    const pdf = within(proof).getByRole('link', { name: /supplier-spec.pdf/ });
    expect(pdf).toHaveAttribute('target', '_blank');
    expect(pdf).toHaveAttribute('rel', expect.stringContaining('noopener'));
    const proposed = within(sheet).getByRole('region', { name: 'Proposed decision' });
    expect(within(proposed).getByText(/net weight is at least 2.5 oz/)).toBeInTheDocument();
    expect(within(sheet).getByText('Why it needs review')).toBeInTheDocument();
  });

  it('approves only with a reason of ten characters, then lists the decision', async () => {
    const user = userEvent.setup();
    nav.set('/fees?tab=tax-review&variant=AT20000101');
    await renderScreen(<TaxReviewPanel />);
    const sheet = await screen.findByRole('dialog', { name: 'Roasted cashews, 6 oz bag' });
    await user.click(await within(sheet).findByRole('button', { name: 'Approve…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Approve the tax for Roasted cashews, 6 oz bag?' });
    const approve = within(dialog).getByRole('button', { name: 'Approve' });
    expect(approve).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Reason'), 'Label ok');
    expect(within(dialog).getByText(/Add a few more words/)).toBeInTheDocument();
    expect(approve).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Reason'), ' and weight match');
    expect(approve).toBeEnabled();
    await user.click(approve);
    expect(await screen.findByText('Roasted cashews, 6 oz bag is approved.')).toBeInTheDocument();
    await waitFor(() => expect(within(sheet).getByText('Approved', { selector: 'span' })).toBeInTheDocument());
    expect(within(sheet).queryByRole('button', { name: 'Approve…' })).not.toBeInTheDocument();
    expect(within(sheet).getByRole('region', { name: 'Decision' })).toHaveTextContent('Decided by');
  });

  it('says in plain words why a variant cannot be approved yet', async () => {
    const user = userEvent.setup();
    nav.set('/fees?tab=tax-review&variant=AT20000102');
    await renderScreen(<TaxReviewPanel />);
    const sheet = await screen.findByRole('dialog', { name: 'Yoruba for beginners (e-book)' });
    await user.click(await within(sheet).findByRole('button', { name: 'Approve…' }));
    const dialog = await screen.findByRole('dialog', { name: /Approve the tax for/ });
    await user.type(within(dialog).getByLabelText('Reason'), 'Looks right to me');
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));
    expect(await within(dialog).findByText(/can’t be approved yet/)).toBeInTheDocument();
    expect(within(dialog).getByRole('alert')).toBeInTheDocument();
  });

  it('moves a variant to another category with a reason, and says what happens to orders', async () => {
    const user = userEvent.setup();
    nav.set('/fees?tab=tax-review&variant=AT20000103');
    await renderScreen(<TaxReviewPanel />);
    const sheet = await screen.findByRole('dialog', { name: 'Hand-woven Kente table runner' });
    expect(await within(sheet).findByText(/3 orders include this product/)).toBeInTheDocument();
    await user.click(within(sheet).getByRole('button', { name: 'Move to another category…' }));
    const dialog = await screen.findByRole('dialog', { name: /Move Hand-woven Kente table runner/ });
    expect(within(dialog).getByText(/3 orders include this product/)).toBeInTheDocument();
    expect(within(dialog).getByText(/Placed orders keep the tax they were charged/)).toBeInTheDocument();
    const move = within(dialog).getByRole('button', { name: 'Move to category' });
    await user.type(within(dialog).getByLabelText('Reason'), 'It is a woven textile, not a craft kit');
    expect(move).toBeDisabled(); // no category chosen yet
    await user.selectOptions(await within(dialog).findByLabelText('New category'), 'bedding-linens');
    expect(move).toBeEnabled();
    await user.click(move);
    expect(await screen.findByText(/moved to another category and is back in review/)).toBeInTheDocument();
  });

  it('asks the seller for more, and keeps the dialog open with the reason when the tax service cannot take it', async () => {
    const user = userEvent.setup();
    interceptStaff(async (req, next) => {
      if (new URL(req.url).pathname.endsWith('/request-information'))
        return new Response(JSON.stringify({ status: 503, code: 'UPSTREAM_UNAVAILABLE', title: 'Service unavailable' }), { status: 503, headers: { 'content-type': 'application/problem+json' } });
      return next(req);
    });
    nav.set('/fees?tab=tax-review&variant=AT20000101');
    await renderScreen(<TaxReviewPanel />);
    const sheet = await screen.findByRole('dialog', { name: 'Roasted cashews, 6 oz bag' });
    await user.click(await within(sheet).findByRole('button', { name: 'Ask the seller for more…' }));
    const dialog = await screen.findByRole('dialog', { name: /Ask the seller of Roasted cashews/ });
    await user.type(within(dialog).getByLabelText('What do you need from the seller?'), 'Please upload a clearer label photo');
    await user.click(within(dialog).getByRole('button', { name: 'Send to the seller' }));
    expect(await within(dialog).findByText(/The tax service can’t take that yet. Nothing was saved/)).toBeInTheDocument();
    expect(within(dialog).getByLabelText('What do you need from the seller?')).toHaveValue('Please upload a clearer label photo');
  });

  it('is read only without tax:classify-admin', async () => {
    asRoles('platform.auditor');
    nav.set('/fees?tab=tax-review&variant=AT20000101');
    await renderScreen(<TaxReviewPanel />);
    const sheet = await screen.findByRole('dialog', { name: 'Roasted cashews, 6 oz bag' });
    expect(await within(sheet).findByText(/Deciding needs the tax:classify-admin permission/)).toBeInTheDocument();
    expect(within(sheet).queryByRole('button', { name: 'Approve…' })).not.toBeInTheDocument();
  });

  it('has an Audit sample tab of live items the system approved, by name and with what to do', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxReviewPanel />);
    await user.click(await screen.findByRole('button', { name: 'Audit sample' }));
    const table = await screen.findByRole('table', { name: 'Tax audit sample' });
    const row = (await within(table).findByText('Shea butter body cream, 8 oz')).closest('tr') as HTMLElement;
    expect(within(row).getByText('Accra Crafts Collective')).toBeInTheDocument();
    expect(within(row).getByText('Skincare & cosmetics')).toBeInTheDocument();
    expect(within(row).getByText('Automatic approval (risk-based rule)')).toBeInTheDocument();
    expect(within(row).getByText(/confirm it or move it to the right category/)).toBeInTheDocument();
    expect(table).not.toHaveTextContent(/org-|AT2000|auto$/);
    // The audit item is live, so it is not in the queue.
    await user.click(screen.getByRole('button', { name: 'Waiting for review' }));
    const queue = await screen.findByRole('table', { name: 'Tax review queue' });
    await within(queue).findByText('Roasted cashews, 6 oz bag');
    expect(within(queue).queryByText('Shea butter body cream, 8 oz')).not.toBeInTheDocument();
  });

  it('confirms an audit item with a reason and takes it out of the sample', async () => {
    const user = userEvent.setup();
    nav.set('/fees?tab=tax-review&review.view=audit&variant=AT20000109');
    await renderScreen(<TaxReviewPanel />);
    const sheet = await screen.findByRole('dialog', { name: 'Shea butter body cream, 8 oz' });
    expect(await within(sheet).findByText('Why it is in the audit sample')).toBeInTheDocument();
    expect(within(sheet).queryByRole('button', { name: 'Approve…' })).not.toBeInTheDocument();
    expect(within(sheet).getByText(/Put in the audit sample/)).toBeInTheDocument();
    await user.click(within(sheet).getByRole('button', { name: 'Confirm…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Confirm the tax for Shea butter body cream, 8 oz?' });
    const confirm = within(dialog).getByRole('button', { name: 'Confirm' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Reason'), 'Label and category checked');
    await user.click(confirm);
    expect(await screen.findByText('Shea butter body cream, 8 oz is confirmed and leaves the audit sample.')).toBeInTheDocument();
    expect(await within(sheet).findByText(/Audit sample confirmed/)).toBeInTheDocument();
    expect(within(sheet).queryByRole('button', { name: 'Confirm…' })).not.toBeInTheDocument();
  });

  it('moves an audit item to another category, which takes it out of the sample and back into review', async () => {
    const user = userEvent.setup();
    nav.set('/fees?tab=tax-review&review.view=audit&variant=AT20000109');
    await renderScreen(<TaxReviewPanel />);
    const sheet = await screen.findByRole('dialog', { name: 'Shea butter body cream, 8 oz' });
    await user.click(await within(sheet).findByRole('button', { name: 'Move to another category…' }));
    const dialog = await screen.findByRole('dialog', { name: /Move Shea butter body cream/ });
    expect(within(dialog).getByText(/stops being live until it is approved again/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Reason'), 'This is a medicated balm');
    await user.selectOptions(await within(dialog).findByLabelText('New category'), 'medicated-skincare');
    await user.click(within(dialog).getByRole('button', { name: 'Move to category' }));
    expect(await screen.findByText(/moved to another category and is back in review/)).toBeInTheDocument();
    await user.click(within(sheet).getByRole('button', { name: 'Close' }));
  });

  it('cannot confirm an audit item without tax:classify-admin', async () => {
    asRoles('platform.auditor');
    nav.set('/fees?tab=tax-review&review.view=audit&variant=AT20000109');
    await renderScreen(<TaxReviewPanel />);
    const sheet = await screen.findByRole('dialog', { name: 'Shea butter body cream, 8 oz' });
    expect(await within(sheet).findByText(/Deciding needs the tax:classify-admin permission/)).toBeInTheDocument();
    expect(within(sheet).queryByRole('button', { name: 'Confirm…' })).not.toBeInTheDocument();
  });

  it('shows an empty queue as a sentence, and an unavailable service with a retry', async () => {
    const user = userEvent.setup();
    let mode: 'empty' | 'down' | 'ok' = 'empty';
    interceptStaff(async (req, next) => {
      if (new URL(req.url).pathname.endsWith('/v1/staff/tax/reviews')) {
        if (mode === 'empty') return new Response(JSON.stringify({ items: [], total: 0, page: 0, size: 25, status: 'PENDING_REVIEW', statuses: [], canDecide: true }), { status: 200, headers: { 'content-type': 'application/json' } });
        if (mode === 'down') return new Response(JSON.stringify({ status: 503, code: 'UPSTREAM_UNAVAILABLE', title: 'Service unavailable' }), { status: 503, headers: { 'content-type': 'application/problem+json' } });
      }
      return next(req);
    });
    await renderScreen(<TaxReviewPanel />);
    expect(await screen.findByText('Nothing is waiting for tax review.')).toBeInTheDocument();
    mode = 'down';
    await user.click(screen.getByRole('button', { name: 'Decisions' }));
    expect(await screen.findByText('This part of AfroTransact is temporarily unavailable')).toBeInTheDocument();
    mode = 'ok';
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Jollof seasoning blend, 4 oz')).toBeInTheDocument();
  });
});
