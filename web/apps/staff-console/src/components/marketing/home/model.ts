import { HOME_BANDS, type HomeBand, type HomeCaps, type HomeModuleRule, type HomeSettings, type HomeSettingsInput, type StaffLocaleLabels } from '@afrotransact/api-client/staff-home';

/* Home settings: the pure parts (what a module's rule can be edited with, what changed, how the page is ordered). No ids are ever
   shown: the module key is only a map key, the person reads `moduleNames`. */

export type RuleField = Exclude<keyof HomeModuleRule, 'enabled' | 'band' | 'weight' | 'show'>;

/** The thresholds each module understands; a module this build does not know (a newer gateway) shows them all. */
const RULE_FIELDS: Record<string, readonly RuleField[]> = {
  hero: [],
  'continue-shopping': ['minProducts'],
  'recently-viewed': ['minProducts'],
  'department-tiles': ['minUnits', 'unitMinProducts'],
  deals: ['minProducts', 'minSellers'],
  'groceries-rail': ['minProducts'],
  'services-near-you': ['minProducts'],
  'new-arrivals': ['minProducts', 'minSellers', 'maxAgeDays'],
  communities: ['minUnits', 'unitMinProducts', 'unitMinSellers'],
  'top-sellers': ['minProducts', 'minSellers'],
  preorder: [],
  plus: []
};
const ALL_FIELDS: readonly RuleField[] = ['minProducts', 'minSellers', 'minUnits', 'unitMinProducts', 'unitMinSellers', 'maxAgeDays'];

export const fieldsOf = (moduleKey: string): readonly RuleField[] => RULE_FIELDS[moduleKey] ?? ALL_FIELDS;

/** The modules in page order: bands keep their order, then the heavier weight first, then the name. */
export function orderedModules(s: Pick<HomeSettings, 'modules' | 'moduleNames'>): string[] {
  const band = (k: string) => HOME_BANDS.indexOf((s.modules[k]?.band ?? 'discovery') as HomeBand);
  return Object.keys(s.modules).sort((a, b) => band(a) - band(b) || (s.modules[b]?.weight ?? 0) - (s.modules[a]?.weight ?? 0) || (s.moduleNames[a] ?? a).localeCompare(s.moduleNames[b] ?? b));
}

export const inputOf = (s: HomeSettings): HomeSettingsInput => structuredClone({ dynamic: s.dynamic, modules: s.modules, caps: s.caps, departmentLabels: s.departmentLabels });

export type CapKey = keyof HomeCaps;
export const CAP_KEYS: readonly CapKey[] = ['maxModules', 'minModules', 'sponsoredPerOrganic', 'sellerShare', 'poolMultiplier', 'noveltyLoads', 'noveltyPenalty', 'visitWindowMinutes', 'hysteresis', 'dealMinPercent', 'navBarSize'];

export interface Change {
  /** `module`, `cap`, `label` or `mode`: which part of the page of settings it is in. */
  area: 'mode' | 'module' | 'cap' | 'label';
  /** The module's name or the department's name. */
  subject: string;
  /** For `module` changes: the module the field belongs to (its own wording of the field). */
  module?: string;
  field: string;
  from: string;
  to: string;
}

const show = (v: unknown): string => (v === undefined || v === null || v === '' ? '' : typeof v === 'boolean' ? (v ? 'on' : 'off') : String(v));

/** What the person changed, in the order of the page, so the reason they type is about something they can see. */
export function changesOf(orig: HomeSettingsInput, draft: HomeSettingsInput, names: Record<string, string>, departmentNames: Record<string, string>): Change[] {
  const out: Change[] = [];
  if (orig.dynamic !== draft.dynamic) out.push({ area: 'mode', subject: '', field: 'dynamic', from: show(orig.dynamic ?? 'default'), to: show(draft.dynamic ?? 'default') });
  for (const key of Object.keys(draft.modules)) {
    const a = orig.modules[key] as HomeModuleRule | undefined;
    const b = draft.modules[key] as HomeModuleRule;
    for (const f of Object.keys(b) as Array<keyof HomeModuleRule>) {
      if (show(a?.[f]) !== show(b[f])) out.push({ area: 'module', subject: names[key] ?? key, module: key, field: f, from: show(a?.[f]), to: show(b[f]) });
    }
  }
  for (const k of CAP_KEYS) if (show(orig.caps[k]) !== show(draft.caps[k])) out.push({ area: 'cap', subject: '', field: k, from: show(orig.caps[k]), to: show(draft.caps[k]) });
  for (const key of new Set([...Object.keys(orig.departmentLabels), ...Object.keys(draft.departmentLabels)])) {
    const a = orig.departmentLabels[key];
    const b = draft.departmentLabels[key];
    for (const l of ['en', 'fr', 'es', 'ar', 'yo'] as const) {
      if (show(a?.[l]) !== show(b?.[l])) out.push({ area: 'label', subject: departmentNames[key] ?? key, field: l, from: show(a?.[l]), to: show(b?.[l]) });
    }
  }
  return out;
}

/** Drops empty locale labels (an empty one means "the built-in translation") and departments with none. */
export function cleanLabels(labels: Record<string, StaffLocaleLabels>): Record<string, StaffLocaleLabels> {
  const out: Record<string, StaffLocaleLabels> = {};
  for (const [key, l] of Object.entries(labels)) {
    const kept = Object.fromEntries(Object.entries(l).filter(([, v]) => typeof v === 'string' && v.trim() !== '').map(([k, v]) => [k, (v as string).trim()]));
    if (Object.keys(kept).length > 0) out[key] = kept as StaffLocaleLabels;
  }
  return out;
}

export const REASON_TONE: Record<string, 'good' | 'warn' | 'info' | 'bad'> = {
  shown: 'good',
  'disabled-by-staff': 'info',
  'sponsored-cap': 'warn',
  'page-full': 'warn'
};
export const reasonTone = (reason: string): 'good' | 'warn' | 'info' | 'bad' => REASON_TONE[reason] ?? 'warn';
