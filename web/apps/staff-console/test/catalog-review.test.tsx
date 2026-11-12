import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { CatalogView } from '@/components/catalog/CatalogView';
import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/* Catalog > the review queues (S385 to S387, S390): the staff member reads names, not ids; opens the request, the photo or the
   no-barcode item; and decides with a reason of 3 or more characters. */

beforeEach(() => {
  resetStaffDb(new Date('2026-11-13T12:00:00Z'));
  asRoles(null);
});

describe('the Catalog tabs', () => {
  it('show the five queues to staff who may moderate the catalog', async () => {
    nav.set('/moderation?tab=change-requests');
    await renderScreen(<CatalogView />);
    for (const name of ['Change requests', 'Wrong matches', 'Duplicate pages', 'No-barcode items', 'Held images']) {
      expect(await screen.findByRole('tab', { name })).toBeInTheDocument();
    }
  });

  it('leave them out for staff without catalog:moderate', async () => {
    asRoles('platform.retail_buyer');
    nav.set('/moderation');
    await renderScreen(<CatalogView />);
    await screen.findAllByRole('tab');
    expect(screen.queryByRole('tab', { name: 'Change requests' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Held images' })).not.toBeInTheDocument();
  });
});

describe('change requests', () => {
  it('list the waiting requests oldest first with the seller by name and how long they have waited', async () => {
    nav.set('/moderation?tab=change-requests');
    await renderScreen(<ResourceTable resource="change-requests" urlPrefix="change-requests" statusFilter />);
    const row = (await screen.findByText('Ankara print tote')).closest('tr')!;
    expect(within(row).getByText('Kente House')).toBeInTheDocument();
    expect(within(row).getByText('2 days · overdue')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/\borg[-_]/);
  });

  it('are approved with a reason, and the sheet then shows who decided and why', async () => {
    const user = userEvent.setup();
    nav.set('/moderation?tab=change-requests&open=cr-1');
    await renderScreen(<ResourceTable resource="change-requests" urlPrefix="change-requests" statusFilter />);
    const sheet = await screen.findByRole('dialog');
    await user.click(await within(sheet).findByRole('button', { name: 'Approve' }));
    const dialog = await screen.findByRole('dialog', { name: 'Approve the change' });
    const reason = within(dialog).getByRole('textbox', { name: /Reason/ });
    await user.type(reason, 'ok');
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));
    expect((await within(dialog).findAllByText(/Add a few more words/)).length).toBeGreaterThan(0);
    await user.clear(reason);
    await user.type(reason, 'Matches the pack');
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Approve the change' })).not.toBeInTheDocument());
    expect(await screen.findByText('Matches the pack')).toBeInTheDocument();
  });
});

describe('held images', () => {
  it('open on the photo itself with what was found and the score, and can be released with a reason', async () => {
    const user = userEvent.setup();
    nav.set('/moderation?tab=held-images&open=img-1');
    await renderScreen(<ResourceTable resource="held-images" urlPrefix="held-images" />);
    const sheet = await screen.findByRole('dialog');
    const photo = await within(sheet).findByRole('img', { name: 'Held photo' });
    expect(photo.getAttribute('src')).toMatch(/^data:image\//);
    expect(within(sheet).getByText('Text, logo or watermark')).toBeInTheDocument();
    expect(within(sheet).getByText(/62% mismatch/)).toBeInTheDocument();
    expect(within(sheet).getByText(/5 minutes/)).toBeInTheDocument();
    await user.click(within(sheet).getByRole('button', { name: 'Release the photo' }));
    const dialog = await screen.findByRole('dialog', { name: 'Release the photo' });
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Packaging text only');
    await user.click(within(dialog).getByRole('button', { name: 'Release' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Release the photo' })).not.toBeInTheDocument());
  });
});

describe('no-barcode items', () => {
  it('show pages that may be the same item before staff decide', async () => {
    nav.set('/moderation?tab=gtin-exemptions&open=ex-1');
    await renderScreen(<ResourceTable resource="gtin-exemptions" urlPrefix="gtin-exemptions" statusFilter />);
    const sheet = await screen.findByRole('dialog');
    expect(await within(sheet).findByText('Pages that may be the same item')).toBeInTheDocument();
    expect(within(sheet).getByText('Hand carved teak stool')).toBeInTheDocument();
    expect(within(sheet).getByText('We are checking that this item has no barcode.')).toBeInTheDocument();
  });
});
