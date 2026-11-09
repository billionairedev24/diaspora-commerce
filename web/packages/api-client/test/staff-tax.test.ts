import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';

import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import { ApiError, createStaffApiClient, reasonOk, staffTaxKeys, TAX_REASON_MIN, type TaxCodeEntry, type TaxCodeMap, type TaxCompleteness, type TaxProfilePage, type TaxReadiness } from '../src';
import { type components } from '../src/generated/gateway-bff-staff';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

/* The category tax tools: the Zod schemas stay aligned with the staff contract and the mock BFF behaves like the Go one
   (73 real categories, plain words, reasons of 10+ characters, blocked providers, the sign-off name). */

const NOW = new Date('2026-11-09T12:00:00Z');
const client = (roles = 'platform.super_admin') =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}`, origin: 'http://staff.test' },
    fetch: (req) => handleStaffMockRequest(req)
  });
type S = components['schemas'];

describe('contract alignment', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<TaxCompleteness>().toExtend<S['StaffTaxCompleteness']>();
    expectTypeOf<TaxReadiness>().toExtend<S['StaffTaxReadiness']>();
    expectTypeOf<TaxCodeEntry>().toExtend<S['StaffTaxCodeEntry']>();
    expectTypeOf<Pick<TaxCodeMap, 'provider' | 'total' | 'page' | 'size' | 'canEdit' | 'completeness'>>().toExtend<Pick<S['StaffTaxCodeMap'], 'provider' | 'total' | 'page' | 'size' | 'canEdit' | 'completeness'>>();
    expectTypeOf<Pick<TaxProfilePage, 'total' | 'page' | 'size' | 'canChange'>>().toExtend<Pick<S['StaffTaxProfilePage'], 'total' | 'page' | 'size' | 'canChange'>>();
  });

  it('has key factories per query', () => {
    expect(staffTaxKeys.profiles({ status: 'ACTIVE' })).toEqual(['staff', 'tax', 'profiles', { status: 'ACTIVE' }]);
    expect(staffTaxKeys.review('AT1')).toEqual(['staff', 'tax', 'review', 'AT1']);
    expect(staffTaxKeys.method()).toEqual(['staff', 'tax', 'method']);
  });

  it('counts the reason like the server does', () => {
    expect(TAX_REASON_MIN).toBe(10);
    expect(reasonOk('123456789')).toBe(false);
    expect(reasonOk('  123456789  ')).toBe(false);
    expect(reasonOk('1234567890')).toBe(true);
  });

  it('keeps the mock fixture in step with tools/taxonomy', () => {
    // exits 1 (throws) when src/mocks/staff/fixtures/category-tax-map.json is stale
    expect(() => execFileSync('node', [resolve(__dirname, '../scripts/sync-tax-fixture.mjs'), '--check'], { stdio: 'pipe' })).not.toThrow();
  });
});

describe('profiles mock', () => {
  beforeEach(() => resetStaffDb(NOW));

  it('serves the 73 categories with plain status labels and counts', async () => {
    const page = await client().tax.profiles({ size: 100 });
    expect(page.total).toBe(73);
    expect(page.items).toHaveLength(73);
    expect(page.departments).toHaveLength(13);
    expect(Object.fromEntries(page.statuses.map((s) => [s.value, s.count]))).toEqual({ ACTIVE: 57, NEEDS_APPROVAL: 7, REFINE_CODE: 6, BLOCKED_BUNDLE: 1, CONFLICT_OPEN: 2 });
    page.items.forEach((r) => expect(r.statusLabel).not.toBe(r.status));
    expect(page.items.find((r) => r.categoryCode === 'nuts-seeds')).toMatchObject({ departmentName: 'Groceries & Food', statusLabel: 'Ready', scenarioCount: 3 });
  });

  it('filters by department and status and pages server-side', async () => {
    const api = client().tax;
    const digital = await api.profiles({ department: 'digital-products', status: 'NEEDS_APPROVAL' });
    expect(digital.items.length).toBeGreaterThan(0);
    digital.items.forEach((r) => expect([r.department, r.status]).toEqual(['digital-products', 'NEEDS_APPROVAL']));
    const second = await api.profiles({ size: 10, page: 1, sort: ['name,desc'] });
    expect(second.items).toHaveLength(10);
    expect(second.total).toBe(73);
    expect((await api.profiles({ q: 'txcd_40040016' })).items.length).toBeGreaterThan(0);
    await expect(api.profiles({ status: 'BOGUS' })).rejects.toMatchObject({ status: 422 });
  });

  it('reads the decision table as sentences and flags notes for the tax professional', async () => {
    const p = await client().tax.profile('nuts-seeds');
    const s007 = p.decisions.find((r) => r.id === 'S007');
    expect(s007?.sentence).toContain('net weight is at least 2.5 oz');
    expect(s007?.sentence).toContain('servings per package is more than 1');
    expect(s007?.sentence.endsWith(', then exempt')).toBe(true);
    expect(p.notes.some((n) => n.kind === 'RULE' && n.rowId === 'S007')).toBe(true);
    expect(p.facts.find((f) => f.id === 'is_candy')?.needed).toBe('Always needed');
    expect(p.facts.find((f) => f.id === 'net_weight_oz')?.needed).toMatch(/^Needed when /);
    expect(p.actions.map((a) => [a.key, a.available])).toEqual([['approve', false], ['reopen', true]]);
  });

  it('needs a reason of 10 characters and the sign-off name to lift a gate', async () => {
    const api = client().tax;
    const path = 'downloadable-ebooks';
    await expect(api.changeProfileStatus(path, { status: 'ACTIVE', reason: 'ok', approvedBy: 'x', decisionRef: 'y' })).rejects.toMatchObject({ status: 422, fieldErrors: { reason: expect.stringContaining('at least 10') } });
    await expect(api.changeProfileStatus(path, { status: 'ACTIVE', reason: 'Opinion letter received' })).rejects.toMatchObject({ fieldErrors: { approvedBy: expect.any(String), decisionRef: expect.any(String) } });
    expect((await api.profile(path)).status).toBe('NEEDS_APPROVAL');
    const done = await api.changeProfileStatus(path, { status: 'ACTIVE', reason: 'Opinion letter received', approvedBy: 'Dana Whitfield, CPA', decisionRef: 'OD-4 letter' });
    expect(done).toMatchObject({ status: 'ACTIVE', statusLabel: 'Ready', changedByName: expect.any(String) });
    expect(done.history[0]).toMatchObject({ approvedBy: 'Dana Whitfield, CPA', decisionRef: 'OD-4 letter', reason: 'Opinion letter received' });
    // Reopening needs only a reason.
    expect((await api.changeProfileStatus(path, { status: 'NEEDS_APPROVAL', reason: 'Rule wording changed' })).status).toBe('NEEDS_APPROVAL');
  });

  it('keeps REFINE_CODE read-only and refuses without tax:classify-admin', async () => {
    const api = client().tax;
    const refine = (await api.profiles({ status: 'REFINE_CODE' })).items[0]!;
    const sheet = await api.profile(refine.categoryCode);
    expect(sheet.readOnlyReason).toMatch(/product by product/);
    await expect(api.changeProfileStatus(refine.categoryCode, { status: 'ACTIVE', reason: 'Trying to lift it', approvedBy: 'x', decisionRef: 'y' })).rejects.toMatchObject({ status: 409 });
    const auditor = client('platform.auditor').tax;
    expect((await auditor.profile('nuts-seeds')).canChange).toBe(false);
    await expect(auditor.changeProfileStatus('nuts-seeds', { status: 'NEEDS_APPROVAL', reason: 'Not allowed to do this' })).rejects.toMatchObject({ status: 403 });
    await expect(client('platform.catalog_manager').tax.profiles({})).rejects.toBeInstanceOf(ApiError);
  });
});

describe('review queue mock', () => {
  beforeEach(() => resetStaffDb(NOW));

  it('lists variants waiting for review with sellers by name and why each needs a person', async () => {
    const q = await client().tax.reviews({});
    expect(q.status).toBe('PENDING_REVIEW');
    expect(q.items.length).toBeGreaterThanOrEqual(4);
    const nuts = q.items.find((r) => r.atin === 'AT20000101')!;
    expect(nuts).toMatchObject({ title: 'Roasted cashews, 6 oz bag', sellers: ['Mama Nkechi’s Kitchen'], categoryName: 'Nuts & seeds', proposed: { outcomeLabel: 'Exempt (no tax)' } });
    expect(nuts.why).toMatch(/exemption/);
    expect(q.items.every((r) => r.sellers.every((s) => !/^org/.test(s)))).toBe(true);
  });

  it('shows facts as entered, evidence with thumbnails and the proposed decision in words', async () => {
    const r = await client().tax.review('AT20000101');
    expect(r.facts.find((f) => f.label === 'Net weight (oz)')?.value).toBe('6 oz');
    expect(r.evidence.map((e) => e.isImage)).toEqual([true, false]);
    expect(r.proposed.because).toContain('then exempt');
    expect(r.canApprove).toBe(true);
    expect(r.audit[0]).toMatchObject({ actionLabel: 'Submitted for review' });
  });

  it('approves with a reason, lists the decision with who and when, and refuses what is not decidable', async () => {
    const api = client().tax;
    await expect(api.approve('AT20000101', { reason: 'short' })).rejects.toMatchObject({ status: 422 });
    await expect(api.approve('AT20000102', { reason: 'Looks right to me' })).rejects.toMatchObject({ status: 422, message: expect.stringContaining('can’t be approved yet') });
    const done = await api.approve('AT20000101', { reason: 'Label and weight match the listing' });
    expect(done.status).toBe('APPROVED');
    const decisions = await api.reviews({ status: 'APPROVED' });
    const mine = decisions.items.find((r) => r.atin === 'AT20000101');
    expect(mine).toMatchObject({ reviewedByName: expect.any(String), ruleVersion: expect.stringMatching(/^tx-/) });
    expect(mine?.reviewedAt).not.toBe('');
  });

  it('reclassifies with a category and reason, and asks for more information', async () => {
    const api = client().tax;
    await expect(api.reclassify('AT20000102', { categoryCode: '', reason: 'It is really a printed book' })).rejects.toMatchObject({ status: 422 });
    await expect(api.reclassify('AT20000102', { categoryCode: 'nuts-seeds', reason: 'short' })).rejects.toMatchObject({ status: 422 });
    expect((await api.reclassify('AT20000102', { categoryCode: 'nuts-seeds', reason: 'It is really a snack box' })).categoryCode).toBe('nuts-seeds');
    await expect(api.requestInformation('AT20000101', { message: 'no' })).rejects.toMatchObject({ status: 422 });
    expect((await api.requestInformation('AT20000101', { message: 'Please upload a clearer label photo' })).status).toBe('REJECTED');
    await expect(api.review('NOPE')).rejects.toMatchObject({ status: 404 });
  });
});

describe('provider code maps mock', () => {
  beforeEach(() => resetStaffDb(NOW));

  it('counts what still needs a verified code (blocked outcomes need none)', async () => {
    const stripe = await client().tax.codeMap({ provider: 'STRIPE', size: 200 });
    expect(stripe.completeness).toMatchObject({ needed: 104, unmapped: 0, complete: false });
    expect(stripe.completeness.unverified).toBeGreaterThan(0);
    const avalara = await client().tax.codeMap({ provider: 'AVALARA', size: 5 });
    expect(avalara.completeness).toMatchObject({ unmapped: 104, percent: 0 });
    expect(avalara.items).toHaveLength(5);
    expect((await client().tax.codeMap({ provider: 'AVALARA', state: 'UNMAPPED', size: 200 })).total).toBe(104);
  });

  it('edits one code with a reason and validates the provider format', async () => {
    const api = client().tax;
    const code = 'AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS';
    await expect(api.updateCode(code, { provider: 'STRIPE', providerCode: 'txcd_40040016', verified: true, reason: 'short' })).rejects.toMatchObject({ status: 422 });
    await expect(api.updateCode(code, { provider: 'STRIPE', providerCode: '40040016', verified: true, reason: 'Checked against the list' })).rejects.toMatchObject({ fieldErrors: { providerCode: expect.any(String) } });
    await expect(api.updateCode(code, { provider: 'STRIPE', providerCode: '', verified: true, reason: 'Checked against the list' })).rejects.toMatchObject({ fieldErrors: { verified: expect.any(String) } });
    const ok = await api.updateCode(code, { provider: 'AVALARA', providerCode: 'PF050099', verified: false, reason: 'Avalara code from their list' });
    expect(ok).toMatchObject({ state: 'UNVERIFIED', providerCode: 'PF050099' });
    expect((await api.updateCode(code, { provider: 'AVALARA', providerCode: 'PF050099', verified: true, reason: 'Matches Avalara guidance' })).state).toBe('VERIFIED');
  });

  it('exports, previews and imports CSV, and never half applies a file', async () => {
    const api = client().tax;
    const exp = await api.exportCodeMap('STRIPE');
    expect(exp.csv.split('\n')[0]).toBe('internal_tax_code,category,outcome,provider,provider_code,verified');
    expect(exp.rows).toBe(105);
    const csv = 'internal_tax_code,provider_code,verified\nAT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS,PF050099,true\n';
    const dry = await api.importCodeMap({ provider: 'AVALARA', csv, dryRun: true });
    expect(dry).toMatchObject({ dryRun: true, changed: 1, applied: 0 });
    expect((await api.codeMap({ provider: 'AVALARA', q: 'PF050099' })).total).toBe(0);
    await expect(api.importCodeMap({ provider: 'AVALARA', csv })).rejects.toMatchObject({ status: 422 });
    const bad = await api.importCodeMap({ provider: 'AVALARA', csv: `${csv}AT.NO.SUCH_CODE,PF050099,false\n`, reason: 'Import of the checked list' });
    expect(bad).toMatchObject({ applied: 0, blocked: true });
    expect(bad.problems).toHaveLength(1);
    expect(await api.importCodeMap({ provider: 'AVALARA', csv, reason: 'Import of the checked list' })).toMatchObject({ applied: 1, problems: [] });
    expect((await api.codeMap({ provider: 'AVALARA', q: 'PF050099' })).total).toBe(1);
  });
});

describe('method and shadow mock', () => {
  beforeEach(() => resetStaffDb(NOW));

  it('explains each method in plain words and blocks what is not set up or not verified', async () => {
    const m = await client().tax.method();
    expect(m).toMatchObject({ method: 'RULES', methodLabel: 'Our own rules', shadowProvider: 'STRIPE', runbookPath: 'docs/runbooks/tax-provider-cutover.md' });
    const by = Object.fromEntries(m.options.map((o) => [o.method, o]));
    expect(by.RULES).toMatchObject({ available: true, current: true });
    expect(by.AVALARA?.blockedReason).toMatch(/isn’t set up/);
    expect(by.STRIPE_TAX?.blockedReason).toMatch(/Finish the Provider codes/);
    m.options.forEach((o) => expect(o.explain.length).toBeGreaterThan(60));
  });

  it('refuses a short reason, an unavailable provider and a shadow equal to the charging provider', async () => {
    const api = client().tax;
    await expect(api.changeMethod({ method: 'RULES', shadowProvider: '', reason: 'short' })).rejects.toMatchObject({ status: 422 });
    await expect(api.changeMethod({ method: 'AVALARA', shadowProvider: '', reason: 'A reason that is long enough' })).rejects.toMatchObject({ fieldErrors: { method: expect.stringMatching(/isn’t set up/) } });
    await expect(api.changeMethod({ method: 'STRIPE_TAX', shadowProvider: '', reason: 'A reason that is long enough' })).rejects.toMatchObject({ fieldErrors: { method: expect.stringMatching(/not verified/) } });
    await expect(api.changeMethod({ method: 'RULES', shadowProvider: 'AVALARA', reason: 'A reason that is long enough' })).rejects.toMatchObject({ fieldErrors: { shadowProvider: expect.any(String) } });
    const off = await api.changeMethod({ method: 'RULES', shadowProvider: '', reason: 'Comparison finished for now' });
    expect(off).toMatchObject({ shadowLabel: 'Off', changedByName: expect.any(String) });
    expect(off.history[0]).toMatchObject({ reason: 'Comparison finished for now', fromLabel: 'Our own rules', toLabel: 'Our own rules' });
    await expect(client('platform.finance').tax.changeMethod({ method: 'RULES', shadowProvider: '', reason: 'Finance may configure tax' })).resolves.toBeTruthy();
    await expect(client('platform.auditor').tax.changeMethod({ method: 'RULES', shadowProvider: '', reason: 'Auditors read only' })).rejects.toMatchObject({ status: 403 });
  });

  it('reports disagreement by category, kind and state with a ready-to-cut-over summary', async () => {
    const api = client().tax;
    const r = await api.shadow({ size: 2 });
    expect(r).toMatchObject({ provider: 'STRIPE', shadowOff: false, linesCompared: 117 });
    expect(r.items).toHaveLength(2);
    expect(r.items[0]).toMatchObject({ orderNumber: expect.stringMatching(/^AT-/), inHouseText: expect.any(String), providerText: expect.any(String) });
    expect(r.byCategory.length).toBeGreaterThan(1);
    expect(r.byState[0]?.key).toBe('TX');
    expect(r.readiness).toMatchObject({ ready: false, thresholdSource: 'DEFAULT' });
    expect(r.readiness?.checks.find((c) => c.key === 'outcomes')?.ok).toBe(false);
    await api.changeMethod({ method: 'RULES', shadowProvider: '', reason: 'Comparison finished for now' });
    expect(await api.shadow({})).toMatchObject({ shadowOff: true, message: expect.stringMatching(/Shadow mode is off/) });
    await expect(api.shadow({ from: '2026-11-09', to: '2026-01-01' })).rejects.toMatchObject({ status: 422 });
  });
});
