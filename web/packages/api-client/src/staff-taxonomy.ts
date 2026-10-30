/* Catalog → Categories (contracts/openapi/gateway-bff-staff.yaml, tag Taxonomy): the department › category › product
   type tree with product counts, the details (attributes) each product type asks sellers for, retire / restore,
   reordering, the audit trail and the plan of approving a seller's category request. Every change carries a reason;
   edits send the version they loaded as If-Match (412 VERSION_CONFLICT when someone else changed it first). */
import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';
import { AttributeKindSchema, type AttributeSection, AttributeSectionSchema } from './schemas/seller';

export const TaxonomyLevelSchema = z.enum(['DEPARTMENT', 'CATEGORY', 'PRODUCT_TYPE']);
export type TaxonomyLevel = z.infer<typeof TaxonomyLevelSchema>;

export const IdentifierRuleSchema = z.enum(['GTIN', 'ISBN']);
export type IdentifierRule = z.infer<typeof IdentifierRuleSchema>;

export const TaxonomyNodeSchema = z.object({
  id: z.string(),
  parentId: z.string().nullable(),
  code: z.string(),
  name: z.string(),
  level: TaxonomyLevelSchema,
  productType: z.string().nullable(),
  productTypeLabel: z.string().nullable(),
  path: z.string(),
  position: z.number().int(),
  /** False once retired: it stays on products that use it but cannot be chosen for new ones. */
  active: z.boolean(),
  retiredAt: z.string().nullable(),
  retiredReason: z.string().nullable(),
  /** Sent as If-Match when the node is changed. */
  version: z.number().int(),
  productCount: z.number().int(),
  subtreeProductCount: z.number().int(),
  childCount: z.number().int()
});
export type TaxonomyNode = z.infer<typeof TaxonomyNodeSchema>;

export const TaxonomyTreeSchema = z.object({
  nodes: z.array(TaxonomyNodeSchema),
  pendingRequests: z.number().int(),
  canEdit: z.boolean(),
  canDecide: z.boolean()
});
export type TaxonomyTree = z.infer<typeof TaxonomyTreeSchema>;

export const TaxonomyNodesSchema = z.object({ items: z.array(TaxonomyNodeSchema) });
export type TaxonomyNodes = z.infer<typeof TaxonomyNodesSchema>;

export const TaxonomyTraitsSchema = z.object({
  food: z.boolean().default(false),
  perishable: z.boolean().default(false),
  regulated: z.boolean().default(false),
  preorder: z.boolean().default(false)
});
export type TaxonomyTraits = z.infer<typeof TaxonomyTraitsSchema>;

export const TaxonomyAttributeSchema = z.object({
  key: z.string(),
  label: z.string(),
  kind: AttributeKindSchema,
  options: z.array(z.string()).default([]),
  units: z.array(z.string()).default([]),
  required: z.boolean().default(false),
  section: AttributeSectionSchema.default('DETAILS'),
  help: z.string().nullable().optional(),
  /** False keeps the definition but stops asking (brand is just an attribute; a Books type has none). */
  applies: z.boolean().default(true),
  multiline: z.boolean().optional()
});
export type TaxonomyAttribute = z.infer<typeof TaxonomyAttributeSchema>;

export const TaxonomyTypeUseSchema = z.object({ nodeId: z.string(), path: z.string(), active: z.boolean() });
export type TaxonomyTypeUse = z.infer<typeof TaxonomyTypeUseSchema>;

export const TaxonomyProductTypeSchema = z.object({
  code: z.string(),
  label: z.string(),
  identifierRule: IdentifierRuleSchema,
  traits: TaxonomyTraitsSchema,
  attributes: z.array(TaxonomyAttributeSchema),
  themes: z.array(z.record(z.string(), z.unknown())).default([]),
  version: z.number().int(),
  updatedAt: z.string().nullable().optional(),
  productCount: z.number().int(),
  usedBy: z.array(TaxonomyTypeUseSchema),
  canEdit: z.boolean().optional()
});
export type TaxonomyProductType = z.infer<typeof TaxonomyProductTypeSchema>;

export const TaxonomyProductTypesSchema = z.object({ items: z.array(TaxonomyProductTypeSchema), canEdit: z.boolean() });
export type TaxonomyProductTypes = z.infer<typeof TaxonomyProductTypesSchema>;

export const TaxonomyAuditItemSchema = z.object({
  id: z.string(),
  at: z.string(),
  actorName: z.string(),
  action: z.string(),
  actionLabel: z.string(),
  subjectKind: z.enum(['NODE', 'PRODUCT_TYPE', 'REQUEST', 'BRAND']),
  subjectLabel: z.string(),
  reason: z.string(),
  summary: z.string()
});
export type TaxonomyAuditItem = z.infer<typeof TaxonomyAuditItemSchema>;
export const TaxonomyAuditSchema = z.object({ items: z.array(TaxonomyAuditItemSchema) });
export type TaxonomyAudit = z.infer<typeof TaxonomyAuditSchema>;

export const CategoryApprovalPlanSchema = z.object({
  nodes: z.array(z.object({ level: TaxonomyLevelSchema, name: z.string(), path: z.string(), created: z.boolean() })),
  productType: z.object({ code: z.string(), label: z.string(), created: z.boolean(), attributeCount: z.number().int() }),
  productsMoving: z.number().int()
});
export type CategoryApprovalPlan = z.infer<typeof CategoryApprovalPlanSchema>;

/* ── Brands (Catalog → Categories → Brands) ───────────── */

export const BrandStatusSchema = z.enum(['PENDING', 'APPROVED', 'REJECTED', 'MERGED_INTO', 'RETIRED']);
export type BrandStatus = z.infer<typeof BrandStatusSchema>;

export const BrandScopeNodeSchema = z.object({ nodeId: z.string(), path: z.string(), level: TaxonomyLevelSchema });
export type BrandScopeNode = z.infer<typeof BrandScopeNodeSchema>;

export const StaffBrandSchema = z.object({
  id: z.string(),
  name: z.string(),
  status: BrandStatusSchema,
  mergedIntoId: z.string().nullable(),
  mergedIntoName: z.string().nullable(),
  /** The seller who typed it in; null for brands staff listed. */
  createdByName: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
  /** Sent as If-Match when the brand is changed. */
  version: z.number().int(),
  decidedBy: z.string().nullable(),
  decidedAt: z.string().nullable(),
  decisionReason: z.string().nullable(),
  /** Listings that hold the brand, and the distinct sellers behind them. */
  products: z.number().int(),
  sellers: z.number().int(),
  scope: z.array(BrandScopeNodeSchema)
});
export type StaffBrand = z.infer<typeof StaffBrandSchema>;

export const StaffBrandListSchema = z.object({
  brands: z.array(StaffBrandSchema),
  total: z.number().int(),
  /** Brands per status, for the tab badges. */
  counts: z.record(z.string(), z.number().int()),
  canEdit: z.boolean()
});
export type StaffBrandList = z.infer<typeof StaffBrandListSchema>;

export const BrandBulkResultSchema = z.object({
  results: z.array(z.object({ name: z.string(), outcome: z.enum(['CREATED', 'EXISTS', 'INVALID']), brandId: z.string().nullable(), message: z.string().nullable() })),
  created: z.number().int(),
  existing: z.number().int(),
  invalid: z.number().int()
});
export type BrandBulkResult = z.infer<typeof BrandBulkResultSchema>;

export interface BrandQuery {
  status?: BrandStatus[];
  q?: string;
  scope?: string;
  limit?: number;
  offset?: number;
}

/** Splits a pasted list (new lines, commas, semicolons) into tidy, de-duplicated names (case and accents ignored). */
export function splitBrandList(text: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const raw of text.split(/[\r\n,;]+/)) {
    const name = raw.trim().replace(/\s+/g, ' ');
    const key = name.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '');
    if (name && key && !seen.has(key)) {
      seen.add(key);
      out.push(name);
    }
  }
  return out;
}

/** Whether an edit of a brand's name or scope changes anything: a form with no change cannot be saved. */
export function brandChanged(brand: Pick<StaffBrand, 'name' | 'scope'>, name: string, scope: string[]): boolean {
  const before = brand.scope.map((s) => s.nodeId).sort();
  const after = [...scope].sort();
  return name.trim().replace(/\s+/g, ' ') !== brand.name || before.length !== after.length || before.some((id, i) => id !== after[i]);
}

/* ── Requests ──────────────────────────────────────────── */

export interface TaxonomyNewProductType {
  code?: string;
  label?: string;
  identifierRule: IdentifierRule;
  traits?: Partial<TaxonomyTraits>;
  attributes?: TaxonomyAttribute[];
  basedOn?: string;
}

export interface TaxonomyCreateNode {
  level: TaxonomyLevel;
  parentId?: string;
  name: string;
  code?: string;
  /** An existing schema for a product-type node, or newProductType. */
  productType?: string;
  newProductType?: TaxonomyNewProductType;
  reason: string;
}

export interface TaxonomyProductTypeChange {
  label?: string;
  identifierRule?: IdentifierRule;
  traits?: Partial<TaxonomyTraits>;
  attributes?: TaxonomyAttribute[];
  reason: string;
}

/** What the approve form of a category request chooses (`productType` is a code, or NEW_PRODUCT_TYPE). */
export interface CategoryApprovalChoice {
  parentCategoryId: string;
  categoryName?: string;
  productType: string;
  basedOn?: string;
  note?: string;
}

/** The product-type value that asks for a new schema. */
export const NEW_PRODUCT_TYPE = '__new__';

/* ── Client ────────────────────────────────────────────── */

const T = '/v1/staff/taxonomy';
const enc = encodeURIComponent;
const ifMatch = (version: number): Record<string, string> => ({ 'If-Match': `"${version}"` });

/** Catalog → Categories routes of the staff BFF (mounted as `staffApi().taxonomy`). */
export function createStaffTaxonomyApi(request: Requester) {
  return {
    tree: (signal?: AbortSignal): Promise<TaxonomyTree> => request(`${T}/tree`, TaxonomyTreeSchema, { signal }),
    createNode: (body: TaxonomyCreateNode): Promise<TaxonomyNode> => request(`${T}/nodes`, TaxonomyNodeSchema, { method: 'POST', body }),
    /** 412 when the node changed since `version`; 422 when the name did not change. */
    renameNode: (id: string, body: { name: string; reason: string }, version: number): Promise<TaxonomyNode> =>
      request(`${T}/nodes/${enc(id)}`, TaxonomyNodeSchema, { method: 'PATCH', body, headers: ifMatch(version) }),
    moveNode: (id: string, body: { parentId: string; reason: string }, version: number): Promise<TaxonomyNode> =>
      request(`${T}/nodes/${enc(id)}/move`, TaxonomyNodeSchema, { method: 'POST', body, headers: ifMatch(version) }),
    /** 409 while the node or anything under it has products. */
    retireNode: (id: string, reason: string, version: number): Promise<TaxonomyNode> =>
      request(`${T}/nodes/${enc(id)}/retire`, TaxonomyNodeSchema, { method: 'POST', body: { reason }, headers: ifMatch(version) }),
    restoreNode: (id: string, reason: string, version: number): Promise<TaxonomyNode> =>
      request(`${T}/nodes/${enc(id)}/restore`, TaxonomyNodeSchema, { method: 'POST', body: { reason }, headers: ifMatch(version) }),
    /** `ids` is every child of the parent (null: the departments), retired ones included, in the new order. */
    reorder: (body: { parentId: string | null; ids: string[]; reason: string }): Promise<TaxonomyNodes> =>
      request(`${T}/reorder`, TaxonomyNodesSchema, { method: 'POST', body }),
    productTypes: (signal?: AbortSignal): Promise<TaxonomyProductTypes> => request(`${T}/product-types`, TaxonomyProductTypesSchema, { signal }),
    productType: (code: string, signal?: AbortSignal): Promise<TaxonomyProductType> =>
      request(`${T}/product-types/${enc(code)}`, TaxonomyProductTypeSchema, { signal }),
    createProductType: (body: TaxonomyNewProductType & { label: string; reason: string }): Promise<TaxonomyProductType> =>
      request(`${T}/product-types`, TaxonomyProductTypeSchema, { method: 'POST', body }),
    /** Only the fields present change; `attributes` is the whole new list. */
    updateProductType: (code: string, body: TaxonomyProductTypeChange, version: number): Promise<TaxonomyProductType> =>
      request(`${T}/product-types/${enc(code)}`, TaxonomyProductTypeSchema, { method: 'PATCH', body, headers: ifMatch(version) }),
    /** The review queue is `status: ['PENDING']`; the seller who typed a brand is named, never an id. */
    brands: (query: BrandQuery = {}, signal?: AbortSignal): Promise<StaffBrandList> =>
      request(`${T}/brands`, StaffBrandListSchema, {
        query: { status: query.status?.join(','), q: query.q || undefined, scope: query.scope, limit: query.limit ? String(query.limit) : undefined, offset: query.offset ? String(query.offset) : undefined },
        signal
      }),
    /** 409 when the name already exists in an overlapping scope. */
    createBrand: (body: { name: string; scope: string[]; reason: string }): Promise<StaffBrand> =>
      request(`${T}/brands`, StaffBrandSchema, { method: 'POST', body }),
    /** A pasted list (`text`, one per line) added to one scope; entries that exist or are invalid are reported, not fatal. */
    createBrands: (body: { text: string; scope: string[]; reason: string }): Promise<BrandBulkResult> =>
      request(`${T}/brands/bulk`, BrandBulkResultSchema, { method: 'POST', body }),
    /** Only the fields present change; 422 when nothing differs from what is stored, 412 when it changed since `version`. */
    updateBrand: (id: string, body: { name?: string; scope?: string[]; reason: string }, version: number): Promise<StaffBrand> =>
      request(`${T}/brands/${enc(id)}`, StaffBrandSchema, { method: 'PATCH', body, headers: ifMatch(version) }),
    approveBrand: (id: string, reason: string, version: number): Promise<StaffBrand> =>
      request(`${T}/brands/${enc(id)}/approve`, StaffBrandSchema, { method: 'POST', body: { reason }, headers: ifMatch(version) }),
    rejectBrand: (id: string, reason: string, version: number): Promise<StaffBrand> =>
      request(`${T}/brands/${enc(id)}/reject`, StaffBrandSchema, { method: 'POST', body: { reason }, headers: ifMatch(version) }),
    retireBrand: (id: string, reason: string, version: number): Promise<StaffBrand> =>
      request(`${T}/brands/${enc(id)}/retire`, StaffBrandSchema, { method: 'POST', body: { reason }, headers: ifMatch(version) }),
    /** Listings that hold the brand are re-pointed to `targetId` (an approved brand) and show its name. */
    mergeBrand: (id: string, targetId: string, reason: string, version: number): Promise<StaffBrand> =>
      request(`${T}/brands/${enc(id)}/merge`, StaffBrandSchema, { method: 'POST', body: { targetId, reason }, headers: ifMatch(version) }),
    audit: (subject?: string, signal?: AbortSignal): Promise<TaxonomyAudit> =>
      request(`${T}/audit`, TaxonomyAuditSchema, { query: { subject, limit: '50' }, signal }),
    /** What approving a category request with these choices would create; nothing is created. */
    previewApproval: (requestId: string, choice: CategoryApprovalChoice, signal?: AbortSignal): Promise<CategoryApprovalPlan> =>
      request(`${T}/requests/${enc(requestId)}/approval-preview`, CategoryApprovalPlanSchema, { method: 'POST', body: choice, signal })
  };
}

export type StaffTaxonomyApi = ReturnType<typeof createStaffTaxonomyApi>;

/* ── Query keys & options ──────────────────────────────── */

export const staffTaxonomyKeys = {
  all: ['staff', 'taxonomy'] as const,
  tree: () => [...staffTaxonomyKeys.all, 'tree'] as const,
  types: () => [...staffTaxonomyKeys.all, 'product-types'] as const,
  type: (code: string) => [...staffTaxonomyKeys.all, 'product-type', code] as const,
  audit: (subject?: string) => [...staffTaxonomyKeys.all, 'audit', subject ?? 'all'] as const,
  brands: (query: BrandQuery) => [...staffTaxonomyKeys.all, 'brands', query] as const,
  preview: (requestId: string, choice: CategoryApprovalChoice) => [...staffTaxonomyKeys.all, 'approval-preview', requestId, choice] as const
};

export const staffTaxonomyQueries = {
  tree: (api: StaffTaxonomyApi) => queryOptions({ queryKey: staffTaxonomyKeys.tree(), queryFn: ({ signal }) => api.tree(signal), staleTime: 10_000 }),
  types: (api: StaffTaxonomyApi) => queryOptions({ queryKey: staffTaxonomyKeys.types(), queryFn: ({ signal }) => api.productTypes(signal), staleTime: 10_000 }),
  type: (api: StaffTaxonomyApi, code: string) =>
    queryOptions({ queryKey: staffTaxonomyKeys.type(code), queryFn: ({ signal }) => api.productType(code, signal), staleTime: 10_000 }),
  brands: (api: StaffTaxonomyApi, query: BrandQuery) =>
    queryOptions({ queryKey: staffTaxonomyKeys.brands(query), queryFn: ({ signal }) => api.brands(query, signal), staleTime: 5_000 }),
  audit: (api: StaffTaxonomyApi, subject?: string) =>
    queryOptions({ queryKey: staffTaxonomyKeys.audit(subject), queryFn: ({ signal }) => api.audit(subject, signal), staleTime: 10_000 }),
  preview: (api: StaffTaxonomyApi, requestId: string, choice: CategoryApprovalChoice) =>
    queryOptions({ queryKey: staffTaxonomyKeys.preview(requestId, choice), queryFn: ({ signal }) => api.previewApproval(requestId, choice, signal), staleTime: 0, retry: false })
};

/* ── Pure helpers (tree, preview of the seller's form, edit checks) ─────────── */

export interface TaxonomyTreeNode {
  node: TaxonomyNode;
  children: TaxonomyTreeNode[];
}

/** The nodes as a tree, siblings in display order; retired nodes only when `showRetired`. */
export function buildTaxonomyTree(nodes: TaxonomyNode[], showRetired: boolean): TaxonomyTreeNode[] {
  const visible = nodes.filter((n) => showRetired || n.active);
  const byParent = new Map<string | null, TaxonomyNode[]>();
  for (const n of visible) {
    const key = n.parentId;
    const list = byParent.get(key);
    if (list) list.push(n);
    else byParent.set(key, [n]);
  }
  const build = (parent: string | null): TaxonomyTreeNode[] =>
    (byParent.get(parent) ?? [])
      .slice()
      .sort((a, b) => a.position - b.position || a.name.localeCompare(b.name))
      .map((node) => ({ node, children: build(node.id) }));
  return build(null);
}

/** Siblings of a node (same parent), retired ones included, in display order: what a reorder sends. */
export function siblingsOf(nodes: TaxonomyNode[], parentId: string | null): TaxonomyNode[] {
  return nodes.filter((n) => n.parentId === parentId).sort((a, b) => a.position - b.position || a.name.localeCompare(b.name));
}

/** A new order of ids with `id` moved one place up (-1) or down (+1); unchanged at the ends. */
export function moveWithin(ids: string[], id: string, by: -1 | 1): string[] {
  const i = ids.indexOf(id);
  const j = i + by;
  if (i < 0 || j < 0 || j >= ids.length) return ids;
  const next = ids.slice();
  const moved = next[i] as string;
  next[i] = next[j] as string;
  next[j] = moved;
  return next;
}

export interface PreviewField {
  key: string;
  label: string;
  required: boolean;
  help: string | null;
  control: 'text' | 'textarea' | 'select' | 'number' | 'brand';
  options: string[];
  units: string[];
}

export interface PreviewSection {
  section: AttributeSection | 'IDENTIFIER';
  fields: PreviewField[];
}

/**
 * The fields a seller's product form shows for a product type, section by section: the identifier (GTIN or ISBN), the
 * details that apply, and the food and regulated sections only for types with those traits. Attributes that do not
 * apply are left out, as the seller form leaves them out.
 */
export function sellerFormPreview(type: Pick<TaxonomyProductType, 'identifierRule' | 'traits' | 'attributes'>): PreviewSection[] {
  const control = (a: TaxonomyAttribute): PreviewField['control'] =>
    a.kind === 'MULTILINE' ? 'textarea' : a.kind === 'ENUM' ? 'select' : a.kind === 'NUMBER' ? 'number' : a.kind === 'BRAND' ? 'brand' : 'text';
  const fieldOf = (a: TaxonomyAttribute): PreviewField => ({
    key: a.key,
    label: a.label,
    required: a.required,
    help: a.help ?? null,
    control: control(a),
    options: a.kind === 'ENUM' ? a.options : [],
    units: a.kind === 'NUMBER' ? a.units : []
  });
  const applying = type.attributes.filter((a) => a.applies);
  const sections: PreviewSection[] = [
    { section: 'IDENTIFIER', fields: [{ key: 'identifier', label: type.identifierRule === 'ISBN' ? 'ISBN-13' : 'GTIN (UPC / EAN)', required: true, help: null, control: 'text', options: [], units: [] }] },
    { section: 'DETAILS', fields: applying.filter((a) => a.section === 'DETAILS').map(fieldOf) }
  ];
  if (type.traits.food) sections.push({ section: 'FOOD', fields: applying.filter((a) => a.section === 'FOOD').map(fieldOf) });
  if (type.traits.regulated) sections.push({ section: 'REGULATED', fields: applying.filter((a) => a.section === 'REGULATED').map(fieldOf) });
  return sections.filter((s) => s.fields.length > 0);
}

/** Lower-case key from a label: "Made in" becomes made_in. */
export function attributeKeyFrom(label: string): string {
  const key = label
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 40);
  return /^[a-z]/.test(key) ? key : key ? `a_${key}`.slice(0, 40) : '';
}

const sameAttribute = (a: TaxonomyAttribute, b: TaxonomyAttribute): boolean =>
  a.key === b.key &&
  a.label === b.label &&
  a.kind === b.kind &&
  a.required === b.required &&
  a.section === b.section &&
  a.applies === b.applies &&
  (a.help ?? '') === (b.help ?? '') &&
  a.options.join('\u0000') === b.options.join('\u0000') &&
  a.units.join('\u0000') === b.units.join('\u0000');

/** Whether the attribute list differs (content or order): a form with no change cannot be saved. */
export function attributesChanged(before: TaxonomyAttribute[], after: TaxonomyAttribute[]): boolean {
  return before.length !== after.length || before.some((a, i) => !sameAttribute(a, after[i] as TaxonomyAttribute));
}

/** Problems with an attribute list, by field path (the same rules catalog applies, so the editor can say so early). */
export function attributeProblems(attributes: TaxonomyAttribute[], traits: TaxonomyTraits): Record<string, string> {
  const errors: Record<string, string> = {};
  const seen = new Set<string>();
  attributes.forEach((a, i) => {
    const at = `attributes[${i}].`;
    if (!/^[a-z][a-z0-9_]{1,39}$/.test(a.key)) errors[`${at}key`] = 'Lower-case letters, digits and _, starting with a letter';
    else if (seen.has(a.key)) errors[`${at}key`] = 'Used twice';
    seen.add(a.key);
    if (a.label.trim() === '') errors[`${at}label`] = 'Give it a label';
    if (a.kind === 'ENUM' && a.options.filter((o) => o.trim() !== '').length === 0) errors[`${at}options`] = 'Add the values to choose from';
    if (a.kind === 'BRAND') {
      if (a.key !== 'brand') errors[`${at}key`] = 'The brand attribute is keyed brand';
      if (a.options.some((o) => o !== 'UNBRANDED' && o !== 'HANDMADE')) errors[`${at}options`] = 'Only No brand and Handmade can be offered instead of a brand';
    }
    if (a.applies && a.section === 'FOOD' && !traits.food) errors[`${at}section`] = 'The food section needs the food trait';
    if (a.applies && a.section === 'REGULATED' && !traits.regulated) errors[`${at}section`] = 'The regulated section needs the regulated trait';
  });
  return errors;
}
