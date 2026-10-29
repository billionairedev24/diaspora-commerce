import { type TaxonomyAttribute, type TaxonomyLevel, type TaxonomyNode, type TaxonomyProductType, type TaxonomyTraits } from '@afrotransact/api-client';

/* Pure rules of the Categories screen: which parents a node can move to, why a node cannot be retired, what a product
   type edit changed. The server applies the same rules again; these let the screen say so before anyone clicks. */

/** The level a node of `level` sits under (departments are top level). */
export const PARENT_LEVEL: Record<TaxonomyLevel, TaxonomyLevel | null> = { DEPARTMENT: null, CATEGORY: 'DEPARTMENT', PRODUCT_TYPE: 'CATEGORY' };

/** The level of the children a node can have (product types are leaves). */
export const CHILD_LEVEL: Record<TaxonomyLevel, TaxonomyLevel | null> = { DEPARTMENT: 'CATEGORY', CATEGORY: 'PRODUCT_TYPE', PRODUCT_TYPE: null };

/** Where a category or product type can move: another active department or category, not where it already is. */
export function moveTargets(nodes: TaxonomyNode[], node: TaxonomyNode): TaxonomyNode[] {
  const level = PARENT_LEVEL[node.level];
  if (!level) return [];
  return nodes.filter((n) => n.level === level && n.active && n.id !== node.parentId).sort((a, b) => a.path.localeCompare(b.path));
}

/** Products that keep a node in use (its own and everything under it); 0 means it can be retired. */
export const productsInUse = (node: TaxonomyNode): number => node.subtreeProductCount;

/** Departments and categories an active node can be added under, for the "Add" menus. */
export const canHaveChildren = (node: TaxonomyNode): boolean => node.active && CHILD_LEVEL[node.level] !== null;

/** The same traits with one switched; perishable needs food, so switching food off clears it. */
export function withTrait(traits: TaxonomyTraits, key: keyof TaxonomyTraits, on: boolean): TaxonomyTraits {
  const next = { ...traits, [key]: on };
  if (key === 'food' && !on) next.perishable = false;
  if (key === 'perishable' && on) next.food = true;
  return next;
}

export interface TypeDraft {
  label: string;
  identifierRule: TaxonomyProductType['identifierRule'];
  traits: TaxonomyTraits;
  attributes: TaxonomyAttribute[];
}

export const draftOfType = (t: Pick<TaxonomyProductType, 'label' | 'identifierRule' | 'traits' | 'attributes'>): TypeDraft => ({
  label: t.label,
  identifierRule: t.identifierRule,
  traits: { ...t.traits },
  attributes: t.attributes.map((a) => ({ ...a, options: [...a.options], units: [...a.units] }))
});

const sameTraits = (a: TaxonomyTraits, b: TaxonomyTraits): boolean => a.food === b.food && a.perishable === b.perishable && a.regulated === b.regulated && a.preorder === b.preorder;

/** The fields of a product-type draft that differ from the stored type: only these are sent. */
export function changedFields(before: TypeDraft, after: TypeDraft, attributesDiffer: boolean): ('label' | 'identifierRule' | 'traits' | 'attributes')[] {
  const out: ('label' | 'identifierRule' | 'traits' | 'attributes')[] = [];
  if (after.label.trim() !== before.label) out.push('label');
  if (after.identifierRule !== before.identifierRule) out.push('identifierRule');
  if (!sameTraits(before.traits, after.traits)) out.push('traits');
  if (attributesDiffer) out.push('attributes');
  return out;
}

/**
 * Moves `id` one place among the siblings that are shown (`visible`), by swapping it with that neighbour in the full
 * order (`all`, retired ones included, which is what the server wants). Unchanged at the ends of the shown list.
 */
export function moveAmongVisible(all: string[], visible: string[], id: string, by: -1 | 1): string[] {
  const i = visible.indexOf(id);
  const neighbour = visible[i + by];
  if (i < 0 || neighbour === undefined) return all;
  const a = all.indexOf(id);
  const b = all.indexOf(neighbour);
  if (a < 0 || b < 0) return all;
  const next = all.slice();
  next[a] = neighbour;
  next[b] = id;
  return next;
}

/** "Women › Dresses" → "Dresses": the last step of a path. */
export const lastStep = (path: string): string => path.split(' › ').at(-1) ?? path;

/** One value per line (choices can contain commas): trimmed, without blanks or repeats. */
export function linesFromText(text: string): string[] {
  return [...new Set(text.split('\n').map((s) => s.trim()).filter(Boolean))];
}

/** A list typed on one line or several (units): trimmed, without blanks or repeats. */
export function listFromText(text: string): string[] {
  return [...new Set(text.split(/[\n,]/).map((s) => s.trim()).filter(Boolean))];
}
