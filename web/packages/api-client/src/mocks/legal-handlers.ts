/* Mock BFF: Legal & privacy center (GET /v1/legal/{key}, cookie policy, consent records, privacy requests). Mirrors
   gateway-bff + content-service behaviour: unknown documents are 404, GPC / "Do not sell or share" force advertising
   off, privacy requests are validated with the storefront's wording and get a PR-###### reference. */
import { http } from 'msw';
import { z } from 'zod';

import { ConsentRequestSchema, isLegalDocumentKey, PRIVACY_REQUEST_TYPES } from '../schemas/legal';
import { US_STATES } from '../us-states';
import { SITE_CONFIG } from './fixtures/content';
import { COOKIE_CATEGORIES, COOKIE_TABLE, LEGAL_DOCUMENTS } from './fixtures/legal';
import { body, json, route } from './http-kit';
import { MockProblem } from './service';

const notFound = () => new MockProblem(404, 'NOT_FOUND', 'Not found', 'We couldn’t find that document.');

/** Fills {phone}, {privacy}, {legal}, {accessibility}, {address}, {smsProgramName} like content-service does. */
function fill(markdown: string): string {
  const c = SITE_CONFIG.contacts;
  const values: Record<string, string> = {
    phone: c.phone, support: 'help@afrotransact.com', privacy: c.privacyEmail, legal: c.legalEmail, accessibility: c.accessibilityEmail, address: c.address,
    smsProgramName: SITE_CONFIG.smsProgramName
  };
  return markdown.replace(/\{([A-Za-z][A-Za-z0-9]*)\}/g, (m, k: string) => values[k] ?? m);
}

const slug = (title: string) => title.toLowerCase().normalize('NFKD').replace(/[^\p{L}\p{N}\s-]/gu, '').trim().replace(/\s+/g, '-');

const noticeVersion = () => LEGAL_DOCUMENTS.find((d) => d.key === 'cookies')?.versions.find((v) => v.status === 'CURRENT');

let privacySeq = 100000;

const PrivacyRequestBody = z.object({
  type: z.string().optional(),
  email: z.string().optional(),
  state: z.string().optional(),
  details: z.string().optional(),
  locale: z.string().optional()
});

export const legalHandlers = [
  http.get('*/v1/legal', route(() => {
    const documents = LEGAL_DOCUMENTS.map((d) => {
      const v = d.versions.find((x) => x.status === 'CURRENT') ?? d.versions[0];
      return { key: d.key, title: d.title, version: v?.version ?? '1.0', effectiveDate: v?.effectiveDate ?? '2026-01-01', href: `/legal/${d.key}`, draftBanner: v?.draftBanner ?? true };
    });
    return json({ documents });
  })),
  http.get('*/v1/legal/:key', route((ctx, params) => {
    const key = params.key ?? '';
    const doc = isLegalDocumentKey(key) ? LEGAL_DOCUMENTS.find((d) => d.key === key) : undefined;
    if (!doc) throw notFound();
    const q = new URL(ctx.request.url).searchParams;
    const wanted = q.get('version');
    const v = wanted ? doc.versions.find((x) => x.version === wanted) : doc.versions.find((x) => x.status === 'CURRENT');
    if (!v) throw notFound();
    const body = fill(v.body);
    const outline = [...body.matchAll(/^## (.+)$/gm)].map((m) => ({ id: slug(m[1] ?? ''), title: m[1] ?? '' }));
    return json({
      key: doc.key, title: doc.title, version: v.version, effectiveDate: v.effectiveDate, status: v.status, draftBanner: v.draftBanner,
      locale: 'en', translationFallback: (q.get('locale') ?? 'en') !== 'en', body, outline,
      versions: doc.versions.map((x) => ({ version: x.version, effectiveDate: x.effectiveDate, status: x.status, changeSummary: x.changeSummary })),
      href: `/legal/${doc.key}`
    });
  })),
  http.get('*/v1/consent/cookie-policy', route(() => {
    const n = noticeVersion();
    return json({ noticeVersion: n?.version ?? '1.0', effectiveDate: n?.effectiveDate ?? '2026-01-01', noticeHref: '/legal/cookies', categories: COOKIE_CATEGORIES, cookies: COOKIE_TABLE });
  })),
  http.post('*/v1/consent/records', route(async (ctx) => {
    const input = await body(ctx.request, ConsentRequestSchema);
    const unknown = Object.keys(input.categories).filter((k) => !COOKIE_CATEGORIES.some((c) => c.key === k));
    if (unknown.length) {
      throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Unknown cookie category.', { errors: Object.fromEntries(unknown.map((k) => [`categories.${k}`, 'Unknown category'])) });
    }
    const gpc = input.gpc === true || ctx.request.headers.get('Sec-GPC') === '1';
    const optOut = input.saleOptOut === true || gpc;
    const categories = Object.fromEntries(COOKIE_CATEGORIES.map((c) => [c.key, c.required ? true : c.gpcOptOut && optOut ? false : input.categories[c.key] === true]));
    return json({ id: crypto.randomUUID(), categories, gpc, saleOptOut: optOut, policyVersion: noticeVersion()?.version ?? '1.0', recordedAt: ctx.now.toISOString() }, 201);
  })),
  http.post('*/v1/privacy/requests', route(async (ctx) => {
    const input = await body(ctx.request, PrivacyRequestBody);
    const type = (input.type ?? '').trim().toLowerCase();
    const email = (input.email ?? '').trim();
    const state = (input.state ?? '').trim().toUpperCase();
    const details = (input.details ?? '').trim();
    const errors: Record<string, string> = {};
    if (!(PRIVACY_REQUEST_TYPES as readonly string[]).includes(type)) errors.type = 'Choose what you would like to do';
    if (!/^[^\s@]+@[^\s@]+\.[a-z]{2,}$/i.test(email)) errors.email = 'Enter the email address on your account';
    if (!US_STATES.some(([code]) => code === state)) errors.state = 'Choose your state';
    if (details.length > 2000) errors.details = 'Use 2000 characters or fewer';
    if (Object.keys(errors).length) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { errors });
    privacySeq += 1;
    const due = new Date(ctx.now.getTime() + 45 * 86_400_000);
    return json({ reference: `PR-${String(privacySeq).padStart(6, '0')}`, type, status: 'RECEIVED', submittedAt: ctx.now.toISOString(), dueAt: due.toISOString() }, 201);
  }))
];
