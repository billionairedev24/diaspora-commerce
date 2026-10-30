import { beforeEach, describe, expect, it } from 'vitest';

import { contentPageHref, createStaffApiClient } from '../src';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

/* Settings → Published pages through the mock BFF: the index with absolute live links, drafts that change nothing
   public, the second approver for legal documents, publication with a reason, the history and rollback. */

const api = (roles?: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: roles ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}`, origin: 'http://staff.test' } : {},
    fetch: (req) => handleStaffMockRequest(req)
  }).content;

async function refused(call: Promise<unknown>): Promise<{ status: number; code?: string; errors: Record<string, string> }> {
  return call.then(
    () => {
      throw new Error('expected the call to be refused');
    },
    (e: { status: number; problem?: { code?: string; errors?: Record<string, string> } }) => ({ status: e.status, code: e.problem?.code, errors: e.problem?.errors ?? {} })
  );
}

beforeEach(() => resetStaffDb(new Date('2026-10-24T12:00:00Z')));

describe('the Published pages index', () => {
  it('lists every legal document and help page with an absolute live link, and the pages edited elsewhere', async () => {
    const list = await api().pages();
    expect(list.storefrontUrl).toBe('http://localhost:3000');
    const legal = list.items.filter((i) => i.kind === 'legal');
    expect(legal).toHaveLength(21);
    expect(list.items.filter((i) => i.kind === 'help').length).toBeGreaterThan(5);
    for (const row of list.items.filter((i) => i.liveUrl)) expect(row.liveUrl).toMatch(/^http:\/\/localhost:3000\//);
    expect(list.items.find((i) => i.key === 'privacy')).toMatchObject({ liveUrl: 'http://localhost:3000/legal/privacy', updatedByName: expect.any(String), editor: 'page', canEdit: true });
    expect(list.items.find((i) => i.key === 'returns')).toMatchObject({ hasDraft: true, draftUpdatedByName: 'Imani Okafor' });
    expect(list.items.find((i) => i.key === 'about')).toMatchObject({ editor: 'developer', status: 'DEVELOPER_ONLY', canEdit: false });
    expect(list.items.find((i) => i.key === 'site-settings')).toMatchObject({ editor: 'record', resource: 'site-settings', resourceId: 'site' });
    expect(contentPageHref('legal', 'privacy')).toBe('/settings/pages/legal/privacy');
  });

  it('is refused to roles without Site content access, and read-only for viewers', async () => {
    expect((await refused(api('platform.driver').pages())).status).toBe(403);
    const view = await api('platform.customer_support').pages();
    expect(view.items.every((i) => !i.canEdit)).toBe(true);
  });
});

describe('a help page', () => {
  it('saves a draft without changing what is public, then publishes it with a reason and keeps the history', async () => {
    const content = api();
    const before = await content.page('help', 'shipping');
    expect(before.draft).toBeNull();
    expect(before.versions).toHaveLength(1);
    const saved = await content.saveDraft('help', 'shipping', {
      title: 'Shipping & delivery',
      summary: before.published.summary ?? 'x',
      topic: 'ORDERS_DELIVERY',
      bodies: { en: '## Pickup\n\nCollect from a locker.' },
      changeSummary: 'Lockers'
    });
    expect(saved).toMatchObject({ rowVersion: 1, updatedByName: 'Ifeoma Okafor' });
    const mid = await content.page('help', 'shipping');
    expect(mid.published.bodies.en).toBe(before.published.bodies.en);
    expect(mid.draft?.bodies.en).toBe('## Pickup\n\nCollect from a locker.');

    expect((await refused(content.publish('help', 'shipping', { reason: 'no', rowVersion: 1 }))).errors.reason).toBeDefined();
    expect((await refused(content.publish('help', 'shipping', { reason: 'Lockers are live', rowVersion: 9 }))).status).toBe(409);
    const after = await content.publish('help', 'shipping', { reason: 'Lockers are live', rowVersion: 1 });
    expect(after.draft).toBeNull();
    expect(after.published.bodies.en).toBe('## Pickup\n\nCollect from a locker.');
    expect(after.versions.map((v) => [v.version, v.status])).toEqual([['2', 'CURRENT'], ['1', 'ARCHIVED']]);
    expect(after.versions[0]).toMatchObject({ reason: 'Lockers are live', publishedByName: 'Ifeoma Okafor' });
    expect((await content.version('help', 'shipping', '1')).bodies.en).toBe(before.published.bodies.en);

    const back = await content.rollback('help', 'shipping', { version: '1', reason: 'Lockers were paused' });
    expect(back.published.bodies.en).toBe(before.published.bodies.en);
    expect(back.versions[0]).toMatchObject({ version: '3', changeSummary: 'Restores revision 1' });
  });

  it('refuses repeated section names, links that go nowhere and text before the first heading', async () => {
    const content = api();
    const base = { title: 'T', summary: 'S', topic: 'ORDERS_DELIVERY' };
    expect((await refused(content.saveDraft('help', 'shipping', { ...base, bodies: { en: '## A\n\nx\n\n## a\n\ny' } }))).errors['bodies.en']).toMatch(/Two sections/);
    expect((await refused(content.saveDraft('help', 'shipping', { ...base, bodies: { en: '## A\n\n[x](javascript:alert(1))' } }))).errors['bodies.en']).toMatch(/does not go anywhere/);
    expect((await refused(content.saveDraft('help', 'shipping', { ...base, bodies: { en: 'No heading' } }))).errors['bodies.en']).toBeDefined();
    expect((await refused(content.saveDraft('help', 'shipping', { ...base, bodies: { en: '## A\n\nx', fr: '## A\n\nx' } }))).errors['bodies.fr']).toBeDefined();
  });

  it('discards a draft and refuses a stale save', async () => {
    const content = api();
    const input = { title: 'T', summary: 'S', topic: 'ORDERS_DELIVERY', bodies: { en: '## A\n\nx' } };
    await content.saveDraft('help', 'shipping', input);
    expect((await refused(content.saveDraft('help', 'shipping', input))).status).toBe(409);
    await content.saveDraft('help', 'shipping', { ...input, rowVersion: 1 });
    await content.discardDraft('help', 'shipping');
    expect((await content.page('help', 'shipping')).draft).toBeNull();
    expect((await refused(content.discardDraft('help', 'shipping'))).status).toBe(404);
  });
});

describe('a legal document', () => {
  it('needs a second approver: the editor of the draft cannot publish it, a colleague can', async () => {
    const content = api();
    const page = await content.page('legal', 'privacy');
    expect(page.locales).toEqual(['en', 'fr', 'es', 'ar', 'yo']);
    expect(page.placeholders.legal).toBe('legal@afrotransact.com');
    const saved = await content.saveDraft('legal', 'privacy', {
      effectiveDate: '2026-12-01',
      draftBanner: false,
      bodies: { ...page.published.bodies, en: `${page.published.bodies.en}\n\n## Order records\n\nWe keep orders for seven years.` },
      needsReview: ['fr'],
      changeSummary: 'Adds retention'
    });
    expect(saved).toMatchObject({ rowVersion: 1, needsReview: ['fr'], updatedByName: 'Ifeoma Okafor' });
    expect((await content.page('legal', 'privacy')).published.version).toBe(page.published.version);
    const own = await refused(content.publish('legal', 'privacy', { reason: 'Counsel approved', rowVersion: 1 }));
    expect(own.status).toBe(403);

    // The seeded draft of returns was written by a colleague: this person can approve it.
    const returns = await content.page('legal', 'returns');
    expect(returns.draft).toMatchObject({ changeSummary: 'Return window from 30 to 45 days', updatedByName: 'Imani Okafor' });
    const published = await content.publish('legal', 'returns', { reason: 'Counsel approved the 45 days', rowVersion: returns.draft?.rowVersion ?? 0 });
    expect(published.draft).toBeNull();
    expect(published.published.version).toBe(returns.draft?.version);
    expect(published.versions[1]).toMatchObject({ status: 'ARCHIVED' });
    expect(published.published.bodies.en).toContain('45 days');
  });

  it('needs content:legal.edit to change a legal document, though Marketing edits help pages', async () => {
    const marketing = api('platform.marketing');
    const legal = await marketing.page('legal', 'accessibility');
    expect(legal.access).toMatchObject({ canEdit: false, canPublish: false, secondApprover: true });
    expect((await refused(marketing.saveDraft('legal', 'accessibility', { bodies: { en: '# A\n\n## One\n\nx' }, changeSummary: 'x' }))).status).toBe(403);
    const help = await marketing.page('help', 'shipping');
    expect(help.access).toMatchObject({ canEdit: true, canPublish: true, secondApprover: false });
  });

  it('starts a rollback as a draft from the older text, once', async () => {
    const content = api();
    const history = (await content.page('legal', 'conditions-of-use')).versions;
    const old = history.find((v) => v.status === 'ARCHIVED');
    expect(old).toBeDefined();
    const rolled = await content.rollback('legal', 'conditions-of-use', { version: old?.version ?? '' });
    expect(rolled.draft?.changeSummary).toBe(`Restores version ${old?.version}`);
    expect((await refused(content.rollback('legal', 'conditions-of-use', { version: old?.version ?? '' }))).status).toBe(409);
    await content.discardDraft('legal', 'conditions-of-use');
  });

  it('serves a translation only where one exists', async () => {
    const privacy = await api().page('legal', 'privacy');
    expect(Object.keys(privacy.published.bodies)).toEqual(['en', 'fr']);
    expect((await refused(api().page('legal', 'nope'))).status).toBe(404);
    expect((await refused(api().page('faq' as 'legal', 'privacy'))).status).toBe(404);
  });
});

describe('other public content staff change themselves: open roles and the cookie table', () => {
  const client = () => createStaffApiClient({ baseUrl: 'http://staff.test/api/bff', headers: {}, fetch: (req) => handleStaffMockRequest(req) });

  it('opens a draft role and closes it again; the careers link opens on the storefront', async () => {
    const staff = client();
    const jobs = await staff.listResource('careers-jobs', { page: 0, size: 50 });
    const draft = jobs.rows.find((r) => String(r.cells.status).toLowerCase() === 'draft');
    expect(draft).toBeDefined();
    const id = draft?.id ?? '';
    const sheet = await staff.runAction('careers-jobs', id, 'open', {});
    expect(sheet.status).toBe('OPEN');
    expect(sheet.links[0]).toMatchObject({ label: 'Open the careers page', external: true, href: 'http://localhost:3000/careers' });
    const closed = await staff.runAction('careers-jobs', id, 'close', {});
    expect(closed.status).toBe('CLOSED');
    expect(closed.links).toEqual([]);
  });

  it('adds, edits and removes a cookie in the table of the cookie notice', async () => {
    const staff = client();
    const created = await staff.createRecord('cookie-definitions', { name: 'at_theme', provider: 'AfroTransact', purpose: 'Dark mode', duration: '1 year', category: 'functional' });
    expect(created.id).toBe('at_theme');
    expect(created.subtitle).toBe('AfroTransact · Functional');
    const edited = await staff.updateRecord('cookie-definitions', 'at_theme', { provider: 'AfroTransact', purpose: 'Dark mode and text size', duration: '6 months', category: 'functional' }, null);
    expect(edited.sections[0]?.fields.some((f) => f.value === '6 months')).toBe(true);
    const gone = await staff.runAction('cookie-definitions', 'at_theme', 'delete', {});
    expect(gone.removed).toBe(true);
    const list = await staff.listResource('cookie-definitions', { page: 0, size: 50 });
    expect(list.rows.some((r) => r.id === 'at_theme')).toBe(false);
  });
});
