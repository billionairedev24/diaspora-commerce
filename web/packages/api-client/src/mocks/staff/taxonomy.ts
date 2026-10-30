/* Mock of Catalog → Categories (/v1/staff/taxonomy/**, catalog's taxonomy API through the BFF) and the category-request
   queue (the `category-requests` table). The tree, the product-type details, versions for If-Match (412
   VERSION_CONFLICT), reasons, the audit trail and the refusal to retire a category products still use mirror the Go BFF
   (internal/app/staff_taxonomy.go) over catalog-service. */
import { http } from 'msw';
import { z } from 'zod';

import { type FieldValue, type FormField, type StaffForm } from '../../schemas/staff';
import {
  type BrandBulkResult,
  type BrandStatus,
  type CategoryApprovalPlan,
  type StaffBrand,
  type StaffBrandList,
  type TaxonomyAttribute,
  type TaxonomyAudit,
  type TaxonomyAuditItem,
  type TaxonomyLevel,
  type TaxonomyNode,
  type TaxonomyNodes,
  type TaxonomyProductType,
  type TaxonomyTraits,
  type TaxonomyTree
} from '../../staff-taxonomy';
import { getStaffDb, type MockRecord, type Scalar } from './db';
import { allows, levelOf } from './model';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';
import { type ActionCtx, type MockResource } from './resources';

type Level = 'VIEW' | 'EDIT' | 'APPROVE' | 'FULL';
const RANK: Record<Level, number> = { VIEW: 1, EDIT: 2, APPROVE: 3, FULL: 4 };

export interface MockTaxonomyState {
  nodes: TaxonomyNode[];
  types: Omit<TaxonomyProductType, 'usedBy' | 'productCount'>[];
  audit: (TaxonomyAuditItem & { subjectId: string })[];
  /** The brand registry (catalog V13): approved brands, the review queue and decided ones. */
  brands: MockBrand[];
  seq: number;
}

export type MockBrand = Omit<StaffBrand, 'scope'> & { scope: string[] };

const PERMISSION = 'catalog:taxonomy';
const NEW_TYPE = '__new__';
const slug = (s: string) =>
  s
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
const lower = (s: string) => s.trim().toLowerCase();

const attr = (key: string, label: string, extra: Partial<TaxonomyAttribute> = {}): TaxonomyAttribute => ({
  key,
  label,
  kind: 'TEXT',
  options: [],
  units: [],
  required: true,
  section: 'DETAILS',
  help: null,
  applies: true,
  ...extra
});
const choice = (key: string, label: string, options: string[], extra: Partial<TaxonomyAttribute> = {}) => attr(key, label, { kind: 'ENUM', options, ...extra });
const BRAND = attr('brand', 'Brand or label', { kind: 'BRAND', options: ['UNBRANDED', 'HANDMADE'], help: 'The label on the garment. Choose “No brand” or “Handmade” if it has none.' });
const BRAND_REQUIRED = attr('brand', 'Brand', { kind: 'BRAND', help: 'The brand printed on the product.' });
const BRAND_OPTIONAL = attr('brand', 'Brand', { kind: 'BRAND', required: false, options: ['UNBRANDED'], help: 'The brand on the package.' });
const ORIGIN = attr('origin', 'Made in');
const traits = (t: Partial<TaxonomyTraits> = {}): TaxonomyTraits => ({ food: false, perishable: false, regulated: false, preorder: false, ...t });

const TYPES: MockTaxonomyState['types'] = [
  {
    code: 'dress',
    label: 'Women’s dresses',
    identifierRule: 'GTIN',
    traits: traits(),
    version: 0,
    updatedAt: null,
    themes: [{ name: 'Size × Color' }],
    attributes: [
      BRAND,
      choice('material', 'Material', ['Cotton wax print', 'Cotton', 'Lace', 'Silk', 'Polyester blend']),
      choice('fit', 'Fit', ['Regular', 'Fitted', 'Relaxed', 'Plus size']),
      choice('length', 'Length', ['Mini', 'Knee', 'Midi', 'Maxi']),
      attr('care', 'Care'),
      ORIGIN
    ]
  },
  {
    code: 'top',
    label: 'Women’s tops & wrappers',
    identifierRule: 'GTIN',
    traits: traits(),
    version: 0,
    updatedAt: null,
    themes: [{ name: 'Size × Color' }],
    attributes: [BRAND, choice('material', 'Material', ['Cotton wax print', 'Cotton', 'Aso-oke', 'Lace']), attr('care', 'Care'), ORIGIN]
  },
  {
    code: 'grocery',
    label: 'Groceries',
    identifierRule: 'GTIN',
    traits: traits({ food: true, preorder: true }),
    version: 0,
    updatedAt: null,
    themes: [{ name: 'Option' }],
    attributes: [
      BRAND_OPTIONAL,
      attr('ingredients', 'Ingredients', { kind: 'MULTILINE', section: 'FOOD' }),
      choice('allergens', 'Allergens', ['None', 'Contains peanuts', 'Contains gluten', 'Contains soy'], { section: 'FOOD' }),
      attr('shelf', 'Shelf life', { kind: 'NUMBER', units: ['days', 'months'], section: 'FOOD', help: 'From the date of manufacture, unopened' }),
      choice('storage', 'Storage', ['Room temperature', 'Refrigerate', 'Freeze'], { section: 'FOOD' }),
      choice('tax_class', 'What best describes this food? (used for sales tax)', ['Packaged, shelf-stable', 'Fresh fruit or vegetables', 'Candy or sweets'], { required: false }),
      ORIGIN
    ]
  },
  {
    code: 'beauty',
    label: 'Beauty & personal care',
    identifierRule: 'GTIN',
    traits: traits({ regulated: true }),
    version: 0,
    updatedAt: null,
    themes: [{ name: 'Option' }],
    attributes: [
      BRAND_REQUIRED,
      attr('inci', 'Ingredients (INCI)', { kind: 'MULTILINE' }),
      choice('skin', 'Skin type', ['All', 'Dry', 'Oily', 'Sensitive']),
      attr('mocra', 'MoCRA product listing number', { section: 'REGULATED', help: 'FDA Cosmetics Direct product listing number (MoCRA)' }),
      ORIGIN
    ]
  },
  {
    code: 'book',
    label: 'Books',
    identifierRule: 'ISBN',
    traits: traits(),
    version: 0,
    updatedAt: null,
    themes: [{ name: 'Option' }],
    attributes: [
      attr('author', 'Author'),
      attr('publisher', 'Publisher'),
      choice('language', 'Language', ['English', 'Yoruba', 'French', 'Igbo']),
      attr('pages', 'Pages', { kind: 'NUMBER', required: false })
    ]
  },
  {
    code: 'home',
    label: 'Home & decor',
    identifierRule: 'GTIN',
    traits: traits(),
    version: 0,
    updatedAt: null,
    themes: [{ name: 'Option' }],
    attributes: [BRAND, attr('material', 'Material'), attr('dims', 'Dimensions', { help: 'e.g. 18 × 18 in' }), ORIGIN]
  },
  {
    code: 'custom',
    label: 'A general product',
    identifierRule: 'GTIN',
    traits: traits(),
    version: 0,
    updatedAt: null,
    themes: [{ name: 'Option' }, { name: 'Size × Color' }],
    attributes: [BRAND, attr('material', 'Material'), attr('details', 'Details', { kind: 'MULTILINE' }), ORIGIN]
  }
];

/** department › category › product type, with the seeded product counts on the leaves. */
const SEED: [string, [string, [string, string, number][]][]][] = [
  [
    'Fashion',
    [
      ['Women', [['Dresses', 'dress', 42], ['Tops', 'top', 31], ['Wrappers & skirts', 'top', 12]]],
      ['Men', [['Men’s clothing', 'top', 18]]]
    ]
  ],
  ['Groceries', [['Pantry', [['Spices & seasonings', 'grocery', 64], ['Grains & rice', 'grocery', 27]]]]],
  ['Beauty', [['Skin & hair', [['Body & skin care', 'beauty', 38], ['Hair care', 'beauty', 0]]]]],
  ['Books', [['All books', [['Books', 'book', 9]]]]],
  ['Home', [['Decor', [['Home & decor', 'home', 15]]]]]
];

export function seedTaxonomy(): MockTaxonomyState {
  const nodes: TaxonomyNode[] = [];
  let seq = 0;
  const node = (parent: TaxonomyNode | null, name: string, level: TaxonomyLevel, position: number, type: string | null, count: number): TaxonomyNode => {
    seq += 1;
    const n: TaxonomyNode = {
      id: `tax-${String(seq).padStart(3, '0')}`,
      parentId: parent?.id ?? null,
      code: slug(parent ? `${parent.code}-${name}` : name),
      name,
      level,
      productType: type,
      productTypeLabel: type ? (TYPES.find((t) => t.code === type)?.label ?? null) : null,
      path: parent ? `${parent.path} › ${name}` : name,
      position,
      active: true,
      retiredAt: null,
      retiredReason: null,
      version: 0,
      productCount: count,
      subtreeProductCount: count,
      childCount: 0
    };
    nodes.push(n);
    return n;
  };
  SEED.forEach(([dept, categories], di) => {
    const d = node(null, dept, 'DEPARTMENT', di, null, 0);
    categories.forEach(([cat, leaves], ci) => {
      const c = node(d, cat, 'CATEGORY', ci, null, 0);
      leaves.forEach(([name, type, count], li) => node(c, name, 'PRODUCT_TYPE', li, type, count));
    });
  });
  return { nodes, types: structuredClone(TYPES), audit: [], brands: seedBrands(nodes), seq };
}

/** Approved brands and a review queue, scoped to the departments they were entered in. */
function seedBrands(nodes: TaxonomyNode[]): MockBrand[] {
  const dept = (name: string) => nodes.find((n) => n.level === 'DEPARTMENT' && n.name === name)?.id ?? '';
  const at = '2026-10-01T10:00:00.000Z';
  const brand = (id: string, name: string, status: BrandStatus, department: string, by: string | null, products: number, sellers: number): MockBrand => ({
    id, name, status, mergedIntoId: null, mergedIntoName: null, createdByName: by, createdAt: at, updatedAt: at, version: 0, decidedBy: null, decidedAt: null,
    decisionReason: null, products, sellers, scope: [dept(department)]
  });
  return [
    brand('brand-nike', 'Nike', 'APPROVED', 'Fashion', null, 120, 18),
    brand('brand-levis', 'Levi’s', 'APPROVED', 'Fashion', null, 64, 9),
    brand('brand-maggi', 'Maggi', 'APPROVED', 'Groceries', null, 310, 41),
    brand('brand-cantu', 'Cantu', 'APPROVED', 'Beauty', null, 77, 12),
    brand('brand-cotedivoire', 'Cotedivoire', 'PENDING', 'Fashion', 'Lagos Threads', 2, 1),
    brand('brand-nikee', 'Nikee', 'PENDING', 'Fashion', 'Accra Spice Co.', 1, 1),
    brand('brand-adire', 'Adire Lagos', 'PENDING', 'Fashion', 'Nile Beauty', 5, 3),
    brand('brand-shea', 'Shea Gold', 'PENDING', 'Beauty', 'Nile Beauty', 3, 1)
  ];
}

/** Counts and children computed from the stored nodes (the tree is read, never stored with them). */
function withCounts(nodes: TaxonomyNode[]): TaxonomyNode[] {
  const children = new Map<string | null, TaxonomyNode[]>();
  nodes.forEach((n) => children.set(n.parentId, [...(children.get(n.parentId) ?? []), n]));
  const total = (n: TaxonomyNode): number => n.productCount + (children.get(n.id) ?? []).reduce((sum, c) => sum + total(c), 0);
  return nodes.map((n) => ({ ...n, subtreeProductCount: total(n), childCount: (children.get(n.id) ?? []).length }));
}

const PARENT_LEVEL: Record<TaxonomyLevel, TaxonomyLevel | null> = { DEPARTMENT: null, CATEGORY: 'DEPARTMENT', PRODUCT_TYPE: 'CATEGORY' };

/* ── category requests (the review queue) ────────────────── */

const field = (key: string, label: string, type: FormField['type'], required: boolean, extra: Partial<FormField> = {}): FormField => ({ key, label, type, required, ...extra });
const str = (v: FieldValue | Scalar | undefined): string => (v === null || v === undefined ? '' : String(v));

export function seedCategoryRequests(now: Date): MockRecord[] {
  const ago = (d: number) => new Date(now.getTime() - d * 86_400_000).toISOString();
  const rec = (id: string, ref: string, seller: string, parentPath: string, name: string, example: string, details: string, waiting: number, status: string, days: number): MockRecord => ({
    id,
    fields: { reference: ref, orgName: seller, parentPath, name, path: `${parentPath} › ${name}`, exampleProduct: example, detailsNeeded: details, productsWaiting: waiting, status, createdAt: ago(days), decisionNote: null, decidedAt: null },
    activity: [{ at: ago(days), actor: seller, text: 'Requested the category' }],
    lines: Array.from({ length: waiting }, (_, i) => ({ id: `${id}-p${i}`, atin: `AT${String(70_000_000 + i * 31 + days)}`, title: `${example} ${i + 1}` }))
  });
  return [
    rec('req-1', 'REQ-000041', 'Lagos Threads', 'Fashion › Women', 'Bridal aso-ebi sets', '6-piece aso-ebi set', 'Pieces, colour, fabric, sizes', 2, 'PENDING_REVIEW', 3),
    rec('req-2', 'REQ-000042', 'Accra Spice Co.', 'Groceries', 'Hot sauces', 'Scotch bonnet hot sauce 250 ml', 'Heat level, ingredients, allergens', 1, 'PENDING_REVIEW', 1),
    rec('req-3', 'REQ-000039', 'Nile Beauty', 'Beauty › Skin & hair', 'Hair care', 'Shea hair butter', 'Hair type, ingredients', 0, 'REJECTED', 12)
  ];
}

const treeOptions = (): { value: string; label: string }[] => {
  const t = getStaffDb().taxonomy;
  return t.nodes
    .filter((n) => n.active && n.level !== 'PRODUCT_TYPE')
    .map((n) => ({ value: n.id, label: n.level === 'DEPARTMENT' ? `${n.path} (new category is created)` : n.path }))
    .sort((a, b) => a.label.localeCompare(b.label));
};

const typeOptions = (): { value: string; label: string }[] => [
  { value: NEW_TYPE, label: 'Create a new product type for this category' },
  ...getStaffDb()
    .taxonomy.types.filter((t) => t.code !== 'custom')
    .map((t) => ({ value: t.code, label: `${t.label} (${t.attributes.filter((a) => a.applies).length} details)` }))
    .sort((a, b) => a.label.localeCompare(b.label))
];

/** What approving a request with these choices creates; throws the reason it cannot (shown on the form). */
export function planApproval(t: MockTaxonomyState, request: MockRecord, v: Record<string, FieldValue | Scalar | undefined>, pending: number): CategoryApprovalPlan {
  const parent = t.nodes.find((n) => n.id === str(v.parentCategoryId));
  if (!parent || !parent.active || parent.level === 'PRODUCT_TYPE') throw new PlanError({ parentCategoryId: 'Choose a department or a category' });
  const name = str(request.fields.name);
  const nodes: CategoryApprovalPlan['nodes'] = [];
  let leafParentPath = parent.path;
  let leafParent: TaxonomyNode | undefined = parent;
  if (parent.level === 'DEPARTMENT') {
    const catName = str(v.categoryName).trim() || name;
    const existing = t.nodes.find((n) => n.parentId === parent.id && lower(n.name) === lower(catName));
    leafParent = existing;
    leafParentPath = `${parent.path} › ${existing?.name ?? catName}`;
    nodes.push({ level: 'CATEGORY', name: existing?.name ?? catName, path: leafParentPath, created: !existing });
  }
  if (leafParent && t.nodes.some((n) => n.parentId === leafParent.id && lower(n.name) === lower(name))) {
    throw new PlanError({ name: `There is already a ${name} in ${leafParent.path}` });
  }
  nodes.push({ level: 'PRODUCT_TYPE', name, path: `${leafParentPath} › ${name}`, created: true });
  const choiceType = str(v.productType);
  if (!choiceType) throw new PlanError({ productType: 'Choose a product type' });
  if (choiceType === NEW_TYPE) {
    const base = t.types.find((x) => x.code === (str(v.basedOn) || 'custom'));
    if (!base) throw new PlanError({ basedOn: 'Choose a product type to start from' });
    return { nodes, productType: { code: slug(name).replace(/-/g, '_'), label: name, created: true, attributeCount: base.attributes.filter((a) => a.applies).length }, productsMoving: pending };
  }
  const existingType = t.types.find((x) => x.code === choiceType && x.code !== 'custom');
  if (!existingType) throw new PlanError({ productType: `Unknown product type ${choiceType}` });
  return { nodes, productType: { code: existingType.code, label: existingType.label, created: false, attributeCount: existingType.attributes.filter((a) => a.applies).length }, productsMoving: pending };
}

export class PlanError extends Error {
  constructor(readonly errors: Record<string, string>) {
    super(Object.values(errors)[0] ?? 'Cannot approve');
  }
}

function planLines(plan: CategoryApprovalPlan): string[] {
  const out = plan.nodes.map((n) => `${n.level === 'PRODUCT_TYPE' ? 'Product type' : 'Category'}: ${n.path} — ${n.created ? 'will be created' : 'already exists'}`);
  out.push(`Details sellers fill in: ${plan.productType.label} (${plan.productType.created ? 'new schema' : 'existing schema'}, ${plan.productType.attributeCount} attributes)`);
  if (plan.productsMoving > 0) out.push(`Products waiting: ${plan.productsMoving} move into listing review`);
  return out;
}

/** The `category-requests` table of the mock records service. */
export const categoryRequestsDef: MockResource = {
  title: 'Category requests',
  columns: [
    { key: 'reference', label: 'Request', kind: 'mono', width: 11, sortable: true },
    { key: 'orgName', label: 'Seller', kind: 'strong', width: 18, sortable: true },
    { key: 'path', label: 'Requested category', kind: 'text', width: 30, sortable: true },
    { key: 'productsWaiting', label: 'Products waiting', kind: 'number', width: 10, sortable: true },
    { key: 'createdAt', label: 'Requested', kind: 'datetime', width: 14, sortable: true },
    { key: 'status', label: 'Status', kind: 'status', width: 12, sortable: true }
  ],
  statusKey: 'status',
  facets: [
    { value: 'PENDING_REVIEW', label: 'Pending review' },
    { value: 'APPROVED', label: 'Approved' },
    { value: 'REJECTED', label: 'Rejected' }
  ],
  search: ['reference', 'orgName', 'path'],
  searchPlaceholder: 'Category, seller or reference',
  exportable: true,
  titleOf: (r) => str(r.fields.name),
  subtitleOf: (r) => `${str(r.fields.reference)} · ${str(r.fields.orgName)} · ${str(r.fields.path)}`,
  sections: [
    ['Request', ['orgName', 'path', 'parentPath', 'exampleProduct', 'detailsNeeded', 'createdAt', 'status', 'decisionNote', 'decidedAt']]
  ],
  linesTitle: 'Products waiting for this category',
  lineColumns: [
    { key: 'atin', label: 'ATIN', kind: 'mono', width: 20, sortable: false },
    { key: 'title', label: 'Product', kind: 'strong', width: 80, sortable: false }
  ],
  links: () => [{ label: 'Open the category tree', href: '/moderation?tab=categories' }],
  actions: [
    {
      key: 'approve',
      label: 'Approve & create',
      tone: 'primary',
      permission: PERMISSION,
      level: 'APPROVE',
      when: (r) => (str(r.fields.status) === 'PENDING_REVIEW' ? null : `Not possible while ${str(r.fields.status).toLowerCase().replace(/_/g, ' ')}.`),
      form: (r): StaffForm => {
        const t = getStaffDb().taxonomy;
        const suggested = t.nodes.find((n) => n.path === str(r.fields.parentPath) && n.level !== 'PRODUCT_TYPE');
        let intro = 'Approving creates the category and its product type in one step, then moves the products waiting for it into listing review. Everything is recorded with your name.';
        if (suggested) {
          try {
            intro += ` As suggested: ${planLines(planApproval(t, r, { parentCategoryId: suggested.id, productType: NEW_TYPE }, Number(r.fields.productsWaiting ?? 0))).join('; ')}.`;
          } catch {
            /* the plan is explained when the reviewer chooses */
          }
        }
        return {
          title: 'Approve and create',
          submitLabel: 'Approve & create',
          intro,
          fields: [
            field('parentCategoryId', 'Where it goes', 'select', true, { options: treeOptions(), ...(suggested ? { value: suggested.id } : {}) }),
            field('categoryName', 'Category name (only when it goes under a department)', 'text', false, { maxLength: 80, help: 'Defaults to the requested name' }),
            field('productType', 'Details sellers fill in', 'select', true, { options: typeOptions(), value: NEW_TYPE }),
            field('basedOn', 'A new product type starts from', 'select', false, {
              options: [{ value: 'custom', label: 'A general product (material, details, brand, made in)' }, ...typeOptions().slice(1)],
              value: 'custom',
              help: 'Refine its details afterwards under Categories'
            }),
            field('note', 'Note to the seller', 'textarea', false, { maxLength: 1000, help: 'Shown to the seller with the decision' })
          ]
        };
      },
      apply: (r, v, ctx) => approveRequest(r, v, ctx)
    },
    {
      key: 'reject',
      label: 'Reject',
      tone: 'danger',
      permission: PERMISSION,
      level: 'APPROVE',
      when: (r) => (str(r.fields.status) === 'PENDING_REVIEW' ? null : `Not possible while ${str(r.fields.status).toLowerCase().replace(/_/g, ' ')}.`),
      form: (): StaffForm => ({
        title: 'Reject the category request',
        submitLabel: 'Reject',
        intro: 'The seller sees your reason. Products they built against this request are rejected with it.',
        fields: [field('note', 'Reason', 'textarea', true, { maxLength: 1000, help: 'At least 3 characters' })]
      }),
      apply: (r, v, ctx) => {
        r.fields.status = 'REJECTED';
        r.fields.decisionNote = str(v.note);
        r.fields.decidedAt = ctx.now.toISOString();
        r.fields.productsWaiting = 0;
        r.activity.unshift({ at: ctx.now.toISOString(), actor: ctx.actor, text: `Rejected: ${str(v.note)}` });
        const t = getStaffDb().taxonomy;
        recordAudit(t, ctx, 'REQUEST_REJECTED', 'Request rejected', 'REQUEST', r.id, `${str(r.fields.reference)} · ${str(r.fields.path)}`, str(v.note), `Rejected ${str(r.fields.path)}`);
      }
    }
  ]
};

function approveRequest(r: MockRecord, v: Record<string, FieldValue>, ctx: ActionCtx): void {
  const t = getStaffDb().taxonomy;
  const waiting = Number(r.fields.productsWaiting ?? 0);
  let plan: CategoryApprovalPlan;
  try {
    plan = planApproval(t, r, v, waiting);
  } catch (e) {
    if (e instanceof PlanError && ctx.fail) ctx.fail(e.errors);
    throw e;
  }
  let parent = t.nodes.find((n) => n.id === str(v.parentCategoryId)) as TaxonomyNode;
  const at = ctx.now.toISOString();
  const typeCode = ((): string => {
    if (!plan.productType.created) return plan.productType.code;
    const base = t.types.find((x) => x.code === (str(v.basedOn) || 'custom'));
    t.types.push({ code: plan.productType.code, label: plan.productType.label, identifierRule: base?.identifierRule ?? 'GTIN', traits: { ...(base?.traits ?? traits()) }, attributes: structuredClone(base?.attributes ?? []), themes: structuredClone(base?.themes ?? []), version: 0, updatedAt: at });
    return plan.productType.code;
  })();
  const add = (p: TaxonomyNode, name: string, level: TaxonomyLevel, type: string | null): TaxonomyNode => {
    t.seq += 1;
    const siblings = t.nodes.filter((n) => n.parentId === p.id);
    const n: TaxonomyNode = {
      id: `tax-${String(t.seq).padStart(3, '0')}`, parentId: p.id, code: slug(`${p.code}-${name}`), name, level, productType: type,
      productTypeLabel: type ? (t.types.find((x) => x.code === type)?.label ?? null) : null, path: `${p.path} › ${name}`,
      position: siblings.length, active: true, retiredAt: null, retiredReason: null, version: 0, productCount: 0, subtreeProductCount: 0, childCount: 0
    };
    t.nodes.push(n);
    return n;
  };
  if (parent.level === 'DEPARTMENT') {
    const planned = plan.nodes[0] as CategoryApprovalPlan['nodes'][number];
    parent = t.nodes.find((n) => n.parentId === parent.id && lower(n.name) === lower(planned.name)) ?? add(parent, planned.name, 'CATEGORY', null);
  }
  const leaf = add(parent, str(r.fields.name), 'PRODUCT_TYPE', typeCode);
  leaf.productCount = waiting;
  r.fields.status = 'APPROVED';
  r.fields.decisionNote = str(v.note) || null;
  r.fields.decidedAt = at;
  r.fields.productsWaiting = 0;
  r.lines = [];
  r.activity.unshift({ at, actor: ctx.actor, text: `Approved: created ${leaf.path}` });
  recordAudit(t, ctx, 'REQUEST_APPROVED', 'Request approved', 'REQUEST', r.id, `${str(r.fields.reference)} · ${str(r.fields.path)}`, `Category request ${str(r.fields.reference)} approved${str(v.note) ? `: ${str(v.note)}` : ''}`, `Approved and created ${leaf.path}`);
}

function recordAudit(t: MockTaxonomyState, ctx: { actor: string; now: Date }, action: string, actionLabel: string, kind: TaxonomyAuditItem['subjectKind'], subjectId: string, subjectLabel: string, reason: string, summary: string) {
  t.seq += 1;
  t.audit.unshift({ id: `aud-${t.seq}`, at: ctx.now.toISOString(), actorName: ctx.actor, action, actionLabel, subjectKind: kind, subjectId, subjectLabel, reason, summary });
}

/* ── handlers ───────────────────────────────────────────── */

const Reason = z.string().trim().min(3, 'Say why you are making this change (at least 3 characters)').max(1000);
const Name = z.string().trim().min(2, '2 to 80 characters').max(80, '2 to 80 characters').refine((n) => !n.includes('›'), 'Remove the › character');
const AttributeBody = z.object({
  key: z.string().trim().regex(/^[a-z][a-z0-9_]{1,39}$/, 'Lower-case letters, digits and _, starting with a letter'),
  label: z.string().trim().min(1, 'Give it a label').max(120),
  kind: z.enum(['TEXT', 'MULTILINE', 'ENUM', 'NUMBER', 'BRAND']),
  options: z.array(z.string().trim().max(80)).default([]),
  units: z.array(z.string().trim().max(20)).default([]),
  required: z.boolean().default(false),
  section: z.enum(['DETAILS', 'FOOD', 'REGULATED']).default('DETAILS'),
  help: z.string().trim().max(200).nullish(),
  applies: z.boolean().default(true)
});
const TraitsBody = z.object({ food: z.boolean().default(false), perishable: z.boolean().default(false), regulated: z.boolean().default(false), preorder: z.boolean().default(false) });
const NewTypeBody = z.object({
  code: z.string().regex(/^[a-z][a-z0-9_]{1,39}$/).optional(),
  label: z.string().trim().min(2).max(80).optional(),
  identifierRule: z.enum(['GTIN', 'ISBN']),
  traits: TraitsBody.optional(),
  attributes: z.array(AttributeBody).max(40).default([]),
  basedOn: z.string().optional()
});

export function taxonomyHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, json, problem } = deps;
  const forbidden = (detail: string) => problem(403, 'FORBIDDEN', 'Forbidden', detail);
  const invalid = (errors: Record<string, string>) => problem(422, 'VALIDATION_FAILED', 'Validation failed', Object.values(errors)[0] ?? 'One or more fields are invalid.', errors);
  const st = (ctx: PreorderCtx) => ctx.db.taxonomy;
  const view = (ctx: PreorderCtx) => {
    if (!allows(ctx.roles, 'catalog')) throw forbidden('Your roles can’t open catalog.');
    if (!ctx.permissions.includes(PERMISSION)) throw forbidden(`This needs the ${PERMISSION} permission.`);
  };
  const rank = (ctx: PreorderCtx) => RANK[(levelOf(ctx.roles, 'catalog') ?? 'VIEW') as Level];
  const canEdit = (ctx: PreorderCtx) => rank(ctx) >= RANK.EDIT;
  const edit = (ctx: PreorderCtx) => {
    view(ctx);
    if (!canEdit(ctx)) throw forbidden('Your roles can’t change catalog.');
  };
  const ifMatch = (ctx: PreorderCtx, version: number, what: string) => {
    const raw = ctx.request.headers.get('if-match');
    if (!raw) throw invalid({ version: 'Reload the page and try again' });
    if (raw.replace(/^W\//, '').replace(/"/g, '') !== String(version)) {
      throw problem(412, 'VERSION_CONFLICT', 'Changed by someone else', `${what} was changed by someone else. Refresh to see the latest.`);
    }
  };
  const nodeOf = (ctx: PreorderCtx, id: string | undefined) => {
    const n = st(ctx).nodes.find((x) => x.id === id);
    if (!n) throw problem(404, 'NOT_FOUND', 'Not found', 'No such category.');
    return n;
  };
  const typeOf = (ctx: PreorderCtx, code: string | undefined) => {
    const t = st(ctx).types.find((x) => x.code === code);
    if (!t) throw problem(404, 'NOT_FOUND', 'Not found', 'No such product type.');
    return t;
  };
  const view1 = (ctx: PreorderCtx, n: TaxonomyNode) => withCounts(st(ctx).nodes).find((x) => x.id === n.id) as TaxonomyNode;
  const subtree = (ctx: PreorderCtx, n: TaxonomyNode): TaxonomyNode[] => [n, ...st(ctx).nodes.filter((c) => c.parentId === n.id).flatMap((c) => subtree(ctx, c))];
  const rebase = (ctx: PreorderCtx, n: TaxonomyNode, oldPath: string) => {
    subtree(ctx, n).forEach((d) => {
      if (d !== n) d.path = n.path + d.path.slice(oldPath.length);
    });
  };
  const typeView = (ctx: PreorderCtx, t: MockTaxonomyState['types'][number]): TaxonomyProductType => ({
    ...t,
    productCount: st(ctx).nodes.filter((n) => n.productType === t.code).reduce((sum, n) => sum + n.productCount, 0),
    usedBy: st(ctx).nodes.filter((n) => n.productType === t.code).map((n) => ({ nodeId: n.id, path: n.path, active: n.active })),
    canEdit: canEdit(ctx)
  });
  const log = (ctx: PreorderCtx, action: string, kind: TaxonomyAuditItem['subjectKind'], id: string, label: string, reason: string, summary: string) =>
    recordAudit(st(ctx), { actor: ctx.action.actor, now: ctx.now }, action, ACTION_LABELS[action] ?? action, kind, id, label, reason, summary);
  const freeName = (ctx: PreorderCtx, parentId: string | null, name: string, except?: string) => {
    if (st(ctx).nodes.some((n) => n.parentId === parentId && n.id !== except && lower(n.name) === lower(name))) {
      throw invalid({ name: `There is already one called ${name} here` });
    }
  };
  const attributeProblems = (attrs: z.infer<typeof AttributeBody>[], t: TaxonomyTraits, current?: TaxonomyAttribute[]) => {
    const errors: Record<string, string> = {};
    const seen = new Set<string>();
    attrs.forEach((a, i) => {
      if (seen.has(a.key)) errors[`attributes[${i}].key`] = 'Used twice';
      seen.add(a.key);
      if (a.kind === 'ENUM' && a.options.filter(Boolean).length === 0) errors[`attributes[${i}].options`] = 'Add the values to choose from';
      if (a.kind !== 'ENUM' && a.kind !== 'BRAND' && a.options.filter(Boolean).length > 0) errors[`attributes[${i}].options`] = 'Only choice attributes have values';
      if (a.applies && a.section === 'FOOD' && !t.food) errors[`attributes[${i}].section`] = 'The food section needs the food trait';
      if (a.applies && a.section === 'REGULATED' && !t.regulated) errors[`attributes[${i}].section`] = 'The regulated section needs the regulated trait';
    });
    const tax = current?.find((a) => a.key === 'tax_class');
    if (tax) {
      const next = attrs.find((a) => a.key === 'tax_class');
      if (!next) errors.attributes = 'The sales-tax question (tax_class) cannot be removed; stop asking it instead';
      else if (next.options.join('|') !== tax.options.join('|') || next.label !== tax.label || next.kind !== tax.kind) errors.attributes = 'The sales-tax question’s wording and choices are set by tax-returns';
    }
    return errors;
  };
  const toAttribute = (a: z.infer<typeof AttributeBody>): TaxonomyAttribute => ({
    key: a.key, label: a.label, kind: a.kind, options: a.kind === 'ENUM' || a.kind === 'BRAND' ? a.options.filter(Boolean) : [], units: a.kind === 'NUMBER' ? a.units : [],
    required: a.applies && a.required, section: a.section, help: a.help || null, applies: a.applies, multiline: a.kind === 'MULTILINE'
  });
  const newType = (ctx: PreorderCtx, b: z.infer<typeof NewTypeBody>, label: string, reason: string) => {
    const t = traits(b.traits);
    if (t.perishable && !t.food) throw invalid({ traits: 'Only food can be perishable' });
    const errors = attributeProblems(b.attributes, t);
    if (Object.keys(errors).length) throw invalid(errors);
    let code = b.code ?? (slug(label).replace(/-/g, '_') || 'type');
    if (!/^[a-z]/.test(code)) code = `type_${code}`;
    for (let i = 2; st(ctx).types.some((x) => x.code === code); i += 1) {
      if (b.code) throw invalid({ code: 'Already used by another product type' });
      code = `${code.replace(/_\d+$/, '')}_${i}`;
    }
    const base = st(ctx).types.find((x) => x.code === (b.basedOn || 'custom'));
    const created = { code, label, identifierRule: b.identifierRule, traits: t, attributes: b.attributes.map(toAttribute), themes: structuredClone(base?.themes ?? []), version: 0, updatedAt: ctx.now.toISOString() };
    st(ctx).types.push(created);
    log(ctx, 'TYPE_CREATED', 'PRODUCT_TYPE', code, label, reason, `Created product type ${label} with ${created.attributes.length} attributes`);
    return created;
  };
  const brandOf = (ctx: PreorderCtx, id: string | undefined) => {
    const b = st(ctx).brands.find((x) => x.id === id);
    if (!b) throw problem(404, 'NOT_FOUND', 'Not found', 'No such brand.');
    return b;
  };
  const brandKey = (s: string) => s.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '');
  const brandView = (ctx: PreorderCtx, b: MockBrand): StaffBrand => ({
    ...b,
    scope: b.scope.flatMap((id) => {
      const n = st(ctx).nodes.find((x) => x.id === id);
      return n ? [{ nodeId: n.id, path: n.path, level: n.level }] : [];
    })
  });
  /** Another live brand with the name whose scope overlaps (one node is, or lies under, the other). */
  const brandClash = (ctx: PreorderCtx, name: string, scope: string[], except?: string) => {
    const under = (a: string, b: string) => {
      for (let n = st(ctx).nodes.find((x) => x.id === a); n; n = st(ctx).nodes.find((x) => x.id === n?.parentId)) if (n.id === b) return true;
      return false;
    };
    return st(ctx).brands.find((o) => o.id !== except && (o.status === 'APPROVED' || o.status === 'PENDING') && brandKey(o.name) === brandKey(name) && o.scope.some((a) => scope.some((b) => under(a, b) || under(b, a))));
  };
  const brandScope = (ctx: PreorderCtx, scope: string[]) => {
    const missing = scope.find((id) => !st(ctx).nodes.some((n) => n.id === id && n.active));
    if (scope.length === 0 || missing) throw invalid({ scope: 'Choose where the brand applies' });
    return [...new Set(scope)];
  };
  const brandReload = (ctx: PreorderCtx, b: MockBrand, version: number) => {
    ifMatch(ctx, version, b.name);
  };
  const decide = (ctx: PreorderCtx, b: MockBrand, status: BrandStatus, action: string, reason: string, summary: string, extra: Partial<MockBrand> = {}) => {
    Object.assign(b, { status, version: b.version + 1, decidedBy: ctx.action.actor, decidedAt: ctx.now.toISOString(), decisionReason: reason, updatedAt: ctx.now.toISOString() }, extra);
    log(ctx, action, 'BRAND', b.id, b.name, reason, summary);
  };
  const T = `${W}/taxonomy`;
  return [
    http.get(
      `${T}/brands`,
      route((ctx): StaffBrandList => {
        view(ctx);
        const params = new URL(ctx.request.url).searchParams;
        const statuses = (params.get('status') ?? '').split(',').filter(Boolean);
        const q = brandKey(params.get('q') ?? '');
        const scope = params.get('scope');
        const rows = st(ctx).brands
          .filter((b) => (statuses.length === 0 || statuses.includes(b.status)) && (!q || brandKey(b.name).includes(q)) && (!scope || b.scope.includes(scope)))
          .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || a.name.localeCompare(b.name));
        const offset = Number(params.get('offset') ?? 0);
        const limit = Number(params.get('limit') ?? 50);
        const counts: Record<string, number> = { PENDING: 0, APPROVED: 0, REJECTED: 0, MERGED_INTO: 0, RETIRED: 0 };
        st(ctx).brands.forEach((b) => (counts[b.status] = (counts[b.status] ?? 0) + 1));
        return { brands: rows.slice(offset, offset + limit).map((b) => brandView(ctx, b)), total: rows.length, counts, canEdit: canEdit(ctx) };
      })
    ),
    http.post(
      `${T}/brands`,
      route(async (ctx) => {
        const b = await json(ctx.request, z.object({ name: z.string().trim().min(2, '2 to 120 characters').max(120, '2 to 120 characters'), scope: z.array(z.string()), reason: Reason }));
        edit(ctx);
        const scope = brandScope(ctx, b.scope);
        if (brandKey(b.name) === '') throw invalid({ name: 'Use letters or digits' });
        if (brandClash(ctx, b.name, scope)) throw problem(409, 'catalog.brand-exists', 'Conflict', `${b.name} already exists in this scope`);
        st(ctx).seq += 1;
        const now = ctx.now.toISOString();
        const made: MockBrand = {
          id: `brand-${st(ctx).seq}`, name: b.name.replace(/\s+/g, ' '), status: 'APPROVED', mergedIntoId: null, mergedIntoName: null, createdByName: null, createdAt: now, updatedAt: now,
          version: 0, decidedBy: ctx.action.actor, decidedAt: now, decisionReason: b.reason, products: 0, sellers: 0, scope
        };
        st(ctx).brands.unshift(made);
        log(ctx, 'BRAND_CREATED', 'BRAND', made.id, made.name, b.reason, `Added brand ${made.name}`);
        return brandView(ctx, made);
      }, 201)
    ),
    http.post(
      `${T}/brands/bulk`,
      route(async (ctx): Promise<BrandBulkResult> => {
        const b = await json(ctx.request, z.object({ names: z.array(z.string()).optional(), text: z.string().optional(), scope: z.array(z.string()), reason: Reason }));
        edit(ctx);
        const scope = brandScope(ctx, b.scope);
        const names = [...(b.names ?? []), ...(b.text ?? '').split(/[\r\n,;]+/)].map((n) => n.trim().replace(/\s+/g, ' ')).filter(Boolean);
        if (names.length === 0 || names.length > 500) throw invalid({ names: 'Paste between 1 and 500 brands, one per line' });
        const out: BrandBulkResult = { results: [], created: 0, existing: 0, invalid: 0 };
        const seen = new Set<string>();
        for (const name of names) {
          if (name.length < 2 || name.length > 120 || brandKey(name) === '') {
            out.results.push({ name, outcome: 'INVALID', brandId: null, message: '2 to 120 characters' });
            out.invalid += 1;
            continue;
          }
          const clash = brandClash(ctx, name, scope);
          if (seen.has(brandKey(name)) || clash) {
            out.results.push({ name, outcome: 'EXISTS', brandId: clash?.id ?? null, message: clash ? `Already listed as ${clash.name}` : 'Listed twice' });
            out.existing += 1;
            continue;
          }
          seen.add(brandKey(name));
          st(ctx).seq += 1;
          const now = ctx.now.toISOString();
          const made: MockBrand = { id: `brand-${st(ctx).seq}`, name, status: 'APPROVED', mergedIntoId: null, mergedIntoName: null, createdByName: null, createdAt: now, updatedAt: now, version: 0, decidedBy: ctx.action.actor, decidedAt: now, decisionReason: b.reason, products: 0, sellers: 0, scope };
          st(ctx).brands.unshift(made);
          out.results.push({ name, outcome: 'CREATED', brandId: made.id, message: null });
          out.created += 1;
        }
        if (out.created > 0) log(ctx, 'BRANDS_ADDED', 'BRAND', 'bulk', `${out.created} brands`, b.reason, `Added ${out.created} brands`);
        return out;
      })
    ),
    http.patch(
      `${T}/brands/:id`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ name: z.string().trim().min(2, '2 to 120 characters').max(120, '2 to 120 characters').optional(), scope: z.array(z.string()).optional(), reason: Reason }));
        edit(ctx);
        const brand = brandOf(ctx, p.id);
        brandReload(ctx, brand, brand.version);
        if (brand.status === 'MERGED_INTO') throw problem(409, 'catalog.brand-state', 'Conflict', `${brand.name} was merged; edit the brand it was merged into`);
        const name = b.name?.replace(/\s+/g, ' ');
        const scope = b.scope ? brandScope(ctx, b.scope) : brand.scope;
        const sameScope = scope.length === brand.scope.length && scope.every((id) => brand.scope.includes(id));
        if ((name === undefined || name === brand.name) && sameScope) throw invalid({ name: 'Nothing has changed. Change it to save.' });
        if (brandClash(ctx, name ?? brand.name, scope, brand.id)) throw problem(409, 'catalog.brand-exists', 'Conflict', `${name ?? brand.name} already exists in this scope`);
        const from = brand.name;
        Object.assign(brand, { name: name ?? brand.name, scope });
        decide(ctx, brand, brand.status, 'BRAND_UPDATED', b.reason, `Changed ${from}: ${[name !== undefined && name !== from ? 'name' : '', sameScope ? '' : 'scope'].filter(Boolean).join(' and ')}`);
        return brandView(ctx, brand);
      })
    ),
    ...(['approve', 'reject', 'retire'] as const).map((action) =>
      http.post(
        `${T}/brands/:id/${action}`,
        route(async (ctx, p) => {
          const b = await json(ctx.request, z.object({ reason: Reason }));
          edit(ctx);
          const brand = brandOf(ctx, p.id);
          brandReload(ctx, brand, brand.version);
          if (action === 'approve' && (brand.status === 'APPROVED' || brand.status === 'MERGED_INTO')) throw problem(409, 'catalog.brand-state', 'Conflict', `${brand.name} is already ${brand.status === 'APPROVED' ? 'approved' : 'merged'}`);
          if (action === 'reject' && brand.status !== 'PENDING') throw problem(409, 'catalog.brand-state', 'Conflict', 'Only brands awaiting review can be rejected');
          if (action === 'retire' && brand.status !== 'APPROVED') throw problem(409, 'catalog.brand-state', 'Conflict', 'Only approved brands can be retired');
          const next: BrandStatus = action === 'approve' ? 'APPROVED' : action === 'reject' ? 'REJECTED' : 'RETIRED';
          const verb = action === 'approve' ? 'Approved' : action === 'reject' ? 'Rejected' : 'Retired';
          decide(ctx, brand, next, `BRAND_${next}`, b.reason, `${verb} ${brand.name}`);
          return brandView(ctx, brand);
        })
      )
    ),
    http.post(
      `${T}/brands/:id/merge`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ targetId: z.string().min(1, 'Choose the brand to merge into'), reason: Reason }));
        edit(ctx);
        const brand = brandOf(ctx, p.id);
        brandReload(ctx, brand, brand.version);
        if (b.targetId === brand.id) throw invalid({ targetId: 'Choose another brand' });
        if (brand.status === 'MERGED_INTO') throw problem(409, 'catalog.brand-state', 'Conflict', `${brand.name} was already merged`);
        const target = brandOf(ctx, b.targetId);
        if (target.status !== 'APPROVED') throw invalid({ targetId: 'Must be approved' });
        target.scope = [...new Set([...target.scope, ...brand.scope])];
        target.products += brand.products;
        target.sellers += brand.sellers;
        target.version += 1;
        decide(ctx, brand, 'MERGED_INTO', 'BRAND_MERGED', b.reason, `Merged ${brand.name} into ${target.name}`, { mergedIntoId: target.id, mergedIntoName: target.name, products: 0 });
        return brandView(ctx, brand);
      })
    ),
    http.get(
      `${T}/tree`,
      route((ctx): TaxonomyTree => {
        view(ctx);
        return {
          nodes: withCounts(st(ctx).nodes).sort((a, b) => a.position - b.position),
          pendingRequests: ctx.db.records['category-requests'].filter((r) => r.fields.status === 'PENDING_REVIEW').length,
          canEdit: canEdit(ctx),
          canDecide: rank(ctx) >= RANK.APPROVE
        };
      })
    ),
    http.post(
      `${T}/nodes`,
      route(async (ctx) => {
        const b = await json(ctx.request, z.object({ level: z.enum(['DEPARTMENT', 'CATEGORY', 'PRODUCT_TYPE']), parentId: z.string().optional(), name: Name, code: z.string().optional(), productType: z.string().optional(), newProductType: NewTypeBody.optional(), reason: Reason }));
        edit(ctx);
        const expected = PARENT_LEVEL[b.level];
        let parent: TaxonomyNode | null = null;
        if (expected) {
          parent = st(ctx).nodes.find((n) => n.id === b.parentId) ?? null;
          if (!parent) throw invalid({ parentId: 'Choose where it goes' });
          if (parent.level !== expected) throw invalid({ parentId: b.level === 'CATEGORY' ? 'A category goes under a department' : 'A product type goes under a category' });
          if (!parent.active) throw invalid({ parentId: `${parent.name} is retired` });
        } else if (b.parentId) throw invalid({ parentId: 'Departments have no parent' });
        freeName(ctx, parent?.id ?? null, b.name);
        let typeCode: string | null = null;
        if (b.level === 'PRODUCT_TYPE') {
          if (!!b.productType === !!b.newProductType) throw invalid({ productType: 'Choose an existing product type or define a new one' });
          if (b.newProductType) typeCode = newType(ctx, b.newProductType, b.newProductType.label ?? b.name, b.reason).code;
          else {
            const t = st(ctx).types.find((x) => x.code === b.productType && x.code !== 'custom');
            if (!t) throw invalid({ productType: 'Unknown product type' });
            typeCode = t.code;
          }
        }
        st(ctx).seq += 1;
        const code = b.code ?? slug(parent ? `${parent.code}-${b.name}` : b.name);
        if (st(ctx).nodes.some((n) => n.code === code)) {
          if (b.code) throw invalid({ code: 'Already used by another category' });
        }
        const n: TaxonomyNode = {
          id: `tax-${String(st(ctx).seq).padStart(3, '0')}`, parentId: parent?.id ?? null, code, name: b.name, level: b.level, productType: typeCode,
          productTypeLabel: typeCode ? (st(ctx).types.find((x) => x.code === typeCode)?.label ?? null) : null, path: parent ? `${parent.path} › ${b.name}` : b.name,
          position: st(ctx).nodes.filter((x) => x.parentId === (parent?.id ?? null)).length, active: true, retiredAt: null, retiredReason: null, version: 0, productCount: 0, subtreeProductCount: 0, childCount: 0
        };
        st(ctx).nodes.push(n);
        log(ctx, 'NODE_CREATED', 'NODE', n.id, n.path, b.reason, `Created ${n.path}`);
        return view1(ctx, n);
      }, 201)
    ),
    http.patch(
      `${T}/nodes/:id`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ name: Name, reason: Reason }));
        edit(ctx);
        const n = nodeOf(ctx, p.id);
        ifMatch(ctx, n.version, n.name);
        if (b.name === n.name) throw invalid({ name: 'Nothing has changed. Change it to save.' });
        freeName(ctx, n.parentId, b.name, n.id);
        const oldPath = n.path;
        const from = n.name;
        n.path = `${oldPath.slice(0, oldPath.length - n.name.length)}${b.name}`;
        n.name = b.name;
        n.version += 1;
        rebase(ctx, n, oldPath);
        log(ctx, 'NODE_RENAMED', 'NODE', n.id, n.path, b.reason, `Renamed “${from}” to “${b.name}”`);
        return view1(ctx, n);
      })
    ),
    http.post(
      `${T}/nodes/:id/move`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ parentId: z.string().min(1, 'Choose where it goes'), reason: Reason }));
        edit(ctx);
        const n = nodeOf(ctx, p.id);
        ifMatch(ctx, n.version, n.name);
        if (n.level === 'DEPARTMENT') throw invalid({ parentId: 'Departments are top level; reorder them instead' });
        if (b.parentId === n.parentId) throw invalid({ parentId: 'Nothing has changed. Change it to save.' });
        const parent = nodeOf(ctx, b.parentId);
        if (parent.level !== PARENT_LEVEL[n.level]) throw invalid({ parentId: n.level === 'CATEGORY' ? 'A category goes under a department' : 'A product type goes under a category' });
        if (!parent.active) throw invalid({ parentId: `${parent.name} is retired` });
        freeName(ctx, parent.id, n.name, n.id);
        const oldPath = n.path;
        n.parentId = parent.id;
        n.path = `${parent.path} › ${n.name}`;
        n.position = st(ctx).nodes.filter((x) => x.parentId === parent.id).length;
        n.version += 1;
        rebase(ctx, n, oldPath);
        log(ctx, 'NODE_MOVED', 'NODE', n.id, n.path, b.reason, `Moved from ${oldPath} to ${n.path}`);
        return view1(ctx, n);
      })
    ),
    http.post(
      `${T}/nodes/:id/retire`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ reason: Reason }));
        edit(ctx);
        const n = nodeOf(ctx, p.id);
        ifMatch(ctx, n.version, n.name);
        if (!n.active) throw invalid({ form: 'Nothing has changed. Change it to save.' });
        const nodes = subtree(ctx, n);
        const inUse = nodes.reduce((sum, x) => sum + x.productCount, 0);
        if (inUse > 0) {
          throw problem(409, 'catalog.taxonomy-in-use', 'Conflict', `${n.path} still has ${inUse} ${inUse === 1 ? 'product' : 'products'}. Move its product types to another category first; a category with products is never retired.`);
        }
        const at = ctx.now.toISOString();
        nodes.filter((x) => x.active).forEach((x) => Object.assign(x, { active: false, retiredAt: at, retiredReason: b.reason, version: x.version + 1 }));
        log(ctx, 'NODE_RETIRED', 'NODE', n.id, n.path, b.reason, `Retired ${n.path}`);
        return view1(ctx, n);
      })
    ),
    http.post(
      `${T}/nodes/:id/restore`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ reason: Reason }));
        edit(ctx);
        const n = nodeOf(ctx, p.id);
        ifMatch(ctx, n.version, n.name);
        if (n.active) throw invalid({ form: 'Nothing has changed. Change it to save.' });
        const parent = st(ctx).nodes.find((x) => x.id === n.parentId);
        if (parent && !parent.active) throw invalid({ form: `Restore ${parent.name} first` });
        const stamp = n.retiredAt;
        subtree(ctx, n).filter((x) => x === n || (!x.active && x.retiredAt === stamp)).forEach((x) => Object.assign(x, { active: true, retiredAt: null, retiredReason: null, version: x.version + 1 }));
        log(ctx, 'NODE_RESTORED', 'NODE', n.id, n.path, b.reason, `Restored ${n.path}`);
        return view1(ctx, n);
      })
    ),
    http.post(
      `${T}/reorder`,
      route(async (ctx): Promise<TaxonomyNodes> => {
        const b = await json(ctx.request, z.object({ parentId: z.string().nullish(), ids: z.array(z.string()).min(2).max(500), reason: Reason }));
        edit(ctx);
        const parentId = b.parentId ?? null;
        const siblings = st(ctx).nodes.filter((n) => n.parentId === parentId);
        if (b.ids.length !== siblings.length || !siblings.every((s) => b.ids.includes(s.id))) {
          throw problem(412, 'VERSION_CONFLICT', 'Changed by someone else', 'The list changed since you opened it. Reload and arrange it again.');
        }
        const before = [...siblings].sort((a, c) => a.position - c.position).map((s) => s.id);
        if (before.join() === b.ids.join()) throw invalid({ form: 'Nothing has changed. Change it to save.' });
        b.ids.forEach((id, i) => {
          const n = nodeOf(ctx, id);
          if (n.position !== i) Object.assign(n, { position: i, version: n.version + 1 });
        });
        const parent = parentId ? nodeOf(ctx, parentId) : null;
        log(ctx, 'NODES_REORDERED', 'NODE', parentId ?? 'root', parent?.path ?? 'Departments', b.reason, `Reordered ${parent?.path ?? 'Departments'}: ${b.ids.map((id) => nodeOf(ctx, id).name).join(', ')}`);
        return { items: withCounts(st(ctx).nodes).filter((n) => n.parentId === parentId).sort((a, c) => a.position - c.position) };
      })
    ),
    http.get(
      `${T}/product-types`,
      route((ctx) => {
        view(ctx);
        return { items: st(ctx).types.map((t) => typeView(ctx, t)), canEdit: canEdit(ctx) };
      })
    ),
    http.post(
      `${T}/product-types`,
      route(async (ctx) => {
        const b = await json(ctx.request, NewTypeBody.extend({ label: z.string().trim().min(2).max(80), reason: Reason }));
        edit(ctx);
        return typeView(ctx, newType(ctx, b, b.label, b.reason));
      }, 201)
    ),
    http.get(
      `${T}/product-types/:code`,
      route((ctx, p) => {
        view(ctx);
        return typeView(ctx, typeOf(ctx, p.code));
      })
    ),
    http.patch(
      `${T}/product-types/:code`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ label: z.string().trim().min(2).max(80).optional(), identifierRule: z.enum(['GTIN', 'ISBN']).optional(), traits: TraitsBody.optional(), attributes: z.array(AttributeBody).max(40).optional(), reason: Reason }));
        edit(ctx);
        const t = typeOf(ctx, p.code);
        if (t.code === 'custom') throw invalid({ form: 'The placeholder type used while a category is requested cannot be edited' });
        ifMatch(ctx, t.version, t.label);
        const nextTraits = b.traits ?? t.traits;
        if (nextTraits.perishable && !nextTraits.food) throw invalid({ traits: 'Only food can be perishable' });
        const nextAttributes = b.attributes ? b.attributes.map(toAttribute) : t.attributes;
        const errors = b.attributes ? attributeProblems(b.attributes, nextTraits, t.attributes) : {};
        if (Object.keys(errors).length) throw invalid(errors);
        const changes: string[] = [];
        if (b.label !== undefined && b.label !== t.label) changes.push('Label');
        if (b.identifierRule !== undefined && b.identifierRule !== t.identifierRule) changes.push('Identifier rule');
        if (JSON.stringify(nextTraits) !== JSON.stringify(t.traits)) changes.push('Traits');
        if (JSON.stringify(nextAttributes.map(stripMultiline)) !== JSON.stringify(t.attributes.map(stripMultiline))) changes.push('Attributes');
        if (changes.length === 0) throw invalid({ form: 'Nothing has changed. Change it to save.' });
        Object.assign(t, { label: b.label ?? t.label, identifierRule: b.identifierRule ?? t.identifierRule, traits: nextTraits, attributes: nextAttributes, version: t.version + 1, updatedAt: ctx.now.toISOString() });
        st(ctx).nodes.filter((n) => n.productType === t.code).forEach((n) => (n.productTypeLabel = t.label));
        log(ctx, 'TYPE_UPDATED', 'PRODUCT_TYPE', t.code, t.label, b.reason, `Changed ${t.label}: ${changes.join('; ')}`);
        return typeView(ctx, t);
      })
    ),
    http.get(
      `${T}/audit`,
      route((ctx): TaxonomyAudit => {
        view(ctx);
        const subject = new URL(ctx.request.url).searchParams.get('subject');
        return { items: st(ctx).audit.filter((a) => !subject || a.subjectId === subject).slice(0, 50).map(({ subjectId: _subjectId, ...item }) => item) };
      })
    ),
    http.post(
      `${T}/requests/:id/approval-preview`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ parentCategoryId: z.string(), categoryName: z.string().optional(), productType: z.string(), basedOn: z.string().optional(), note: z.string().optional() }));
        view(ctx);
        const r = ctx.db.records['category-requests'].find((x) => x.id === p.id);
        if (!r) throw problem(404, 'NOT_FOUND', 'Not found', 'No such category request.');
        try {
          return planApproval(st(ctx), r, b, Number(r.fields.productsWaiting ?? 0));
        } catch (e) {
          if (e instanceof PlanError) throw invalid(e.errors);
          throw e;
        }
      })
    )
  ];
}

const stripMultiline = ({ multiline: _multiline, ...a }: TaxonomyAttribute) => a;

const ACTION_LABELS: Record<string, string> = {
  NODE_CREATED: 'Created', NODE_RENAMED: 'Renamed', NODE_MOVED: 'Moved', NODE_RETIRED: 'Retired', NODE_RESTORED: 'Restored', NODES_REORDERED: 'Reordered',
  TYPE_CREATED: 'Product type created', TYPE_UPDATED: 'Product type changed', BRAND_CREATED: 'Brand added', BRANDS_ADDED: 'Brands added', BRAND_UPDATED: 'Brand changed',
  BRAND_APPROVED: 'Brand approved', BRAND_REJECTED: 'Brand rejected', BRAND_RETIRED: 'Brand retired', BRAND_MERGED: 'Brand merged'
};
