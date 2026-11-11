import rules from '../../../../../../tools/listing-rules/rules.json';
import { type ListingRules, ListingRulesSchema } from '../../schemas/seller';

/* The listing rules as catalog serves them (GET /v1/listing-rules?categoryCode=), built from the one rules source,
   tools/listing-rules/rules.json: the status of every field per kind and the category to kind assignment come from it.
   Only how a field is asked (its control, its answer's attribute key, options and units) is described here, the same
   fixed facts catalog's RuleField enum holds; mock-listing-rules.test.ts checks every category resolves with every field. */

interface SourceField { id: string; group: string; name: string; note: string; byKind: Record<string, string> }
interface SourceCategory { code: string; department: string; name: string; kind: string; approval: string; note: string; provisional?: boolean; overrides?: Record<string, string> }
interface Source { kinds: { code: string; name: string }[]; fields: SourceField[]; categories: SourceCategory[] }

const SOURCE = rules as unknown as Source;
const ALLERGENS = ['Contains milk', 'Contains eggs', 'Contains fish', 'Contains shellfish', 'Contains tree nuts', 'Contains peanuts', 'Contains wheat', 'Contains soy', 'Contains sesame'];

const META: Record<string, { control: string; attributeKey?: string; options?: string[]; units?: string[]; perVariant?: boolean; label?: string }> = {
  title: { control: 'text' }, brand: { control: 'brand', attributeKey: 'brand' }, product_id: { control: 'barcode', perVariant: true, label: 'Barcode or ISBN' },
  description: { control: 'long_text' }, keywords: { control: 'keywords' }, images: { control: 'images' }, label_photo: { control: 'photo' },
  condition: { control: 'condition' }, price: { control: 'money', perVariant: true }, quantity: { control: 'whole_number', perVariant: true },
  handling_days: { control: 'whole_number' }, ship_from: { control: 'ship_from' }, package_size: { control: 'package', perVariant: true },
  package_weight: { control: 'package', perVariant: true }, country_of_origin: { control: 'text', attributeKey: 'origin' }, dangerous_goods: { control: 'yes_no' },
  net_weight: { control: 'measure', attributeKey: 'net_weight', units: ['oz', 'lb', 'g', 'kg', 'fl oz', 'ml', 'l'] },
  servings: { control: 'whole_number', attributeKey: 'servings' }, shelf_life: { control: 'measure', attributeKey: 'shelf', units: ['days', 'months', 'years'] },
  storage: { control: 'choice', attributeKey: 'storage', options: ['Room temperature', 'Cool and dry', 'Refrigerate', 'Freeze'] },
  ingredients: { control: 'long_text', attributeKey: 'ingredients', label: 'Ingredients' }, allergens: { control: 'multi_choice', attributeKey: 'allergens', options: ALLERGENS, label: 'Allergens' },
  material: { control: 'text', attributeKey: 'material' }, model_number: { control: 'text', attributeKey: 'model' }, age_grade: { control: 'text', attributeKey: 'age_grade' },
  sizes: { control: 'sizes' }, permit: { control: 'document' }, brand_authorization: { control: 'document' }, safety_certificate: { control: 'document' },
  liability_insurance: { control: 'document' }, authenticity_invoice: { control: 'document' }
};
const SUPERSEDED = ['inci', 'temp', 'permit', 'safety', 'age'];
const REPLACED = [...new Set([...Object.values(META).flatMap((m) => (m.attributeKey && m.attributeKey !== 'brand' ? [m.attributeKey] : [])), ...SUPERSEDED])].sort();

/** The rules of a category code; 'general' (or a code the rules do not know, with `fallback`) answers the general goods rules. */
export function listingRulesFor(code: string): ListingRules | null {
  const general = code === 'general';
  const category = general ? SOURCE.categories.find((c) => c.kind === 'HOM') : SOURCE.categories.find((c) => c.code === code);
  if (!category) return null;
  return ListingRulesSchema.parse({
    categoryCode: general ? 'general' : category.code,
    categoryName: general ? 'General goods' : category.name,
    department: category.department,
    kind: category.kind,
    kindName: SOURCE.kinds.find((k) => k.code === category.kind)?.name ?? category.kind,
    approval: general ? 'open' : category.approval,
    note: general ? '' : category.note,
    provisional: general || Boolean(category.provisional),
    fields: SOURCE.fields.map((f) => {
      const m = META[f.id] ?? { control: 'text' };
      return {
        id: f.id, group: f.group, label: m.label ?? f.name, status: (!general && category.overrides?.[f.id]) || f.byKind[category.kind], note: f.note, control: m.control,
        attributeKey: m.attributeKey ?? null, options: m.options ?? [], units: m.units ?? [], perVariant: Boolean(m.perVariant)
      };
    }),
    replacedAttributeKeys: REPLACED
  });
}

/** Every category code of the rules (for tests). */
export const LISTING_RULE_CATEGORIES: string[] = SOURCE.categories.map((c) => c.code);
