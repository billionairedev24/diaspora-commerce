import schemas from '../../../../../../java/services/catalog/src/main/resources/taxonomy/schemas.json';
import dataset from '../../../../../../tools/taxonomy/category_tax_map.json';
import { type CategoryNode, type ProductTypeSchema, ProductTypeSchemaSchema } from '../../schemas/seller';

/* The category tax tree as the catalog serves it (13 departments > 74 categories > product types) and each product type's
   schema (details plus the category's tax questions), built from the same two files the catalog's importer reads:
   tools/taxonomy/category_tax_map.json and the catalog's taxonomy/schemas.json. Mirrors TaxonomyPlanner / TaxFactSchema. */

interface DatasetFact { id: string; label: string; kind: string; unit: string | null; owner: string; help: string | null; evidence: boolean; choices?: { value: string; label: string }[] }
interface FactRef { fact: string; required: boolean; requiredWhen: unknown; choices?: string[] }
interface DatasetCategory { code: string; department: string; name: string; status: string; facts: FactRef[]; productTypes: { code: string; label: string }[] }
interface SchemaDef { identifierRule?: string; traits?: Partial<ProductTypeSchema['traits']>; attributes?: unknown[]; themes?: unknown[]; sizing?: unknown }

const facts = new Map<string, DatasetFact>((dataset.facts as DatasetFact[]).map((f) => [f.id, f]));
const families = schemas.families as Record<string, SchemaDef>;
const NOT_ASKED = new Set(['STAFF', 'CONTEXT']);

/** The seller-facing tax questions of a category (STAFF and CONTEXT facts are never asked). */
export function taxQuestions(c: DatasetCategory): unknown[] {
  return c.facts.flatMap((ref) => {
    const f = facts.get(ref.fact);
    if (!f || NOT_ASKED.has(f.owner)) return [];
    const offered = f.kind === 'CHOICE' ? (f.choices ?? []).filter((x) => !ref.choices?.length || ref.choices.includes(x.value)) : [];
    return [{
      key: f.id, label: f.label, options: offered.map((x) => x.value), required: ref.required, multiline: false,
      kind: ({ CHOICE: 'ENUM' } as Record<string, string>)[f.kind] ?? f.kind, units: [], section: 'TAX',
      help: f.owner === 'SELLER' ? 'Yes if your business is a bakery.' : f.help, taxFact: true, owner: f.owner === 'VARIANT' ? 'VARIANT' : 'OFFER',
      unit: f.unit, showWhen: ref.requiredWhen ?? null, requiredWhen: ref.requiredWhen ?? null, evidence: f.evidence,
      optionLabels: Object.fromEntries(offered.map((x) => [x.value, x.label]))
    }];
  });
}

const familyOf = (department: string, category: string): SchemaDef => {
  const name = (schemas.categories as Record<string, string>)[category] ?? (schemas.departments as Record<string, string>)[department] ?? 'other';
  return families[name] ?? families.other ?? {};
};

const typeOf = (c: DatasetCategory, code: string, label: string): ProductTypeSchema => {
  const own = (schemas.productTypes as Record<string, SchemaDef>)[code];
  const def = own ?? familyOf(c.department, c.code);
  const traits = { food: false, perishable: false, regulated: false, preorder: false, ...def.traits, sizing: def.sizing ?? null };
  return ProductTypeSchemaSchema.parse({
    code, label, identifierRule: (def.identifierRule ?? 'GTIN').toLowerCase(),
    attributes: [...(def.attributes ?? []).map((a) => ({ ...(a as object), help: (a as { help?: string }).help ?? null })), ...taxQuestions(c)],
    themes: def.themes ?? [], traits
  });
};

const categories = dataset.categories as unknown as DatasetCategory[];
const provisional = (schemas.provisional as { code: string; department: string; name: string; status: string; productType: string }[]).map((p) => ({
  code: p.code, department: p.department, name: p.name, status: p.status, facts: [], productTypes: [{ code: p.productType, label: p.name }]
}));
const all: DatasetCategory[] = [...categories, ...provisional];

export const TAX_PRODUCT_TYPES: Record<string, ProductTypeSchema> = {};
const leafCode = (c: DatasetCategory, t: { code: string }) => `${c.code}-${t.code === c.code ? 'item' : t.code}`;

export const TAX_TREE: CategoryNode[] = (dataset.departments as { code: string; name: string }[]).map((d) => {
  const children: CategoryNode[] = all.filter((c) => c.department === d.code).map((c) => {
    const path = `${d.name} › ${c.name}`;
    return {
      id: `cat-${c.code}`, code: c.code, name: c.name, level: 'CATEGORY', productType: null, path, taxStatus: c.status as CategoryNode['taxStatus'],
      children: c.productTypes.map((t) => {
        TAX_PRODUCT_TYPES[t.code] = typeOf(c, t.code, t.label);
        return { id: `pt-${leafCode(c, t)}`, code: leafCode(c, t), name: t.label, level: 'PRODUCT_TYPE', productType: t.code, path: `${path} › ${t.label}`, children: [] };
      })
    };
  });
  return { id: `dep-${d.code}`, code: d.code, name: d.name, level: 'DEPARTMENT', productType: null, path: d.name, children };
});
