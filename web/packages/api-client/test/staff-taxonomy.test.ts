import { beforeEach, describe, expect, it } from 'vitest';

import {
  attributeKeyFrom,
  attributeProblems,
  attributesChanged,
  buildTaxonomyTree,
  createStaffApiClient,
  moveWithin,
  NEW_PRODUCT_TYPE,
  sellerFormPreview,
  siblingsOf,
  type TaxonomyAttribute,
  type TaxonomyNode
} from '../src';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

/* Catalog → Categories: the tree, the details each product type asks for (with the seller-form preview), reasons,
   If-Match, soft retire that refuses a category with products, the audit trail, and approving a category request. */

const client = (roles?: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: roles ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}`, origin: 'http://staff.test' } : {},
    fetch: (req) => handleStaffMockRequest(req)
  });
const api = (roles?: string) => client(roles).taxonomy;

const attr = (key: string, label: string, extra: Partial<TaxonomyAttribute> = {}): TaxonomyAttribute => ({
  key, label, kind: 'TEXT', options: [], units: [], required: false, section: 'DETAILS', help: null, applies: true, ...extra
});

const node = (nodes: TaxonomyNode[], path: string): TaxonomyNode => {
  const n = nodes.find((x) => x.path === path);
  if (!n) throw new Error(`no node ${path}`);
  return n;
};


/** The BFF's refusal flattened: status, code, detail and the field errors. */
async function rejects(call: Promise<unknown>, expected: Record<string, unknown>): Promise<void> {
  const refusal = await call.then(
    () => {
      throw new Error('expected the call to be refused');
    },
    (e: { status: number; problem?: { code?: string; detail?: string; errors?: Record<string, string> } }) => ({
      status: e.status,
      code: e.problem?.code,
      detail: e.problem?.detail,
      errors: e.problem?.errors ?? {}
    })
  );
  expect(refusal).toMatchObject(expected);
}

beforeEach(() => resetStaffDb(new Date('2026-10-24T12:00:00Z')));

describe('the tree', () => {
  it('lists every node with counts, the pending requests and what the caller may do', async () => {
    const tree = await api().tree();
    expect(tree.canEdit).toBe(true);
    expect(tree.canDecide).toBe(true);
    expect(tree.pendingRequests).toBe(2);
    const fashion = node(tree.nodes, 'Fashion');
    expect(fashion).toMatchObject({ level: 'DEPARTMENT', childCount: 2, subtreeProductCount: 103 });
    expect(node(tree.nodes, 'Fashion › Women › Dresses')).toMatchObject({ productType: 'dress', productTypeLabel: 'Women’s dresses', productCount: 42 });
  });

  it('builds a nested tree in display order and hides retired nodes unless asked', async () => {
    const { nodes } = await api().tree();
    const built = buildTaxonomyTree(nodes, false);
    expect(built.map((t) => t.node.name)).toEqual(['Fashion', 'Groceries', 'Beauty', 'Books', 'Home']);
    expect(built[0]?.children.map((t) => t.node.name)).toEqual(['Women', 'Men']);
    const retired = nodes.map((n) => (n.name === 'Men' ? { ...n, active: false } : n));
    expect(buildTaxonomyTree(retired, false)[0]?.children.map((t) => t.node.name)).toEqual(['Women']);
    expect(buildTaxonomyTree(retired, true)[0]?.children.map((t) => t.node.name)).toEqual(['Women', 'Men']);
  });

  it('refuses staff without catalog:taxonomy, shows a viewer the tree read-only', async () => {
    await rejects(api('platform.operations_manager').tree(), { status: 403 });
    await rejects(api('platform.product_owner').tree(), { status: 403 });
    await expect(api('platform.catalog_manager').tree()).resolves.toMatchObject({ canEdit: true, canDecide: true });
  });
});

describe('creating, renaming, moving', () => {
  it('creates a department, a category and a product type with a new schema, each with a reason and an audit row', async () => {
    const dept = await api().createNode({ level: 'DEPARTMENT', name: 'Toys', reason: 'New department for the spring launch' });
    expect(dept).toMatchObject({ path: 'Toys', code: 'toys', version: 0, active: true });
    const cat = await api().createNode({ level: 'CATEGORY', parentId: dept.id, name: 'Board games', reason: 'Games are a distinct shelf' });
    const leaf = await api().createNode({
      level: 'PRODUCT_TYPE',
      parentId: cat.id,
      name: 'Strategy games',
      reason: 'Strategy games sell on their own',
      newProductType: { identifierRule: 'GTIN', attributes: [attr('players', 'Players', { required: true })] }
    });
    expect(leaf).toMatchObject({ path: 'Toys › Board games › Strategy games', productType: 'strategy_games' });
    expect((await api().productType('strategy_games')).attributes).toHaveLength(1);
    const audit = await api().audit();
    expect(audit.items.map((a) => a.action)).toEqual(['NODE_CREATED', 'TYPE_CREATED', 'NODE_CREATED', 'NODE_CREATED']);
    expect(audit.items[0]).toMatchObject({ actorName: 'Ifeoma Okafor', reason: 'Strategy games sell on their own' });
    expect(audit.items.some((a) => a.summary === 'Created Toys › Board games')).toBe(true);
  });

  it('names the field when a rule is broken: reason, duplicate name, wrong parent, nothing to change', async () => {
    const { nodes } = await api().tree();
    const fashion = node(nodes, 'Fashion');
    await rejects(api().createNode({ level: 'DEPARTMENT', name: 'Pets', reason: 'ab' }), { status: 422, errors: { reason: expect.any(String) } });
    await rejects(api().createNode({ level: 'CATEGORY', parentId: fashion.id, name: 'women', reason: 'Duplicate on purpose' }), { errors: { name: expect.stringContaining('already one called') } });
    await rejects(api().createNode({ level: 'PRODUCT_TYPE', parentId: fashion.id, name: 'Hats', reason: 'Wrong level', productType: 'top' }), { errors: { parentId: expect.any(String) } });
    await rejects(api().createNode({ level: 'PRODUCT_TYPE', parentId: node(nodes, 'Fashion › Men').id, name: 'Hats', reason: 'No schema chosen' }), { errors: { productType: expect.any(String) } });
    const women = node(nodes, 'Fashion › Women');
    await rejects(api().renameNode(women.id, { name: 'Women', reason: 'Same name' }, women.version), { status: 422, errors: { name: expect.stringContaining('Nothing has changed') } });
  });

  it('renames with If-Match, carries the new path down and refuses a stale version', async () => {
    const { nodes } = await api().tree();
    const women = node(nodes, 'Fashion › Women');
    const renamed = await api().renameNode(women.id, { name: 'Ladies', reason: 'Better wording' }, women.version);
    expect(renamed).toMatchObject({ name: 'Ladies', path: 'Fashion › Ladies', version: women.version + 1 });
    const after = (await api().tree()).nodes;
    expect(node(after, 'Fashion › Ladies › Dresses').productCount).toBe(42);
    await rejects(api().renameNode(women.id, { name: 'Women again', reason: 'Late edit' }, women.version), { status: 412, code: 'VERSION_CONFLICT' });
  });

  it('moves a product type to another category and refuses a wrong parent level', async () => {
    const { nodes } = await api().tree();
    const tops = node(nodes, 'Fashion › Women › Tops');
    const men = node(nodes, 'Fashion › Men');
    const moved = await api().moveNode(tops.id, { parentId: men.id, reason: 'Tops are unisex' }, tops.version);
    expect(moved.path).toBe('Fashion › Men › Tops');
    await rejects(api().moveNode(moved.id, { parentId: node(nodes, 'Fashion').id, reason: 'Wrong level' }, moved.version), { errors: { parentId: expect.any(String) } });
    await rejects(api().moveNode(node(nodes, 'Fashion').id, { parentId: men.id, reason: 'Departments stay' }, 0), { errors: { parentId: expect.any(String) } });
  });
});

describe('retire, restore, reorder', () => {
  it('never retires a category with products; an empty one is retired softly and can come back', async () => {
    const { nodes } = await api().tree();
    const dresses = node(nodes, 'Fashion › Women › Dresses');
    await rejects(api().retireNode(dresses.id, 'Not selling dresses', dresses.version), { status: 409, detail: expect.stringContaining('42 products') });
    const women = node(nodes, 'Fashion › Women');
    await rejects(api().retireNode(women.id, 'Not selling women’s wear', women.version), { status: 409 });

    const hair = node(nodes, 'Beauty › Skin & hair › Hair care');
    const retired = await api().retireNode(hair.id, 'No sellers use it', hair.version);
    expect(retired).toMatchObject({ active: false, retiredReason: 'No sellers use it' });
    const restored = await api().restoreNode(hair.id, 'Sellers asked for it back', retired.version);
    expect(restored).toMatchObject({ active: true, retiredAt: null });
    const actions = (await api().audit()).items.map((a) => a.action);
    expect(actions).toEqual(expect.arrayContaining(['NODE_RETIRED', 'NODE_RESTORED']));
  });

  it('retiring a category retires what is under it and restoring it brings those back', async () => {
    const dept = await api().createNode({ level: 'DEPARTMENT', name: 'Seasonal', reason: 'Holiday shelf' });
    const cat = await api().createNode({ level: 'CATEGORY', parentId: dept.id, name: 'Christmas', reason: 'Holiday shelf' });
    const retired = await api().retireNode(dept.id, 'Season is over', dept.version);
    expect(retired.active).toBe(false);
    const after = (await api().tree()).nodes;
    expect(node(after, 'Seasonal › Christmas').active).toBe(false);
    await rejects(api().restoreNode(cat.id, 'Parent first', 1), { status: 422 });
    await api().restoreNode(dept.id, 'Next season', retired.version);
    expect(node((await api().tree()).nodes, 'Seasonal › Christmas').active).toBe(true);
  });

  it('reorders siblings, refuses a stale list and an unchanged order', async () => {
    const { nodes } = await api().tree();
    const ids = siblingsOf(nodes, null).map((n) => n.id);
    const next = moveWithin(ids, ids[1] as string, -1);
    expect(next[0]).toBe(ids[1]);
    expect(moveWithin(ids, ids[0] as string, -1)).toBe(ids);
    const done = await api().reorder({ parentId: null, ids: next, reason: 'Groceries first' });
    expect(done.items.map((n) => n.id)).toEqual(next);
    await rejects(api().reorder({ parentId: null, ids: next, reason: 'No change' }), { status: 422 });
    await rejects(api().reorder({ parentId: null, ids: next.slice(0, 3), reason: 'Forgot two' }), { status: 412, code: 'VERSION_CONFLICT' });
  });
});

describe('product-type details (attributes)', () => {
  it('lists the schemas with where they are used, and Books has no brand', async () => {
    const types = await api().productTypes();
    const book = types.items.find((t) => t.code === 'book');
    expect(book?.attributes.some((a) => a.key === 'brand')).toBe(false);
    expect(book?.identifierRule).toBe('ISBN');
    expect(types.items.find((t) => t.code === 'top')?.usedBy.map((u) => u.path)).toEqual(['Fashion › Women › Tops', 'Fashion › Women › Wrappers & skirts', 'Fashion › Men › Men’s clothing']);
  });

  it('adds a brand, switches it off for a type, and saves nothing when nothing changed', async () => {
    const book = await api().productType('book');
    const withBrand = await api().updateProductType('book', { attributes: [attr('brand', 'Brand', { required: true }), ...book.attributes], reason: 'Publishers are brands too' }, book.version);
    expect(withBrand.version).toBe(book.version + 1);
    expect(withBrand.attributes[0]?.key).toBe('brand');
    const off = await api().updateProductType('book', { attributes: withBrand.attributes.map((a) => (a.key === 'brand' ? { ...a, applies: false } : a)), reason: 'Not for books' }, withBrand.version);
    expect(off.attributes.find((a) => a.key === 'brand')).toMatchObject({ applies: false });
    expect(sellerFormPreview(off).flatMap((s) => s.fields.map((f) => f.key))).not.toContain('brand');
    await rejects(api().updateProductType('book', { attributes: off.attributes, reason: 'Same again' }, off.version), { status: 422, errors: { form: expect.any(String) } });
    await rejects(api().updateProductType('book', { label: 'Books!', reason: 'Late' }, book.version), { status: 412 });
  });

  it('applies the catalog rules: unique keys, choices need values, sections need traits, the tax question stays', async () => {
    const grocery = await api().productType('grocery');
    await rejects(
      api().updateProductType('grocery', { attributes: [attr('a_b', 'A'), attr('a_b', 'B'), attr('pick', 'Pick', { kind: 'ENUM' })], reason: 'Invalid on purpose' }, grocery.version)
    , { errors: { 'attributes[1].key': 'Used twice', 'attributes[2].options': expect.any(String) } });
    await rejects(
      api().updateProductType('top', { attributes: [attr('shelf', 'Shelf life', { section: 'FOOD' })], reason: 'Food section on clothes' }, (await api().productType('top')).version)
    , { errors: { 'attributes[0].section': expect.stringContaining('food trait') } });
    await rejects(
      api().updateProductType('grocery', { attributes: grocery.attributes.filter((a) => a.key !== 'tax_class'), reason: 'Drop the tax question' }, grocery.version)
    , { errors: { attributes: expect.stringContaining('tax_class') } });
    await rejects(api().updateProductType('custom', { label: 'Mine', reason: 'Tamper' }, 0), { status: 422 });
  });

  it('edits traits and the identifier rule, and only food can be perishable', async () => {
    const top = await api().productType('top');
    await rejects(api().updateProductType('top', { traits: { perishable: true }, reason: 'Perishable clothes' }, top.version), { errors: { traits: expect.any(String) } });
    const edited = await api().updateProductType('top', { identifierRule: 'ISBN', traits: { food: true, perishable: true }, reason: 'Odd but allowed' }, top.version);
    expect(edited).toMatchObject({ identifierRule: 'ISBN', traits: { food: true, perishable: true } });
  });
});

describe('the seller form preview', () => {
  it('shows the identifier, the details that apply, and the food and regulated sections only for those traits', async () => {
    const grocery = sellerFormPreview(await api().productType('grocery'));
    expect(grocery.map((s) => s.section)).toEqual(['IDENTIFIER', 'DETAILS', 'FOOD']);
    expect(grocery[2]?.fields.map((f) => f.key)).toEqual(['ingredients', 'allergens', 'shelf', 'storage']);
    expect(grocery[2]?.fields.find((f) => f.key === 'shelf')).toMatchObject({ control: 'number', units: ['days', 'months'] });
    expect(grocery[2]?.fields.find((f) => f.key === 'allergens')).toMatchObject({ control: 'select', required: true });
    const beauty = sellerFormPreview(await api().productType('beauty'));
    expect(beauty.map((s) => s.section)).toEqual(['IDENTIFIER', 'DETAILS', 'REGULATED']);
    const book = sellerFormPreview(await api().productType('book'));
    expect(book[0]?.fields[0]?.label).toBe('ISBN-13');
    expect(book[1]?.fields.map((f) => f.key)).toEqual(['author', 'publisher', 'language', 'pages']);
    const traitless = sellerFormPreview({ identifierRule: 'GTIN', traits: { food: false, perishable: false, regulated: false, preorder: false }, attributes: [attr('shelf', 'Shelf life', { section: 'FOOD' })] });
    expect(traitless.map((s) => s.section)).toEqual(['IDENTIFIER']);
  });

  it('keys, change detection and early problems for the attribute editor', () => {
    expect(attributeKeyFrom('Made in')).toBe('made_in');
    expect(attributeKeyFrom('Crème brûlée (500 g)')).toBe('creme_brulee_500_g');
    expect(attributeKeyFrom('3D size')).toBe('a_3d_size');
    expect(attributeKeyFrom('!!!')).toBe('');
    const a = [attr('brand', 'Brand'), attr('size', 'Size')];
    expect(attributesChanged(a, [attr('brand', 'Brand'), attr('size', 'Size')])).toBe(false);
    expect(attributesChanged(a, [attr('size', 'Size'), attr('brand', 'Brand')])).toBe(true);
    expect(attributesChanged(a, [attr('brand', 'Brand'), attr('size', 'Size', { required: true })])).toBe(true);
    const none = { food: false, perishable: false, regulated: false, preorder: false };
    expect(attributeProblems([attr('Bad Key', 'x'), attr('ok', ''), attr('ok', 'Dup')], none)).toEqual({
      'attributes[0].key': expect.any(String),
      'attributes[1].label': expect.any(String),
      'attributes[2].key': 'Used twice'
    });
  });
});

describe('category requests', () => {
  it('queues requests by seller name with the products waiting, and previews what approving creates', async () => {
    const staff = client();
    const queue = await staff.listResource('category-requests', { page: 0, size: 25 });
    expect(queue.rows).toHaveLength(3);
    const first = queue.rows.find((r) => r.cells.reference === 'REQ-000041');
    expect(first?.cells).toMatchObject({ orgName: 'Lagos Threads', path: 'Fashion › Women › Bridal aso-ebi sets', productsWaiting: 2, status: 'PENDING_REVIEW' });
    const { nodes } = await api().tree();
    const women = node(nodes, 'Fashion › Women');
    const plan = await api().previewApproval('req-1', { parentCategoryId: women.id, productType: NEW_PRODUCT_TYPE });
    expect(plan.nodes).toEqual([{ level: 'PRODUCT_TYPE', name: 'Bridal aso-ebi sets', path: 'Fashion › Women › Bridal aso-ebi sets', created: true }]);
    expect(plan).toMatchObject({ productsMoving: 2, productType: { created: true } });
    expect((await api().tree()).nodes).toHaveLength(nodes.length);
    const underDepartment = await api().previewApproval('req-2', { parentCategoryId: node(nodes, 'Groceries').id, categoryName: 'Sauces', productType: 'grocery' });
    expect(underDepartment.nodes.map((n) => n.path)).toEqual(['Groceries › Sauces', 'Groceries › Sauces › Hot sauces']);
    expect(underDepartment.productType).toMatchObject({ code: 'grocery', created: false });
  });

  it('approving creates the category and the product type in one step and records it', async () => {
    const staff = client();
    const { nodes } = await api().tree();
    const detail = await staff.getRecord('category-requests', 'req-2');
    expect(detail.title).toBe('Hot sauces');
    const approve = detail.actions.find((a) => a.key === 'approve');
    expect(approve?.allowed).toBe(true);
    expect(approve?.form?.fields[0]?.options?.some((o) => o.label === 'Groceries (new category is created)')).toBe(true);
    const done = await staff.runAction('category-requests', 'req-2', 'approve', { parentCategoryId: node(nodes, 'Groceries').id, categoryName: 'Sauces', productType: 'grocery', note: 'Welcome' });
    expect(done.status).toBe('APPROVED');
    const after = (await api().tree()).nodes;
    expect(node(after, 'Groceries › Sauces › Hot sauces')).toMatchObject({ productType: 'grocery', level: 'PRODUCT_TYPE' });
    expect((await api().tree()).pendingRequests).toBe(1);
    expect((await api().audit()).items[0]).toMatchObject({ action: 'REQUEST_APPROVED', summary: 'Approved and created Groceries › Sauces › Hot sauces' });
    await rejects(staff.runAction('category-requests', 'req-2', 'approve', { parentCategoryId: node(after, 'Groceries').id, productType: 'grocery' }), { status: 409 });
  });

  it('a clash is refused with the field, and rejecting needs a reason', async () => {
    const staff = client();
    const { nodes } = await api().tree();
    const pantry = node(nodes, 'Groceries › Pantry');
    await api().createNode({ level: 'PRODUCT_TYPE', parentId: pantry.id, name: 'Hot sauces', productType: 'grocery', reason: 'Already have it' });
    await rejects(staff.runAction('category-requests', 'req-2', 'approve', { parentCategoryId: pantry.id, productType: 'grocery' }), { status: 422, errors: { name: expect.stringContaining('already a Hot sauces') } });
    await rejects(staff.runAction('category-requests', 'req-2', 'reject', {}), { status: 422 });
    const rejected = await staff.runAction('category-requests', 'req-2', 'reject', { note: 'Covered by Sauces & pastes' });
    expect(rejected.status).toBe('REJECTED');
    expect((await api().tree()).pendingRequests).toBe(1);
  });

  it('only staff with catalog:taxonomy decide: others see the queue but not the buttons', async () => {
    const detail = await client('platform.operations_manager').getRecord('category-requests', 'req-1');
    const approve = detail.actions.find((a) => a.key === 'approve');
    expect(approve).toMatchObject({ allowed: false, reason: expect.stringContaining('catalog:taxonomy') });
    await rejects(client('platform.operations_manager').runAction('category-requests', 'req-1', 'reject', { note: 'Not mine to decide' }), { status: 403 });
  });
});
