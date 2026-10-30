/* Mock of Settings → Published pages (/v1/staff/content/**, content's legal and help authoring through the BFF): the
   index with absolute live links, the page as published, a draft that changes nothing public, any past version,
   publication with a reason (a second approver for legal documents) and rollback. Same rules as the real BFF. */
import { http } from 'msw';
import { z } from 'zod';

import { HELP_TOPICS } from '../../schemas/help';
import { legalGroupTitle } from '../../schemas/legal';
import {
  type ContentDocument,
  type ContentKind,
  type ContentPage,
  type ContentPageList,
  type ContentPageRow,
  type ContentVersionRef
} from '../../staff-content';
import { LEGAL_DOCUMENTS } from '../fixtures/legal';
import { allows, levelOf } from './model';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';

export const MOCK_STOREFRONT_URL = 'http://localhost:3000';

interface LegalVersion {
  version: string;
  effectiveDate: string;
  status: 'CURRENT' | 'ARCHIVED';
  draftBanner: boolean;
  bodies: Record<string, string>;
  changeSummary: string | null;
  publishedBy: string;
  publishedAt: string;
}

interface LegalDraft {
  version: string;
  effectiveDate: string;
  draftBanner: boolean;
  bodies: Record<string, string>;
  needsReview: string[];
  changeSummary: string | null;
  rowVersion: number;
  updatedBy: string;
  updatedAt: string;
}

interface HelpRevision {
  revision: number;
  title: string;
  summary: string;
  topic: string;
  sortOrder: number;
  body: string;
  changeSummary: string | null;
  reason: string | null;
  publishedBy: string | null;
  publishedAt: string;
}

interface HelpDraft {
  title: string;
  summary: string;
  topic: string;
  sortOrder: number;
  body: string;
  changeSummary: string | null;
  rowVersion: number;
  updatedBy: string;
  updatedAt: string;
}

export interface MockContentState {
  legal: Record<string, { title: string; versions: LegalVersion[]; draft: LegalDraft | null }>;
  help: Record<string, { status: 'PUBLISHED' | 'DRAFT'; revisions: HelpRevision[]; draft: HelpDraft | null }>;
}

const FRENCH_PRIVACY = `# Politique de confidentialité

## Les données que nous collectons

Nous collectons les informations que vous nous donnez (nom, adresse, téléphone) et celles liées à vos commandes. Écrivez-nous à {privacy}.

## Vos choix

Vous pouvez accéder à vos données, les corriger ou demander leur suppression depuis [vos choix de confidentialité](/legal/privacy-choices).`;

/** Seeds the staff authoring state from the same fixtures the public mock serves. */
export function seedContent(now: Date, helpPages: { slug: string; title: string; summary: string; topic: string; status: string; body: string; updatedAt: string }[]): MockContentState {
  const legal: MockContentState['legal'] = {};
  for (const d of LEGAL_DOCUMENTS) {
    legal[d.key] = {
      title: d.title,
      draft: null,
      versions: d.versions.map((v, i): LegalVersion => ({
        version: v.version,
        effectiveDate: v.effectiveDate,
        status: v.status,
        draftBanner: v.draftBanner,
        bodies: d.key === 'privacy' && v.status === 'CURRENT' ? { en: v.body, fr: FRENCH_PRIVACY } : { en: v.body },
        changeSummary: v.changeSummary,
        publishedBy: i === 0 ? 'Imani Okafor' : 'Sam Eze',
        publishedAt: new Date(now.getTime() - (i + 1) * 40 * 86_400_000).toISOString()
      }))
    };
  }
  // Documents still with counsel carry [[LEGAL: …]] markers where a decision is open, like the real first drafts.
  const accessibility = legal.accessibility?.versions.find((v) => v.status === 'CURRENT');
  if (accessibility?.bodies.en) {
    accessibility.bodies.en += '\n\n## Open points\n\nWe will state the audit date once it is booked. [[LEGAL: Q17 state the audit status and date]]';
  }
  // A colleague has a change to returns waiting for a second person to approve it.
  const returns = legal.returns;
  if (returns) {
    const current = returns.versions.find((v) => v.status === 'CURRENT') ?? returns.versions[0];
    if (current) {
      returns.draft = {
        version: bumpMinor(current.version),
        effectiveDate: new Date(now.getTime() + 14 * 86_400_000).toISOString().slice(0, 10),
        draftBanner: false,
        bodies: { en: current.bodies.en!.replace(/30 days/g, '45 days') },
        needsReview: [],
        changeSummary: 'Return window from 30 to 45 days',
        rowVersion: 1,
        updatedBy: 'Imani Okafor',
        updatedAt: new Date(now.getTime() - 3_600_000).toISOString()
      };
    }
  }
  const help: MockContentState['help'] = {};
  for (const a of helpPages) {
    help[a.slug] = {
      status: a.status === 'PUBLISHED' ? 'PUBLISHED' : 'DRAFT',
      draft: null,
      revisions: [
        { revision: 1, title: a.title, summary: a.summary, topic: a.topic, sortOrder: a.slug === 'help-center' ? 0 : 10, body: a.body, changeSummary: 'Initial content', reason: 'Initial load', publishedBy: null, publishedAt: a.updatedAt }
      ]
    };
  }
  return { legal, help };
}

function bumpMinor(version: string): string {
  const [major = '1', minor = '0'] = version.split('.');
  return `${major}.${Number(minor) + 1}`;
}

const TOPIC_TITLES: Record<string, string> = {
  ORDERS_DELIVERY: 'Orders & delivery',
  RETURNS_REFUNDS: 'Returns & refunds',
  ACCOUNT_PAYMENTS: 'Your account & payments',
  SELLING: 'Selling & advertising',
  COMPANY: 'About AfroTransact'
};

const PLACEHOLDERS: Record<string, string> = {
  phone: '+1 (833) 555-0199',
  phoneE164: '+18335550199',
  support: 'help@afrotransact.com',
  privacy: 'privacy@afrotransact.com',
  legal: 'legal@afrotransact.com',
  accessibility: 'accessibility@afrotransact.com',
  address: 'AfroTransact, Inc. · 1001 Texas Ave, Suite 1400 · Houston, TX 77002',
  smsProgramName: 'AfroTransact Alerts'
};

const DraftBody = z.object({
  version: z.string().optional(),
  effectiveDate: z.string().optional(),
  draftBanner: z.boolean().optional(),
  title: z.string().optional(),
  summary: z.string().optional(),
  topic: z.string().optional(),
  sortOrder: z.number().int().optional(),
  bodies: z.record(z.string(), z.string()),
  needsReview: z.array(z.string()).optional(),
  changeSummary: z.string().optional(),
  rowVersion: z.number().int().optional()
});
const PublishBody = z.object({ reason: z.string(), rowVersion: z.number().int() });
const RollbackBody = z.object({ version: z.string(), reason: z.string().optional() });

const LINK = /\[[^\]]*\]\(([^)\s]*)[^)]*\)/g;
const LOCALES = ['en', 'fr', 'es', 'ar', 'yo'];
const HEADING = /^## +(.+?) *$/gm;
const SEMVER = /^[0-9]{1,3}\.[0-9]{1,3}(\.[0-9]{1,3})?$/;
const cmp = (a: string, b: string): number => {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) if ((pa[i] ?? 0) !== (pb[i] ?? 0)) return (pa[i] ?? 0) - (pb[i] ?? 0);
  return 0;
};

export function contentPageHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, json, problem } = deps;
  const view = (ctx: PreorderCtx) => {
    if (!allows(ctx.roles, 'siteCfg')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Your roles can’t open site content & legal.');
  };
  const level = (ctx: PreorderCtx) => levelOf(ctx.roles, 'siteCfg');
  const canEdit = (ctx: PreorderCtx, kind: ContentKind) => {
    const lv = level(ctx);
    return ctx.permissions.includes(kind === 'legal' ? 'content:legal.edit' : 'content:site.edit') && (lv === 'EDIT' || lv === 'APPROVE' || lv === 'FULL');
  };
  const canPublish = (ctx: PreorderCtx, kind: ContentKind) => canEdit(ctx, kind) && (kind === 'help' || level(ctx) === 'APPROVE' || level(ctx) === 'FULL');
  const kindOf = (raw: string | undefined): ContentKind => {
    if (raw !== 'legal' && raw !== 'help') throw problem(404, 'NOT_FOUND', 'Not found', 'There is no such page.');
    return raw;
  };
  const edit = (ctx: PreorderCtx, kind: ContentKind) => {
    view(ctx);
    if (!canEdit(ctx, kind)) throw problem(403, 'FORBIDDEN', 'Forbidden', `This needs the ${kind === 'legal' ? 'content:legal.edit' : 'content:site.edit'} permission.`);
  };
  const live = (path: string | null) => (path ? `${MOCK_STOREFRONT_URL}${path}` : null);
  const helpPath = (slug: string) => (slug === 'help-center' ? '/help' : `/help/${slug}`);
  const audit = (ctx: PreorderCtx, action: string, entityId: string, summary: string) => ctx.db.audit.unshift({ at: ctx.now.toISOString(), actor: ctx.action.actor, action, summary, entityId });
  const topicTitle = (topic: string) => TOPIC_TITLES[topic] ?? topic;

  const problemsOf = (kind: ContentKind, b: z.infer<typeof DraftBody>): Record<string, string> => {
    const errs: Record<string, string> = {};
    if (!(b.bodies.en ?? '').trim()) errs['bodies.en'] = 'The English text is required: it is the binding version';
    for (const [loc, text] of Object.entries(b.bodies)) {
      if (!LOCALES.includes(loc)) errs[`bodies.${loc}`] = 'Locale must be one of en, fr, es, ar, yo';
      else if (kind === 'help' && loc !== 'en') errs[`bodies.${loc}`] = 'Help pages are written in English only';
      else {
        const seen = new Set<string>();
        for (const m of text.matchAll(HEADING)) {
          const h = (m[1] ?? '').trim().toLowerCase();
          if (seen.has(h)) {
            errs[`bodies.${loc}`] = `Two sections are called “${(m[1] ?? '').trim()}”. Give each its own heading.`;
            break;
          }
          seen.add(h);
        }
        for (const m of text.matchAll(LINK)) {
          const dest = m[1] ?? '';
          if (!/^(https?:\/\/|mailto:|tel:|\/(?!\/)|#)/i.test(dest) && !errs[`bodies.${loc}`]) errs[`bodies.${loc}`] = `The link “${dest}” does not go anywhere. Use https://, /path, #section, mailto: or tel:.`;
        }
      }
    }
    if (kind === 'help') {
      if (!(b.title ?? '').trim()) errs.title = 'Required';
      if (!(b.summary ?? '').trim()) errs.summary = 'Required';
      const en = b.bodies.en ?? '';
      const first = en.split('\n').find((l) => l.trim() !== '');
      if (en.trim() && !/^##\s/.test(first ?? '')) errs['bodies.en'] = 'Start each section with a “## Heading” line';
    }
    return errs;
  };

  const legalDoc = (v: LegalVersion): ContentDocument => ({
    version: v.version, effectiveDate: v.effectiveDate, draftBanner: v.draftBanner, title: '', summary: null, topic: null, sortOrder: null, bodies: { ...v.bodies },
    needsReview: [], changeSummary: v.changeSummary, rowVersion: null, updatedAt: v.publishedAt, updatedByName: v.publishedBy, reason: null
  });
  const legalDraftDoc = (d: LegalDraft, title: string): ContentDocument => ({
    version: d.version, effectiveDate: d.effectiveDate, draftBanner: d.draftBanner, title, summary: null, topic: null, sortOrder: null, bodies: { ...d.bodies },
    needsReview: [...d.needsReview], changeSummary: d.changeSummary, rowVersion: d.rowVersion, updatedAt: d.updatedAt, updatedByName: d.updatedBy, reason: null
  });
  const helpDoc = (r: HelpRevision | HelpDraft, version: string): ContentDocument => ({
    version, effectiveDate: null, draftBanner: null, title: r.title, summary: r.summary, topic: r.topic, sortOrder: r.sortOrder, bodies: { en: r.body }, needsReview: [],
    changeSummary: r.changeSummary, rowVersion: 'rowVersion' in r ? r.rowVersion : null, updatedAt: 'publishedAt' in r ? r.publishedAt : r.updatedAt,
    updatedByName: 'publishedBy' in r ? r.publishedBy : r.updatedBy, reason: 'reason' in r ? r.reason : null
  });

  const legalPage = (ctx: PreorderCtx, key: string): ContentPage => {
    const doc = ctx.db.content.legal[key];
    if (!doc) throw problem(404, 'NOT_FOUND', 'Not found', 'There is no such page.');
    const current = doc.versions.find((v) => v.status === 'CURRENT');
    if (!current) throw problem(404, 'NOT_FOUND', 'Not found', 'There is no such page.');
    const publish = canPublish(ctx, 'legal');
    return {
      kind: 'legal', key, title: doc.title, group: legalGroupTitle(key), livePath: `/legal/${key}`, liveUrl: live(`/legal/${key}`), locales: ['en', 'fr', 'es', 'ar', 'yo'],
      published: { ...legalDoc(current), title: doc.title },
      draft: doc.draft ? legalDraftDoc(doc.draft, doc.title) : null,
      versions: doc.versions.map((v): ContentVersionRef => ({ version: v.version, status: v.status, effectiveDate: v.effectiveDate, changeSummary: v.changeSummary, reason: null, publishedAt: v.publishedAt, publishedByName: v.publishedBy, draftBanner: v.draftBanner, locales: Object.keys(v.bodies) })),
      placeholders: PLACEHOLDERS, topics: [],
      access: { canEdit: canEdit(ctx, 'legal'), canPublish: publish, secondApprover: true, reason: publish ? null : canEdit(ctx, 'legal') ? 'Publishing a legal document needs the Approve level on Site content & legal. You can save drafts.' : 'Your roles can view this page but not change it.' }
    };
  };

  const helpPage = (ctx: PreorderCtx, slug: string): ContentPage => {
    const page = ctx.db.content.help[slug];
    const latest = page?.revisions[0];
    if (!page || !latest) throw problem(404, 'NOT_FOUND', 'Not found', 'There is no such page.');
    const publish = canPublish(ctx, 'help');
    const visible = page.status === 'PUBLISHED';
    return {
      kind: 'help', key: slug, title: latest.title, group: topicTitle(latest.topic), livePath: visible ? helpPath(slug) : null, liveUrl: visible ? live(helpPath(slug)) : null, locales: ['en'],
      published: helpDoc(latest, String(latest.revision)),
      draft: page.draft ? helpDoc(page.draft, 'draft') : null,
      versions: page.revisions.map((r, i): ContentVersionRef => ({ version: String(r.revision), status: i === 0 ? 'CURRENT' : 'ARCHIVED', effectiveDate: null, changeSummary: r.changeSummary, reason: r.reason, publishedAt: r.publishedAt, publishedByName: r.publishedBy, draftBanner: null, locales: ['en'] })),
      placeholders: {}, topics: HELP_TOPICS.map((t) => ({ value: t, label: topicTitle(t) })),
      access: { canEdit: canEdit(ctx, 'help'), canPublish: publish, secondApprover: false, reason: publish ? null : 'Your roles can view this page but not change it.' }
    };
  };

  const P = `${W}/content/pages`;
  return [
    http.get(
      P,
      route((ctx): ContentPageList => {
        view(ctx);
        const rows: ContentPageRow[] = [];
        for (const [key, doc] of Object.entries(ctx.db.content.legal)) {
          const current = doc.versions.find((v) => v.status === 'CURRENT');
          if (!current) continue;
          rows.push({
            kind: 'legal', key, title: doc.title, group: legalGroupTitle(key), livePath: `/legal/${key}`, liveUrl: live(`/legal/${key}`), status: current.draftBanner ? 'DRAFT_FOR_COUNSEL' : 'PUBLISHED',
            version: current.version, effectiveDate: current.effectiveDate, updatedAt: current.publishedAt, updatedByName: current.publishedBy, hasDraft: doc.draft !== null,
            draftUpdatedByName: doc.draft?.updatedBy ?? null, editor: 'page', resource: null, resourceId: null, consoleHref: null, canEdit: canEdit(ctx, 'legal'), locales: ['en', 'fr', 'es', 'ar', 'yo']
          });
        }
        for (const [slug, page] of Object.entries(ctx.db.content.help)) {
          const latest = page.revisions[0];
          if (!latest) continue;
          const visible = page.status === 'PUBLISHED';
          rows.push({
            kind: 'help', key: slug, title: latest.title, group: topicTitle(latest.topic), livePath: visible ? helpPath(slug) : null, liveUrl: visible ? live(helpPath(slug)) : null,
            status: visible ? 'PUBLISHED' : 'HIDDEN', version: String(latest.revision), effectiveDate: null, updatedAt: latest.publishedAt, updatedByName: latest.publishedBy, hasDraft: page.draft !== null,
            draftUpdatedByName: page.draft?.updatedBy ?? null, editor: 'page', resource: null, resourceId: null, consoleHref: null, canEdit: canEdit(ctx, 'help'), locales: ['en']
          });
        }
        const record = (key: string, title: string, group: string, path: string | null, resource: string | null, resourceId: string | null, consoleHref: string | null, editable: boolean): ContentPageRow => ({
          kind: 'record', key, title, group, livePath: path, liveUrl: live(path), status: 'PUBLISHED', version: '', effectiveDate: null, updatedAt: null, updatedByName: null, hasDraft: false,
          draftUpdatedByName: null, editor: 'record', resource, resourceId, consoleHref, canEdit: editable, locales: ['en']
        });
        const siteEdit = canEdit(ctx, 'help');
        const marketing = allows(ctx.roles, 'marketing', 'EDIT') && ctx.permissions.includes('content:placements.edit');
        rows.push(
          record('site-settings', 'Contacts & social links', 'Site', '/', 'site-settings', 'site', null, siteEdit),
          record('maintenance', 'Maintenance notice', 'Site', '/', 'maintenance', null, null, siteEdit),
          record('announcements', 'Announcement bar', 'Marketing', '/', 'announcements', null, null, marketing),
          record('placements', 'Homepage banners & tiles', 'Marketing', '/', 'placements', null, null, marketing),
          record('notification-templates', 'Email, SMS and push messages', 'Notifications', null, null, null, '/settings?section=notifications', allows(ctx.roles, 'notifications', 'EDIT')),
          { ...record('about', 'About AfroTransact', 'Company', '/about', null, null, null, false), status: 'DEVELOPER_ONLY', editor: 'developer' }
        );
        return { storefrontUrl: MOCK_STOREFRONT_URL, items: rows };
      })
    ),
    http.get(
      `${P}/:kind/:key`,
      route((ctx, params): ContentPage => {
        view(ctx);
        const kind = kindOf(params.kind);
        return kind === 'legal' ? legalPage(ctx, String(params.key)) : helpPage(ctx, String(params.key));
      })
    ),
    http.get(
      `${P}/:kind/:key/versions/:version`,
      route((ctx, params): ContentDocument => {
        view(ctx);
        const kind = kindOf(params.kind);
        const key = String(params.key);
        const version = String(params.version);
        if (kind === 'legal') {
          const doc = ctx.db.content.legal[key];
          const v = doc?.versions.find((x) => x.version === version);
          if (!doc || !v) throw problem(404, 'NOT_FOUND', 'Not found', 'There is no such version.');
          return { ...legalDoc(v), title: doc.title };
        }
        const r = ctx.db.content.help[key]?.revisions.find((x) => String(x.revision) === version);
        if (!r) throw problem(404, 'NOT_FOUND', 'Not found', 'There is no such version.');
        return helpDoc(r, String(r.revision));
      })
    ),
    http.put(
      `${P}/:kind/:key/draft`,
      route(async (ctx, params): Promise<ContentDocument> => {
        const kind = kindOf(params.kind);
        edit(ctx, kind);
        const b = await json(ctx.request, DraftBody);
        const errs = problemsOf(kind, b);
        if (Object.keys(errs).length) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errs);
        const key = String(params.key);
        const now = ctx.now.toISOString();
        if (kind === 'legal') {
          const doc = ctx.db.content.legal[key];
          const current = doc?.versions.find((v) => v.status === 'CURRENT');
          if (!doc || !current) throw problem(404, 'NOT_FOUND', 'Not found', 'There is no such page.');
          if ((doc.draft?.rowVersion ?? undefined) !== b.rowVersion) throw problem(409, 'CONFLICT', 'Changed by someone else', 'Someone changed the draft in the meantime. Reload it and apply your change again.');
          const version = b.version ?? bumpMinor(current.version);
          if (!SEMVER.test(version) || cmp(version, current.version) <= 0) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'The version must be higher than the published one.', { version: `Must be higher than the current version ${current.version}` });
          doc.draft = {
            version, effectiveDate: b.effectiveDate ?? current.effectiveDate, draftBanner: b.draftBanner ?? true, bodies: { ...b.bodies }, needsReview: [...(b.needsReview ?? [])],
            changeSummary: b.changeSummary?.trim() || null, rowVersion: (doc.draft?.rowVersion ?? 0) + 1, updatedBy: ctx.action.actor, updatedAt: now
          };
          audit(ctx, 'legal.draft-saved', key, `Saved the draft of ${doc.title} (${version})`);
          return legalDraftDoc(doc.draft, doc.title);
        }
        const page = ctx.db.content.help[key];
        const latest = page?.revisions[0];
        if (!page || !latest) throw problem(404, 'NOT_FOUND', 'Not found', 'There is no such page.');
        if ((page.draft?.rowVersion ?? undefined) !== b.rowVersion) throw problem(409, 'CONFLICT', 'Changed by someone else', 'Someone changed the draft in the meantime. Reload it and apply your change again.');
        page.draft = {
          title: b.title ?? latest.title, summary: b.summary ?? latest.summary, topic: b.topic ?? latest.topic, sortOrder: b.sortOrder ?? latest.sortOrder, body: b.bodies.en ?? '',
          changeSummary: b.changeSummary?.trim() || null, rowVersion: (page.draft?.rowVersion ?? 0) + 1, updatedBy: ctx.action.actor, updatedAt: now
        };
        audit(ctx, 'help-article.draft-saved', key, `Saved the draft of ${page.draft.title}`);
        return helpDoc(page.draft, 'draft');
      })
    ),
    http.delete(
      `${P}/:kind/:key/draft`,
      route((ctx, params) => {
        const kind = kindOf(params.kind);
        edit(ctx, kind);
        const key = String(params.key);
        const holder = kind === 'legal' ? ctx.db.content.legal[key] : ctx.db.content.help[key];
        if (!holder?.draft) throw problem(404, 'NOT_FOUND', 'Not found', 'There is no draft.');
        holder.draft = null;
        audit(ctx, `${kind === 'legal' ? 'legal' : 'help-article'}.draft-discarded`, key, `Discarded the draft of ${key}`);
        return new Response(null, { status: 204 });
      })
    ),
    http.post(
      `${P}/:kind/:key/publication`,
      route(async (ctx, params): Promise<ContentPage> => {
        const kind = kindOf(params.kind);
        edit(ctx, kind);
        if (!canPublish(ctx, kind)) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Publishing a legal document needs the Approve level on Site content & legal. You can save drafts.');
        const b = await json(ctx.request, PublishBody);
        const reason = b.reason.trim();
        if (reason.length < 5 || reason.length > 500) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Say why you are publishing.', { reason: 'Say why you are publishing (5 to 500 characters)' });
        const key = String(params.key);
        const now = ctx.now.toISOString();
        if (kind === 'legal') {
          const doc = ctx.db.content.legal[key];
          const draft = doc?.draft;
          if (!doc || !draft) throw problem(404, 'NOT_FOUND', 'Not found', 'There is no draft.');
          if (draft.rowVersion !== b.rowVersion) throw problem(409, 'CONFLICT', 'Changed by someone else', 'Someone changed the draft in the meantime. Reload it and apply your change again.');
          if (draft.updatedBy === ctx.action.actor) throw problem(403, 'FORBIDDEN', 'A second person must publish this', 'A second person must publish this: you saved the last change to the draft.');
          const markers = Object.values(draft.bodies).reduce((n, t) => n + (t.match(/\[\[LEGAL:[^\]]*\]\]/g)?.length ?? 0), 0);
          if (!draft.draftBanner && markers > 0) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'The draft cannot be published yet.', { bodies: `${markers} [[LEGAL: …]] marker${markers === 1 ? '' : 's'} left. Resolve them, or publish with the draft banner on.` });
          if (!draft.changeSummary) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'The draft cannot be published yet.', { changeSummary: 'Say what changed' });
          doc.versions.forEach((v) => {
            if (v.status === 'CURRENT') v.status = 'ARCHIVED';
          });
          doc.versions.unshift({ version: draft.version, effectiveDate: draft.effectiveDate, status: 'CURRENT', draftBanner: draft.draftBanner, bodies: { ...draft.bodies }, changeSummary: draft.changeSummary, publishedBy: ctx.action.actor, publishedAt: now });
          doc.draft = null;
          const rec = ctx.db.records['legal-documents'].find((r) => r.id === key);
          if (rec) {
            rec.fields.version = draft.version;
            rec.fields.effectiveDate = draft.effectiveDate;
            rec.fields.draftBanner = draft.draftBanner;
            rec.fields.state = draft.draftBanner ? 'Draft for counsel' : 'In force';
          }
          audit(ctx, 'legal.draft-published', key, `Published ${doc.title} ${draft.version}: ${reason}`);
          return legalPage(ctx, key);
        }
        const page = ctx.db.content.help[key];
        const draft = page?.draft;
        if (!page || !draft) throw problem(404, 'NOT_FOUND', 'Not found', 'There is no draft.');
        if (draft.rowVersion !== b.rowVersion) throw problem(409, 'CONFLICT', 'Changed by someone else', 'Someone changed the draft in the meantime. Reload it and apply your change again.');
        const next: HelpRevision = { revision: (page.revisions[0]?.revision ?? 0) + 1, title: draft.title, summary: draft.summary, topic: draft.topic, sortOrder: draft.sortOrder, body: draft.body, changeSummary: draft.changeSummary, reason, publishedBy: ctx.action.actor, publishedAt: now };
        page.revisions.unshift(next);
        page.draft = null;
        syncHelpRecord(ctx, key, next);
        audit(ctx, 'help-article.draft-published', key, `Published ${next.title}: ${reason}`);
        return helpPage(ctx, key);
      }, 201)
    ),
    http.post(
      `${P}/:kind/:key/rollback`,
      route(async (ctx, params): Promise<ContentPage> => {
        const kind = kindOf(params.kind);
        edit(ctx, kind);
        const b = await json(ctx.request, RollbackBody);
        const key = String(params.key);
        const now = ctx.now.toISOString();
        if (kind === 'legal') {
          const doc = ctx.db.content.legal[key];
          const current = doc?.versions.find((v) => v.status === 'CURRENT');
          const old = doc?.versions.find((v) => v.version === b.version);
          if (!doc || !current || !old) throw problem(404, 'NOT_FOUND', 'Not found', 'There is no such version.');
          if (doc.draft) throw problem(409, 'CONFLICT', 'There is already a draft', `There is already a draft of ${doc.title}. Publish or discard it before restoring a version.`);
          doc.draft = {
            version: bumpMinor(current.version), effectiveDate: current.effectiveDate, draftBanner: old.draftBanner, bodies: { ...old.bodies }, needsReview: [],
            changeSummary: `Restores version ${old.version}`, rowVersion: 0, updatedBy: ctx.action.actor, updatedAt: now
          };
          audit(ctx, 'legal.draft-restored', key, `Started a draft of ${doc.title} from version ${old.version}`);
          return legalPage(ctx, key);
        }
        if (!canPublish(ctx, 'help')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Your roles can view this page but not change it.');
        const reason = (b.reason ?? '').trim();
        if (reason.length < 5 || reason.length > 500) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Say why you are going back.', { reason: 'Say why you are going back (5 to 500 characters)' });
        const page = ctx.db.content.help[key];
        const old = page?.revisions.find((r) => String(r.revision) === b.version);
        if (!page || !old) throw problem(404, 'NOT_FOUND', 'Not found', 'There is no such version.');
        if (page.draft) throw problem(409, 'CONFLICT', 'There is a draft', 'There is a draft of this page. Publish or discard it before restoring a revision.');
        const next: HelpRevision = { ...old, revision: (page.revisions[0]?.revision ?? 0) + 1, changeSummary: `Restores revision ${old.revision}`, reason, publishedBy: ctx.action.actor, publishedAt: now };
        page.revisions.unshift(next);
        syncHelpRecord(ctx, key, next);
        audit(ctx, 'help-article.restored', key, `Restored revision ${old.revision} of ${next.title}: ${reason}`);
        return helpPage(ctx, key);
      }, 201)
    )
  ];

  function syncHelpRecord(ctx: PreorderCtx, slug: string, r: HelpRevision) {
    const rec = ctx.db.records['help-articles'].find((x) => x.id === slug);
    if (!rec) return;
    rec.fields.title = r.title;
    rec.fields.summary = r.summary;
    rec.fields.topic = r.topic;
    rec.fields.topicTitle = topicTitle(r.topic);
    rec.fields.sections = r.body;
    rec.fields.updatedAt = r.publishedAt;
  }
}
