/* Service areas (Settings > Markets & locations) in the mock staff BFF: the radius rule per service category and the metro
   zones, every change with a reason. Mirrors internal/app/staff_resources_serviceareas.go of the Go BFF over bookings'
   /v1/staff/service-areas/…; docs/engineering/service-areas.md explains the rules. */
import { type FieldValue, type FormField, type StaffForm } from '../../schemas/staff';
import { type MockRecord, type Scalar } from './db';
import { type MockResource } from './resources';

const PERMISSION = 'content:markets.edit';
const field = (key: string, label: string, type: FormField['type'], required: boolean, extra: Partial<FormField> = {}): FormField => ({ key, label, type, required, ...extra });
const str = (v: FieldValue | Scalar | undefined): string => (v === null || v === undefined ? '' : String(v));
const num = (v: FieldValue | Scalar | undefined): number => (typeof v === 'number' ? v : Number(v ?? 0));
const reason = (): FormField => field('reason', 'Reason (kept in the audit trail)', 'textarea', true, { maxLength: 500, pattern: '^[\\s\\S]{5,}$', help: 'Give a reason of at least 5 characters' });
const CITY_LINES = '^(\\s*[^\\n,][^\\n]*,\\s*[A-Za-z]{2}\\s*(\\n|$))+$';
const CITY_HELP = 'Write each city as “City, ST” (one per line)';
const miles = (key: string, label: string, value: number, hi = 150): FormField => field(key, label, 'number', true, { min: 0, max: hi, value });
const requireReason = (v: Record<string, FieldValue>) => {
  if (str(v.reason).trim().length < 5) throw new Error('Give a reason of at least 5 characters');
};

const RULES: [string, string, number, number, number][] = [
  ['hair & beauty', 'Hair & beauty', 20, 5, 40],
  ['catering', 'Catering', 40, 10, 100],
  ['tailoring', 'Tailoring', 25, 5, 60],
  ['tax & legal', 'Tax & legal', 30, 5, 60],
  ['_fallback', 'Any other category', 25, 5, 60]
];

export function seedServiceAreaRules(now: Date): MockRecord[] {
  const at = now.toISOString();
  const rows: MockRecord[] = RULES.map(([id, name, defaultMiles, minMiles, maxMiles]) => ({ id, fields: { name, defaultMiles, minMiles, maxMiles }, activity: [{ at, actor: 'System', text: 'Seeded default' }] }));
  return rows;
}

const ZONES: [string, string, string][] = [
  ['greater-austin', 'Greater Austin', 'Austin, TX\nGeorgetown, TX\nRound Rock, TX\nHutto, TX\nPflugerville, TX\nCedar Park, TX\nLeander, TX\nTaylor, TX'],
  ['houston', 'Houston', 'Houston, TX\nSugar Land, TX\nKaty, TX\nPearland, TX\nMissouri City, TX'],
  ['dallas-fort-worth', 'Dallas-Fort Worth', 'Dallas, TX\nFort Worth, TX\nArlington, TX\nPlano, TX\nIrving, TX\nGarland, TX'],
  ['new-york-city', 'New York City', 'New York, NY\nBrooklyn, NY\nBronx, NY\nStaten Island, NY\nYonkers, NY']
];

const zoneFields = (key: string, name: string, cityList: string) => {
  const cities = cityList.split('\n').filter(Boolean);
  return { key, name, cityCount: cities.length, cityList, citiesPreview: cities.slice(0, 6).join(', ') };
};

export function seedServiceZones(now: Date): MockRecord[] {
  const at = now.toISOString();
  return ZONES.map(([key, name, cities]) => ({ id: key, fields: zoneFields(key, name, cities), activity: [{ at, actor: 'System', text: 'Seeded default' }] }));
}

/** "Round Rock, TX" lines → validated list; a bad line is named, like bookings' 422. */
function parseCities(text: string): string[] {
  const lines = text.split(/\n|;/).map((l) => l.trim()).filter(Boolean);
  if (!lines.length) throw new Error('Add at least one city');
  for (const l of lines) if (!/^.+,\s*[A-Za-z]{2}$/.test(l)) throw new Error(`Write each city as “City, ST” (one per line); check “${l}”`);
  return lines.map((l) => l.replace(/,\s*([A-Za-z]{2})$/, (_m, s: string) => `, ${s.toUpperCase()}`));
}

const form = (title: string, submitLabel: string, fields: FormField[], intro: string): StaffForm => ({ title, submitLabel, fields, intro });

export const serviceAreaRulesDef: MockResource = {
  title: 'Default search radius by category',
  columns: [
    { key: 'name', label: 'Service category', kind: 'strong', width: 34 }, { key: 'defaultMiles', label: 'Default search radius (mi)', kind: 'number', width: 20 }, { key: 'minMiles', label: 'Provider minimum (mi)', kind: 'number', width: 18 },
    { key: 'maxMiles', label: 'Provider maximum (mi)', kind: 'number', width: 18 }
  ],
  facets: [],
  search: ['name'],
  sort: (a, b) => (a.id === '_fallback' ? 1 : b.id === '_fallback' ? -1 : str(a.fields.name).localeCompare(str(b.fields.name))),
  exportable: false,
  titleOf: (r) => str(r.fields.name),
  subtitleOf: () => 'Shoppers search this far by default and can choose any distance; providers declare how far they travel to customers between the minimum and the maximum',
  sections: [['Radius', ['defaultMiles', 'minMiles', 'maxMiles']]],
  actions: [],
  edit: {
    permission: PERMISSION,
    form: (r) =>
      form(`Default search radius for ${str(r.fields.name)}`, 'Save', [miles('defaultMiles', 'Default search radius (miles)', num(r.fields.defaultMiles)), miles('minMiles', 'Provider minimum radius (miles)', num(r.fields.minMiles)), miles('maxMiles', 'Provider maximum radius (miles)', num(r.fields.maxMiles)), reason()],
        'The default is how far shoppers search in this category until they choose their own distance: it is not a limit, shoppers may pick any distance. Providers declare how far they travel to customers between the minimum and the maximum.'),
    apply: (r, v, ctx) => {
      requireReason(v);
      const [d, lo, hi] = [num(v.defaultMiles), num(v.minMiles), num(v.maxMiles)];
      if (lo < 1 || hi < lo || d < lo || d > hi) throw new Error('Need 1 <= minimum <= default <= maximum');
      Object.assign(r.fields, { defaultMiles: d, minMiles: lo, maxMiles: hi });
      if (ctx) r.activity.unshift({ at: ctx.now.toISOString(), actor: ctx.actor, text: `Changed: ${str(v.reason)}` });
    }
  }
};

export const serviceZonesDef: MockResource = {
  title: 'Metro zones',
  columns: [{ key: 'name', label: 'Zone', kind: 'strong', width: 22 }, { key: 'key', label: 'Key', kind: 'mono', width: 18 }, { key: 'cityCount', label: 'Cities', kind: 'number', width: 10 }, { key: 'citiesPreview', label: 'Includes', kind: 'text', width: 50 }],
  facets: [],
  search: ['name', 'cityList'],
  searchPlaceholder: 'Search zones or cities',
  sort: (a, b) => str(a.fields.name).localeCompare(str(b.fields.name)),
  exportable: false,
  titleOf: (r) => str(r.fields.name),
  subtitleOf: (r) => `${str(r.fields.cityCount)} cities`,
  sections: [['Zone', ['name', 'key', 'cityList']]],
  actions: [
    {
      key: 'delete', label: 'Delete zone', tone: 'danger', permission: PERMISSION, removes: true, when: () => null, confirm: 'Delete this zone? Providers that added it as an extra area keep their other areas.',
      form: () => form('Delete zone', 'Delete', [reason()], ''),
      apply: (_r, v) => requireReason(v)
    }
  ],
  edit: {
    permission: PERMISSION,
    form: (r) =>
      form(`Edit ${str(r.fields.name)}`, 'Save', [field('name', 'Zone name', 'text', true, { maxLength: 80, value: str(r.fields.name) }), field('cities', 'Cities (one per line, “City, ST”)', 'textarea', true, { maxLength: 6000, pattern: CITY_LINES, help: CITY_HELP, value: str(r.fields.cityList) }), reason()],
        'Providers see their zone (“Serves Greater Austin”) and can add a whole zone as an extra area. A city belongs to a zone by its name and state.'),
    apply: (r, v, ctx) => {
      requireReason(v);
      Object.assign(r.fields, zoneFields(str(r.fields.key), str(v.name), parseCities(str(v.cities)).join('\n')));
      if (ctx) r.activity.unshift({ at: ctx.now.toISOString(), actor: ctx.actor, text: `Changed: ${str(v.reason)}` });
    }
  },
  create: {
    permission: PERMISSION,
    form: form('New metro zone', 'Create zone', [field('key', 'Key (lowercase letters, digits and dashes, e.g. greater-austin)', 'text', true, { maxLength: 40, pattern: '^[a-z0-9][a-z0-9-]{1,39}$', help: 'Use 2–40 lowercase letters, digits or dashes' }), field('name', 'Zone name', 'text', true, { maxLength: 80 }), field('cities', 'Cities (one per line, “City, ST”)', 'textarea', true, { maxLength: 6000, pattern: CITY_LINES, help: CITY_HELP }), reason()], ''),
    build: (v, _id, ctx) => {
      requireReason(v);
      const key = str(v.key).toLowerCase();
      if (!/^[a-z0-9][a-z0-9-]{1,39}$/.test(key)) throw new Error('The key is 2–40 lowercase letters, digits or dashes');
      return { id: key, fields: zoneFields(key, str(v.name), parseCities(str(v.cities)).join('\n')), activity: [{ at: ctx.now.toISOString(), actor: ctx.actor, text: `Created: ${str(v.reason)}` }] };
    }
  }
};
