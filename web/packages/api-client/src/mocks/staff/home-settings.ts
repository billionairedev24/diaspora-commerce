/* Mock of Home settings, the community registry and Home preview (/v1/staff/home/**; S417, S418, S420, S424, S430). Settings live in the
   same module-level state the storefront mock BFF reads, so a saved threshold changes the mock home and the department and community
   navigation at once. A save needs a reason and the current version (409 when someone saved in between); every save is audit-logged and
   kept as a version that can be reverted to. */
import { http } from 'msw';
import { z } from 'zod';

import { type DeliveryLocation } from '../../schemas/location';
import {
  type HomeCaps,
  HomeCapsSchema,
  HomeDynamicModeSchema,
  HomeModuleRuleSchema,
  type HomePreview,
  type HomeSettings,
  type HomeSettingsInput,
  homeSettingsProblems,
  type HomeSettingsVersion,
  SIMULATED_CATALOGUES,
  type StaffCommunities,
  type StaffCommunity,
  StaffLocaleLabelsSchema} from '../../staff-home';
import { defaultHomeSettings, departmentsOf, fixtureCatalog, homeMock, previewHome, signalsOf, visibleCommunities } from '../home-dynamic';
import { liveCatalog } from '../service';
import { allows } from './model';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';

const InputSchema = z.object({
  expectedVersion: z.number().int(),
  reason: z.string().default(''),
  dynamic: HomeDynamicModeSchema.nullable(),
  modules: z.record(z.string(), HomeModuleRuleSchema),
  caps: HomeCapsSchema,
  departmentLabels: z.record(z.string(), StaffLocaleLabelsSchema)
});
const CommunityWriteSchema = z.object({ labels: StaffLocaleLabelsSchema, aliases: z.array(z.string()).default([]), imageUrl: z.string().nullable().default(null), reason: z.string().default('') });
const RevertSchema = z.object({ toVersion: z.number().int(), reason: z.string().default('') });

const HOUSTON: DeliveryLocation = { zip: '77042', city: 'Houston', state: 'TX', label: 'Houston 77042', serviceable: true, source: 'chosen' };
const GUEST: DeliveryLocation = { ...HOUSTON, zip: '', label: 'Houston', source: 'detected' };

/** The community registry the mock keeps (S420): canonical key, five-locale labels, aliases and an optional editorial image. */
const registry = new Map<string, StaffCommunity>();

export function resetHomeRegistry(): void {
  registry.clear();
}

/** Dotted paths of every changed value, for the version history ("modules.communities.minProducts"). */
function diff(from: unknown, to: unknown, path: string, out: Array<{ field: string; from: unknown; to: unknown }>): void {
  if (JSON.stringify(from) === JSON.stringify(to)) return;
  if (from && to && typeof from === 'object' && typeof to === 'object' && !Array.isArray(from) && !Array.isArray(to)) {
    const keys = new Set([...Object.keys(from as object), ...Object.keys(to as object)]);
    for (const k of keys) diff((from as Record<string, unknown>)[k], (to as Record<string, unknown>)[k], path ? `${path}.${k}` : k, out);
    return;
  }
  out.push({ field: path, from: from ?? null, to: to ?? null });
}

const inputOf = (s: HomeSettings): HomeSettingsInput => ({ dynamic: s.dynamic, modules: s.modules, caps: s.caps, departmentLabels: s.departmentLabels });

export function homeSettingsHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, json, problem } = deps;
  const need = (ctx: PreorderCtx, edit: boolean) => {
    if (!allows(ctx.roles, 'marketing', edit ? 'EDIT' : 'VIEW')) throw problem(403, 'FORBIDDEN', 'Forbidden', edit ? 'Your roles can’t change Marketing.' : 'Your roles can’t open Marketing.');
  };
  const view = (): HomeSettings => ({ ...homeMock.settings, effectiveDynamic: homeMock.settings.dynamic ?? 'on' });
  const reasonOk = (reason: string, field = 'reason') => {
    if (reason.trim().length < 3) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Say why you are changing the home page.', { [field]: 'Say why you are changing the home page (3 characters or more).' });
  };
  const commit = (ctx: PreorderCtx, next: HomeSettingsInput, reason: string, revertOf: number | null) => {
    const before = inputOf(homeMock.settings);
    const changes: HomeSettingsVersion['changes'] = [];
    diff(before, next, '', changes);
    const version = homeMock.settings.version + 1;
    homeMock.settings = { ...homeMock.settings, ...next, version, effectiveDynamic: next.dynamic ?? 'on', updatedAt: ctx.now.toISOString(), updatedBy: ctx.action.actor };
    homeMock.versions.unshift({ version, reason: reason.trim(), changedBy: ctx.action.actor, changedAt: ctx.now.toISOString(), revertOf, changes, snapshot: homeMock.settings });
    ctx.db.audit.unshift({
      at: ctx.now.toISOString(),
      actor: ctx.action.actor,
      action: revertOf === null ? 'home.settings.changed' : 'home.settings.reverted',
      summary: `Home settings v${version}: ${changes.length ? changes.slice(0, 4).map((c) => c.field).join(', ') : 'no value changed'} — ${reason.trim()}`,
      entityId: 'home'
    });
  };
  const staffCommunity = (key: string, ctx: { now: Date }): StaffCommunity => {
    const entry = registry.get(key) as StaffCommunity;
    const visible = visibleCommunities(fixtureCatalog(homeMock.catalogue ?? 100), homeMock.settings).find((c) => c.key === key);
    void ctx;
    return { ...entry, eligibleProducts: visible?.count ?? 0, sellers: visible?.sellers ?? 0, visible: !!visible, imageAccepted: entry.imageUrl ? /^https:\/\/.+\.(jpe?g|png|webp)$/i.test(entry.imageUrl) : undefined };
  };

  return [
    // The department picker (also the storefront's navigation source): the empty departments only for staff.
    http.get('*/v1/site/departments', ({ request }) => {
      const includeEmpty = new URL(request.url).searchParams.get('includeEmpty') === 'true';
      return Response.json({ departments: departmentsOf(fixtureCatalog(homeMock.catalogue ?? 100), homeMock.settings, includeEmpty), generatedAt: new Date().toISOString() });
    }),
    http.get(
      `${W}/home/settings`,
      route((ctx): HomeSettings => {
        need(ctx, false);
        return view();
      })
    ),
    http.put(
      `${W}/home/settings`,
      route(async (ctx): Promise<HomeSettings> => {
        need(ctx, true);
        const body = await json(ctx.request, InputSchema);
        const bad = homeSettingsProblems({ dynamic: body.dynamic, modules: body.modules, caps: body.caps, departmentLabels: body.departmentLabels }, body.reason);
        if (bad.length) {
          const errors = Object.fromEntries(bad.map((f) => [f, f === 'reason' ? 'Say why you are changing the home page (3 characters or more).' : 'This value is not valid.']));
          throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
        }
        if (body.expectedVersion !== homeMock.settings.version) throw problem(409, 'CONFLICT', 'Changed by someone else', 'Home settings were saved by someone else. Reload and apply your change again.');
        commit(ctx, { dynamic: body.dynamic, modules: body.modules, caps: body.caps as HomeCaps, departmentLabels: body.departmentLabels }, body.reason, null);
        return view();
      })
    ),
    http.get(
      `${W}/home/settings/versions`,
      route((ctx): HomeSettingsVersion[] => {
        need(ctx, false);
        return homeMock.versions.map(({ snapshot: _s, ...v }) => v);
      })
    ),
    http.post(
      `${W}/home/settings/revert`,
      route(async (ctx): Promise<HomeSettings> => {
        need(ctx, true);
        const body = await json(ctx.request, RevertSchema);
        reasonOk(body.reason);
        const target = homeMock.versions.find((v) => v.version === body.toVersion);
        const base = target ? inputOf(target.snapshot) : body.toVersion === 0 ? inputOf(defaultHomeSettings()) : null;
        if (!base) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'That version does not exist.', { toVersion: 'That version does not exist.' });
        commit(ctx, base, body.reason, body.toVersion);
        return view();
      })
    ),
    http.get(
      `${W}/home/communities`,
      route((ctx): StaffCommunities => {
        need(ctx, false);
        const catalog = fixtureCatalog(homeMock.catalogue ?? 100);
        const known = new Set([...registry.values()].flatMap((c) => [c.key, ...c.aliases.map((a) => a.toLowerCase()), c.labels.en.toLowerCase()]));
        const unregistered = signalsOf(catalog)
          .communities.filter((c) => !known.has(c.name.toLowerCase()))
          .map((c) => ({ value: c.name, eligibleProducts: c.products.length }));
        return { registered: [...registry.keys()].map((k) => staffCommunity(k, ctx)), unregistered };
      })
    ),
    http.put(
      `${W}/home/communities/:key`,
      route(async (ctx): Promise<StaffCommunity> => {
        need(ctx, true);
        const key = decodeURIComponent(new URL(ctx.request.url).pathname.split('/').pop() ?? '');
        if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(key)) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'A key is lower case letters, digits and dashes.', { key: 'A key is lower case letters, digits and dashes.' });
        const body = await json(ctx.request, CommunityWriteSchema);
        reasonOk(body.reason);
        registry.set(key, { key, labels: body.labels, aliases: body.aliases, imageUrl: body.imageUrl });
        ctx.db.audit.unshift({ at: ctx.now.toISOString(), actor: ctx.action.actor, action: 'home.community.saved', summary: `Community ${body.labels.en} saved — ${body.reason.trim()}`, entityId: key });
        return staffCommunity(key, ctx);
      })
    ),
    http.delete(
      `${W}/home/communities/:key`,
      route((ctx): Response => {
        need(ctx, true);
        const key = decodeURIComponent(new URL(ctx.request.url).pathname.split('/').pop() ?? '');
        reasonOk(new URL(ctx.request.url).searchParams.get('reason') ?? '');
        registry.delete(key);
        return new Response(null, { status: 204 });
      })
    ),
    http.get(
      `${W}/home/preview`,
      route((ctx): HomePreview => {
        need(ctx, false);
        const q = new URL(ctx.request.url).searchParams;
        const invalid = (field: string, message: string) => problem(422, 'VALIDATION_FAILED', 'Validation failed', message, { [field]: message });
        const catalogParam = q.get('catalog') ?? 'live';
        if (catalogParam !== 'live' && !SIMULATED_CATALOGUES.some((n) => n === catalogParam)) throw invalid('catalog', 'Choose live, 0, 10, 100 or 10000.');
        const zip = q.get('zip')?.trim() ?? '';
        if (zip && !/^\d{5}$/.test(zip)) throw invalid('zip', 'A ZIP has 5 digits.');
        const asOf = q.get('asOf') ?? undefined;
        if (asOf && Number.isNaN(Date.parse(asOf))) throw invalid('asOf', 'Use a date like 2026-12-01.');
        const loc = zip ? { ...HOUSTON, zip, label: `Houston ${zip}` } : GUEST;
        const catalog = catalogParam !== 'live' ? fixtureCatalog(Number(catalogParam)) : homeMock.catalogue !== null ? fixtureCatalog(homeMock.catalogue) : liveCatalog(loc, ctx.now);
        return previewHome(
          { zip, signedIn: q.get('signedIn') === 'true', asOf, seed: q.get('seed') ?? undefined, locale: q.get('locale') ?? undefined, deterministic: q.get('deterministic') === 'true', variant: q.get('variant') ?? undefined },
          { catalog, catalogName: catalogParam, settings: homeMock.settings, loc, now: ctx.now }
        );
      })
    )
  ];
}
