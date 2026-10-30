-- S326 free-text brands that products already hold become registry rows, scoped to the department of the product they were
-- typed on, so a value such as "Cotedivoire" stays under the department it was entered in and is no longer offered under
-- every category. Idempotent: only products without a brand id are looked at, so running it again (the integration test
-- does) adds nothing for products already linked.
--
--   * a value that matches a live registry brand whose scope lies in that department is linked to it;
--   * otherwise a brand is created scoped to the department: APPROVED when three or more distinct sellers used it,
--     PENDING (owned by the first seller who typed it, awaiting staff review) otherwise;
--   * products whose type has no applicable brand attribute (books) are left alone.

create temporary table legacy_node_dept on commit drop as
with recursive up (node_id, id, parent_id) as (
    select id, id, parent_id from category
    union all
    select u.node_id, c.id, c.parent_id from up u join category c on c.id = u.parent_id
)
select node_id, id as dept_id from up where parent_id is null;

create temporary table legacy_brand_use on commit drop as
select p.id as product_id, p.owner_org, p.created_at, btrim(p.attributes ->> 'brand') as raw,
       brand_key(p.attributes ->> 'brand') as nk, nd.dept_id
from product p
join legacy_node_dept nd on nd.node_id = p.category_id
join product_type pt on pt.code = p.product_type
where not (p.attributes ? 'brand_id')
  and btrim(coalesce(p.attributes ->> 'brand', '')) <> ''
  and brand_key(p.attributes ->> 'brand') <> ''
  and exists (select 1 from jsonb_array_elements(pt.attributes) a
              where a ->> 'key' = 'brand' and coalesce((a ->> 'applies')::boolean, true));

create temporary table legacy_brand_group on commit drop as
select g.nk, g.dept_id, g.display, g.sellers, g.creator,
       (select b.id from brand b
        where b.normalized_name = g.nk and b.status in ('APPROVED', 'PENDING')
          and exists (select 1 from brand_scope s join legacy_node_dept nd on nd.node_id = s.node_id
                      where s.brand_id = b.id and nd.dept_id = g.dept_id)
        order by (b.status = 'APPROVED') desc, b.created_at limit 1) as existing_id,
       gen_random_uuid() as new_id
from (select nk, dept_id,
             mode() within group (order by raw) as display,
             count(distinct owner_org) as sellers,
             (array_agg(owner_org order by created_at, product_id))[1] as creator
      from legacy_brand_use group by nk, dept_id) g;

insert into brand (id, name, normalized_name, status, created_by_org, created_at, updated_at)
select new_id, left(display, 120), nk, case when sellers >= 3 then 'APPROVED' else 'PENDING' end, creator, now(), now()
from legacy_brand_group where existing_id is null;

insert into brand_scope (brand_id, node_id)
select new_id, dept_id from legacy_brand_group where existing_id is null;

update product p
set attributes = p.attributes || jsonb_build_object('brand_id', coalesce(g.existing_id, g.new_id)::text, 'brand', b.name)
from legacy_brand_use u
join legacy_brand_group g on g.nk = u.nk and g.dept_id = u.dept_id
join brand b on b.id = coalesce(g.existing_id, g.new_id)
where p.id = u.product_id;
