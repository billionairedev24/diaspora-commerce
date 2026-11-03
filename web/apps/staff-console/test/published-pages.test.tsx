import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { ContentPageEditor } from '@/components/pages/ContentPageEditor';
import { PublishedPagesView } from '@/components/pages/PublishedPagesView';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/* Settings → Published pages against the mock BFF: the index with absolute live links, the preview in each language and
   width, the editor (a form with no change cannot be saved, a live side-by-side preview), publishing with a reason, the
   second approver of a legal document, the history with a diff, and rollback. */

beforeEach(() => {
  resetStaffDb(new Date('2026-10-24T12:00:00Z'));
  asRoles(null);
  nav.set('/settings/pages/legal/privacy');
});

const mode = (m: string) => nav.set(`${nav.pathname}?mode=${m}`);

describe('the index', () => {
  it('lists every page with an absolute live link that opens in a new tab, who changed it and when', async () => {
    await renderScreen(<PublishedPagesView />);
    const table = await screen.findByRole('table', { name: 'Published pages' });
    const row = (await within(table).findAllByTestId('page-row')).find((r) => within(r).queryByText('Privacy Notice'));
    expect(row).toBeDefined();
    const live = within(row as HTMLElement).getByRole('link', { name: /\/legal\/privacy/ });
    expect(live).toHaveAttribute('href', 'http://localhost:3000/legal/privacy');
    expect(live).toHaveAttribute('target', '_blank');
    expect(live).toHaveAttribute('rel', expect.stringContaining('noreferrer'));
    expect(within(row as HTMLElement).getByText('Imani Okafor')).toBeInTheDocument();
    for (const a of table.querySelectorAll<HTMLAnchorElement>('a[target="_blank"]')) expect(a.href).toMatch(/^http:\/\/localhost:3000\//);
  });

  it('shows a colleague’s waiting draft, the pages edited elsewhere, and the pages only a developer changes', async () => {
    await renderScreen(<PublishedPagesView />);
    const table = await screen.findByRole('table', { name: 'Published pages' });
    const returns = (await within(table).findAllByTestId('page-row')).find((r) => within(r).queryByText('Returns & Refunds'));
    expect(within(returns as HTMLElement).getByText('Draft by Imani Okafor')).toBeInTheDocument();
    expect(within(table).getByText('Contacts & social links')).toBeInTheDocument();
    const developer = within(table).getByText('Developer only', { selector: 'span.rounded-pill' }).closest('tr') as HTMLElement;
    expect(within(developer).getByText('About AfroTransact')).toBeInTheDocument();
    expect(within(developer).getByText('Changed by a developer')).toBeInTheDocument();
    expect(within(developer).queryByRole('button')).toBeNull();
  });

  it('previews a page in a sheet with the language and width switchers, and falls back to English with a notice', async () => {
    const user = userEvent.setup();
    await renderScreen(<PublishedPagesView />);
    await user.click(await screen.findByRole('button', { name: 'Preview Privacy Notice' }));
    const sheet = await screen.findByRole('dialog');
    const stage = await within(sheet).findByTestId('preview-stage');
    expect(within(stage).getByRole('heading', { level: 1, name: 'Privacy Notice' })).toBeInTheDocument();
    await user.click(within(sheet).getByRole('button', { name: 'Français' }));
    expect(await within(sheet).findByText('Les données que nous collectons')).toBeInTheDocument();
    expect(within(sheet).queryByText(/Pas encore de traduction/)).toBeNull();
    await user.click(within(sheet).getByRole('button', { name: /Español/ }));
    expect(await within(sheet).findByText('No Español translation yet.')).toBeInTheDocument();
    expect(within(sheet).getByTestId('preview-stage')).toHaveAttribute('lang', 'es');
    await user.click(within(sheet).getByRole('button', { name: 'Phone' }));
    expect(within(sheet).getByTestId('preview-stage')).toHaveAttribute('data-device', 'phone');
    expect(within(sheet).getByTestId('preview-stage').style.maxWidth).toBe('390px');
  });
});

describe('a legal document page', () => {
  it('previews the published version, with the live link, the draft banner and the translated chrome', async () => {
    const user = userEvent.setup();
    await renderScreen(<ContentPageEditor kind="legal" pageKey="privacy" />);
    expect((await screen.findAllByRole('heading', { level: 1, name: 'Privacy Notice' })).length).toBe(2);
    const live = screen.getByRole('link', { name: /View live page/ });
    expect(live).toHaveAttribute('href', 'http://localhost:3000/legal/privacy');
    expect(live).toHaveAttribute('target', '_blank');
    const stage = await screen.findByTestId('preview-stage');
    expect(within(stage).getByRole('note', { name: '' })).toHaveTextContent('Draft for legal counsel review — not yet in force.');
    await user.click(screen.getByRole('button', { name: 'Français' }));
    expect(await screen.findByText('Projet soumis à relecture juridique — pas encore en vigueur.')).toBeInTheDocument();
    expect(screen.getByText('Sur cette page')).toBeInTheDocument();
  });

  it('highlights and counts [[LEGAL: …]] markers so they cannot slip into a final document', async () => {
    nav.set('/settings/pages/legal/accessibility');
    await renderScreen(<ContentPageEditor kind="legal" pageKey="accessibility" />);
    expect(await screen.findByText('Unresolved counsel markers: 1')).toBeInTheDocument();
    const mark = (await screen.findByTestId('preview-stage')).querySelector('mark[data-legal-marker]');
    expect(mark).toHaveTextContent('[[LEGAL: Q17 state the audit status and date]]');
  });

  it('cannot be saved until something changes, previews what is typed beside the editor, and checks it', async () => {
    const user = userEvent.setup();
    mode('edit');
    await renderScreen(<ContentPageEditor kind="legal" pageKey="privacy" />);
    const save = await screen.findByRole('button', { name: 'Save draft' });
    expect(save).toBeDisabled();
    expect(screen.getByText('No changes yet.')).toBeInTheDocument();
    const body = screen.getByRole('textbox', { name: /Text in English/ });
    fireEvent.change(body, { target: { value: `${(body as HTMLTextAreaElement).value}\n\n## Brand new section\n\nWe keep it simple.` } });
    expect(await screen.findByText('We keep it simple.')).toBeInTheDocument();
    expect(save).toBeEnabled();
    expect(screen.getByText('You have unsaved changes.')).toBeInTheDocument();
    // A repeated section name blocks the save and names the line.
    fireEvent.change(body, { target: { value: `${(body as HTMLTextAreaElement).value}\n\n## Brand new section\n\nAgain.` } });
    const checks = await screen.findByTestId('content-checks');
    expect(within(checks).getByText(/Two sections are called “Brand new section”/)).toBeInTheDocument();
    expect(save).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Undo my changes' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled());
  });

  it('saves a draft that changes nothing public, then a colleague’s approval is needed: the last editor cannot publish', async () => {
    const user = userEvent.setup();
    mode('edit');
    await renderScreen(<ContentPageEditor kind="legal" pageKey="privacy" />);
    const body = await screen.findByRole('textbox', { name: /Text in English/ });
    fireEvent.change(body, { target: { value: `${(body as HTMLTextAreaElement).value}\n\n## Brand new section\n\nWe keep it simple.` } });
    fireEvent.change(screen.getByRole('textbox', { name: /What changed/ }), { target: { value: 'Adds a section' } });
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByText('Draft saved. Nothing public has changed.')).toBeInTheDocument();
    // Publishing is for a second person: this one saved last.
    await user.click(screen.getByRole('button', { name: 'Publish…' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/You saved the last change to this draft/)).toBeInTheDocument();
    await user.type(within(dialog).getByRole('textbox', { name: /Why are you publishing/ }), 'Counsel agreed');
    expect(within(dialog).getByRole('button', { name: 'Publish' })).toBeDisabled();
  });

  it('lets this person approve and publish a colleague’s draft with a reason, then shows it live in the history', async () => {
    const user = userEvent.setup();
    nav.set('/settings/pages/legal/returns?mode=edit');
    await renderScreen(<ContentPageEditor kind="legal" pageKey="returns" />);
    expect(await screen.findByText('Imani Okafor has a draft waiting')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Publish…' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/Someone else saved this draft/)).toBeInTheDocument();
    const confirm = within(dialog).getByRole('button', { name: 'Publish' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByRole('textbox', { name: /Why are you publishing/ }), 'Counsel approved 45 days');
    await user.click(confirm);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(await screen.findByText('Published')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'History' }));
    const versions = await screen.findAllByTestId('content-version');
    expect(within(versions[0] as HTMLElement).getByText('Live')).toBeInTheDocument();
    expect(within(versions[0] as HTMLElement).getByText('Ifeoma Okafor')).toBeInTheDocument();
    expect(within(versions[0] as HTMLElement).getByText('Return window from 30 to 45 days')).toBeInTheDocument();
  });

  it('compares two versions line by line and starts a draft from an older one (rollback)', async () => {
    const user = userEvent.setup();
    nav.set('/settings/pages/legal/conditions-of-use?mode=history');
    await renderScreen(<ContentPageEditor kind="legal" pageKey="conditions-of-use" />);
    const diff = await screen.findByTestId('content-diff');
    expect(diff).toHaveTextContent(/Comparing v/);
    const versions = await screen.findAllByTestId('content-version');
    expect(versions.length).toBeGreaterThan(1);
    await user.click(within(versions[1] as HTMLElement).getByRole('button', { name: 'Roll back' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/a second person publishes it/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Start a draft from it' }));
    expect(await screen.findByText('Draft saved. Nothing public has changed.')).toBeInTheDocument();
  });

  it('is read only for support: preview and history, no editor', async () => {
    asRoles('platform.customer_support');
    mode('edit');
    await renderScreen(<ContentPageEditor kind="legal" pageKey="privacy" />);
    expect((await screen.findAllByText('Read only')).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: 'Save draft' })).toBeNull();
    expect(screen.getByRole('tab', { name: 'History' })).toBeInTheDocument();
  });
});

describe('a help page', () => {
  it('publishes the typed text with a reason in one step and records a revision; a past revision is restored from the history', async () => {
    const user = userEvent.setup();
    nav.set('/settings/pages/help/shipping?mode=edit');
    await renderScreen(<ContentPageEditor kind="help" pageKey="shipping" />);
    const body = await screen.findByRole('textbox', { name: /Page text/ });
    const original = (body as HTMLTextAreaElement).value;
    fireEvent.change(body, { target: { value: `${original}\n\n## Lockers\n\nCollect from a locker.` } });
    expect(await screen.findByText('Collect from a locker.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Publish…' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByText(/second person/)).toBeNull();
    await user.type(within(dialog).getByRole('textbox', { name: /Why are you publishing/ }), 'Lockers are live');
    await user.click(within(dialog).getByRole('button', { name: 'Publish' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await user.click(await screen.findByRole('tab', { name: 'History' }));
    const versions = await screen.findAllByTestId('content-version');
    expect(versions).toHaveLength(2);
    expect(within(versions[0] as HTMLElement).getByText('Reason: Lockers are live')).toBeInTheDocument();
    await user.click(within(versions[1] as HTMLElement).getByRole('button', { name: 'Roll back' }));
    const rollback = await screen.findByRole('dialog');
    await user.type(within(rollback).getByRole('textbox', { name: /Why are you going back/ }), 'Lockers paused');
    await user.click(within(rollback).getByRole('button', { name: 'Publish it again' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    // Going back publishes at once and returns to the preview of the (old) live text.
    expect(await screen.findByText('Published (version 3)')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'History' }));
    expect(await screen.findAllByTestId('content-version')).toHaveLength(3);
  });

  it('English only: no language switcher', async () => {
    nav.set('/settings/pages/help/shipping');
    await renderScreen(<ContentPageEditor kind="help" pageKey="shipping" />);
    expect(await screen.findByText('English only')).toBeInTheDocument();
    expect(screen.getByTestId('preview-stage')).toHaveAttribute('lang', 'en');
  });

  it('a page nobody knows is an error, not an empty editor', async () => {
    nav.set('/settings/pages/help/nope');
    await renderScreen(<ContentPageEditor kind="help" pageKey="nope" />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});
