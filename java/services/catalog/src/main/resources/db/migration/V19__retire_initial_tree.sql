-- Category tax build: retire the initial tree (7 departments, ~29 product types) that V2 seeded, before V20 loads the
-- department > category > product type tree of docs/engineering/category-tax-design.md.
--
-- One-time replacement, owner decision: nothing is deployed and existing catalogue data is deleted. The rows are
-- identified by the deterministic ids V2 gave them (uuid5 of the path, see tools/seed/catalog_taxonomy.py history), so
-- rows staff created or renamed since are never touched. A node that a product, a product draft or a category request
-- still uses is retired (hidden from sellers and shoppers, still resolving for what uses it) instead of deleted, so an
-- existing database stays consistent. Future changes to the tree go through the staff console and the importer
-- (POST /v1/staff/taxonomy/import), never through migrations: see docs/engineering/taxonomy.md.

create temporary table legacy_node (id uuid primary key, lvl varchar(20)) on commit drop;
insert into legacy_node (id, lvl) values
    ('9155c8ee-935b-54b5-9f67-a7b03a9f7479', 'PRODUCT_TYPE'),
    ('eff7caa5-6d96-51f3-8018-a77d12dcd892', 'PRODUCT_TYPE'),
    ('d3e73236-1787-5ad2-aecd-abd23d45d2a5', 'PRODUCT_TYPE'),
    ('1fe82a7e-dc48-5fd2-afd2-e2b729fc0c74', 'PRODUCT_TYPE'),
    ('70083e54-0c5c-52f3-a15b-759c236ebc09', 'PRODUCT_TYPE'),
    ('02e2a338-9ffe-5a15-9ae6-1c867c5a4a4d', 'PRODUCT_TYPE'),
    ('2d6e0887-2b1b-59e7-9abf-01fb87ce87ae', 'PRODUCT_TYPE'),
    ('208165be-3b89-5583-a1c3-2d5a84be2417', 'PRODUCT_TYPE'),
    ('993e6383-7c7d-53f0-88f3-0d0fe8cceb2a', 'PRODUCT_TYPE'),
    ('dcd9e156-dfbe-5510-bc6f-0194418e4c9e', 'PRODUCT_TYPE'),
    ('95226236-1ee0-52b3-a581-7a8e9424acac', 'PRODUCT_TYPE'),
    ('0ae4c60a-10dd-5bf5-b077-7d90097fc8b1', 'PRODUCT_TYPE'),
    ('600103ee-be30-5bfa-a4a0-4217e9deaacb', 'PRODUCT_TYPE'),
    ('63c43edf-56eb-5289-ac7e-af9b99ed861c', 'PRODUCT_TYPE'),
    ('b263f453-e2a5-53f4-8da1-12a6f59d8e86', 'PRODUCT_TYPE'),
    ('2e553076-a36c-5f1a-8e08-272dd793034f', 'PRODUCT_TYPE'),
    ('b426ecdf-a6cb-5e8f-b8be-6e61e5ccb9ed', 'PRODUCT_TYPE'),
    ('45c911da-1c27-5955-a7fb-4b6e6b4e2c80', 'PRODUCT_TYPE'),
    ('8adab897-c661-51c6-bb5b-022004835eb0', 'PRODUCT_TYPE'),
    ('ebebb06c-286d-5c26-ae04-71f7903419b9', 'PRODUCT_TYPE'),
    ('a886116a-41d6-5b45-94fa-a1cbe2e8fdbb', 'PRODUCT_TYPE'),
    ('93fe1d67-c683-5b5b-8dd4-4aded3b8651f', 'PRODUCT_TYPE'),
    ('0ed5bd57-e153-5d12-a619-b02931f2fe31', 'PRODUCT_TYPE'),
    ('ffea101f-c4d9-5a3d-8844-702a2fc5f2dc', 'PRODUCT_TYPE'),
    ('5b477506-68fb-5584-aba1-0d137aa8e62e', 'PRODUCT_TYPE'),
    ('90ba271e-7b9d-536c-9f6c-76364e5bc815', 'PRODUCT_TYPE'),
    ('e64bf3c4-8af3-56d7-a269-552320bd9171', 'PRODUCT_TYPE'),
    ('fb5368ba-961f-50aa-9d69-10c2870f91a6', 'PRODUCT_TYPE'),
    ('e4cdfb97-85b4-5135-a726-50f2e76cac8b', 'PRODUCT_TYPE'),
    ('adc666f2-e3bd-5495-a669-f31d64177cb3', 'CATEGORY'),
    ('7f782b5c-a26c-5aec-aa42-9ac5132fff68', 'CATEGORY'),
    ('83012442-2af9-5032-8dc3-900a50343704', 'CATEGORY'),
    ('726e0484-3185-53b8-a07a-150ed4ded782', 'CATEGORY'),
    ('c433bd1b-463b-5b18-8da2-50ce3e1858f5', 'CATEGORY'),
    ('04ad7ef3-989a-5e0f-9f0f-5581de8403e0', 'CATEGORY'),
    ('128b10a8-593b-50c3-9963-615a81b428ef', 'CATEGORY'),
    ('95372ce7-cf16-5e19-9a37-0c0c33b4995e', 'CATEGORY'),
    ('9eaa88fa-3912-5a25-a70c-e45adfbb9c6a', 'CATEGORY'),
    ('cd188866-a55b-5f9d-8d16-de618904c301', 'CATEGORY'),
    ('f6c142b3-e007-563d-889e-56b9373dc7eb', 'CATEGORY'),
    ('08390df5-f9af-5c18-abf1-ec2d785bb1de', 'CATEGORY'),
    ('409399e7-ca9f-5765-a8d0-2be41d600d86', 'CATEGORY'),
    ('ba6ae5d6-5e9b-5d93-9a19-c7ccb8d07cf8', 'DEPARTMENT'),
    ('a5d48106-da2f-528b-a4a4-ec8a1f36d5ff', 'DEPARTMENT'),
    ('38ff0060-313a-5ab7-9cd2-ea3f1a6d55f4', 'DEPARTMENT'),
    ('453f9e9e-b442-507c-aea9-3ec7764f0da1', 'DEPARTMENT'),
    ('5e673de7-2cd0-5dfc-8a09-e5f9aeb29b0e', 'DEPARTMENT'),
    ('67761b06-a83e-5e91-8c32-24a365b9a429', 'DEPARTMENT'),
    ('9a2b6f81-be5f-5589-92c5-b982ff249617', 'DEPARTMENT');

-- Starter brands were scoped to the old nodes. Keep where each applies as a department or category of the new tree
-- (V20 re-creates the scopes once the new nodes exist and drops this table).
create table legacy_brand_scope as
select bs.brand_id,
       case when c.code like 'fashion-fabrics%' then 'fabrics-textiles'
            -- clothing labels (Nike, Levi's ...) were scoped to the women, men and kids categories only, not to bags,
            -- jewellery or fabrics: keep them on the clothing categories of the new tree
            when c.code like 'fashion-women%' or c.code like 'fashion-men%' then 'adult-clothing-footwear'
            when c.code like 'fashion-kids%' then 'childrens-clothing'
            when c.code like 'fashion-%' then 'fashion-accessories'
            when c.code like 'beauty-%' then 'beauty-personal-care'
            when c.code like 'groceries%' then 'groceries-food'
            else null end as target_code
  from brand_scope bs join category c on c.id = bs.node_id join legacy_node l on l.id = c.id;
delete from brand_scope bs using legacy_node l where bs.node_id = l.id;

-- Leaves first, then categories, then departments (the parent foreign key), only where nothing uses the node.
delete from category c using legacy_node l
 where c.id = l.id and l.lvl = 'PRODUCT_TYPE'
   and not exists (select 1 from product p where p.category_id = c.id)
   and not exists (select 1 from size_chart sc where sc.category_id = c.id)
   and not exists (select 1 from brand_scope bs where bs.node_id = c.id)
   and not exists (select 1 from category_request cr where cr.resolved_category_id = c.id);
delete from category c using legacy_node l
 where c.id = l.id and l.lvl = 'CATEGORY' and not exists (select 1 from category ch where ch.parent_id = c.id)
   and not exists (select 1 from brand_scope bs where bs.node_id = c.id)
   and not exists (select 1 from category_request cr where cr.resolved_category_id = c.id);
delete from category c using legacy_node l
 where c.id = l.id and l.lvl = 'DEPARTMENT' and not exists (select 1 from category ch where ch.parent_id = c.id)
   and not exists (select 1 from brand_scope bs where bs.node_id = c.id);

-- What is left of the initial tree is retired: still resolves for the products that use it, cannot be chosen.
update category c set active = false, retired_at = coalesce(c.retired_at, now()),
       retired_reason = coalesce(c.retired_reason, 'Replaced by the category tax tree'), updated_at = now(),
       version = c.version + 1
  from legacy_node l where c.id = l.id and c.active;

-- Initial product types nothing refers to any more (V20 re-creates the fashion types it keeps).
delete from product_type t
 where t.code in ('beauty', 'book', 'dress', 'fabric', 'grocery', 'hair', 'headwrap', 'home', 'kids', 'menwear', 'perishable', 'top')
   and not exists (select 1 from category c where c.product_type_code = t.code)
   and not exists (select 1 from product p where p.product_type = t.code)
   and not exists (select 1 from product_import i where i.product_type = t.code)
   and not exists (select 1 from size_chart sc where sc.product_type_code = t.code);
