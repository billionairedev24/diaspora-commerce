/* Mock of Home settings and Home preview (/v1/staff/home/**; S417, S418, S430). Settings live in the same module-level state the
   storefront mock BFF reads, so a saved threshold changes the mock home and the department / community navigation at once.
   A save needs a reason and the current version (409 when someone saved in between); every save is audit-logged. */
import { http } from 'msw';
import { z } from 'zod';

import { type DeliveryLocation } from '../../schemas/location';
import { type HomePreview, HomeDynamicModeSchema, HomeModuleRuleSchema, type HomeSettings, MIN_REASON_LENGTH, SIMULATED_CATALOGUES } from '../../staff-home';
import { fixtureCatalog, homeMock, previewHome } from '../home-dynamic';
import { liveCatalog } from '../service';
import { allows } from './model';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';

const InputSchema = z.object({
  version: z.number().int(),
  reason: z.string().default(''),
  dynamic: HomeDynamicModeSchema,
  organicPerSponsored: z.number().int().min(1),
  minModules: z.number().int().min(0),
  maxModules: z.number().int().min(1),
  modules: z.array(HomeModuleRuleSchema)
});

const HOUSTON: DeliveryLocation = { zip: '77042', city: 'Houston', state: 'TX', label: 'Houston 77042', serviceable: true, source: 'chosen' };
const GUEST: DeliveryLocation = { ...HOUSTON, zip: '', label: 'Houston', source: 'detected' };

export function homeSettingsHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, json, problem } = deps;
  const need = (ctx: PreorderCtx, edit: boolean) => {
    if (!allows(ctx.roles, 'marketing', edit ? 'EDIT' : 'VIEW')) throw problem(403, 'FORBIDDEN', 'Forbidden', edit ? 'Your roles can’t change Marketing.' : 'Your roles can’t open Marketing.');
  };
  const view = (ctx: PreorderCtx): HomeSettings => ({ ...homeMock.settings, canEdit: allows(ctx.roles, 'marketing', 'EDIT') });
  return [
    http.get(
      `${W}/home/settings`,
      route((ctx): HomeSettings => {
        need(ctx, false);
        return view(ctx);
      })
    ),
    http.put(
      `${W}/home/settings`,
      route(async (ctx): Promise<HomeSettings> => {
        need(ctx, true);
        const body = await json(ctx.request, InputSchema);
        const errors: Record<string, string> = {};
        if (body.reason.trim().length < MIN_REASON_LENGTH) errors.reason = 'Say why you are changing the home page.';
        if (body.minModules > body.maxModules) errors.minModules = 'The minimum cannot be above the maximum.';
        body.modules.forEach((m, i) => {
          if (m.startsAt && m.endsAt && Date.parse(m.endsAt) <= Date.parse(m.startsAt)) errors[`modules.${i}.endsAt`] = 'The end must be after the start.';
          if (Object.values(m.labels).some(Boolean) && !m.labels.en) errors[`modules.${i}.labels`] = 'English is required when another language is set.';
        });
        if (Object.keys(errors).length) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
        if (body.version !== homeMock.settings.version) throw problem(409, 'CONFLICT', 'Changed by someone else', 'Home settings were saved by someone else. Reload and apply your change again.');
        const changed = body.modules.filter((m) => JSON.stringify(m) !== JSON.stringify(homeMock.settings.modules.find((o) => o.key === m.key)));
        homeMock.settings = {
          ...homeMock.settings,
          dynamic: body.dynamic,
          organicPerSponsored: body.organicPerSponsored,
          minModules: body.minModules,
          maxModules: body.maxModules,
          modules: body.modules,
          version: homeMock.settings.version + 1,
          updatedAt: ctx.now.toISOString(),
          updatedByName: ctx.action.actor
        };
        ctx.db.audit.unshift({
          at: ctx.now.toISOString(),
          actor: ctx.action.actor,
          action: 'home.settings.changed',
          summary: `Home settings v${homeMock.settings.version}: ${changed.length ? changed.map((m) => m.name).join(', ') : 'caps and mode'} — ${body.reason.trim()}`,
          entityId: 'home'
        });
        return view(ctx);
      })
    ),
    http.get(
      `${W}/home/preview`,
      route((ctx): HomePreview => {
        need(ctx, false);
        const q = new URL(ctx.request.url).searchParams;
        const sim = q.get('simulate');
        const size = sim !== null ? Number(sim) : Number.NaN;
        const invalid = (field: string, message: string) => problem(422, 'VALIDATION_FAILED', 'Validation failed', message, { [field]: message });
        if (sim !== null && !SIMULATED_CATALOGUES.some((n) => n === size)) throw invalid('simulate', 'Choose 0, 10, 100 or 10000.');
        const zip = q.get('zip')?.trim() ?? '';
        if (zip && !/^\d{5}$/.test(zip)) throw invalid('zip', 'A ZIP has 5 digits.');
        const asOf = q.get('asOf') ?? undefined;
        if (asOf && Number.isNaN(Date.parse(asOf))) throw invalid('asOf', 'Use a date like 2026-12-01.');
        const loc = zip ? { ...HOUSTON, zip, label: `Houston ${zip}` } : GUEST;
        const catalog = sim !== null ? fixtureCatalog(size) : homeMock.catalogue !== null ? fixtureCatalog(homeMock.catalogue) : liveCatalog(loc, ctx.now);
        const locale = q.get('locale') ?? undefined;
        return previewHome({ zip, signedIn: q.get('signedIn') === 'true', asOf, seed: q.get('seed') ?? undefined, locale }, { catalog, settings: homeMock.settings, loc, now: ctx.now, locale });
      })
    )
  ];
}
