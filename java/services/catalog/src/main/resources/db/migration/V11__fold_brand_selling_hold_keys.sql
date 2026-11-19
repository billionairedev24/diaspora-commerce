-- Brand selling-scope keys fold accents like the brand registry (seller V93, S630): "hermès" and "hermes" are one brand.
-- Holds written before that carry the old keys; fold them with brand_key() so a renewed approval (which sends the
-- folded key) releases them. Holds of one listing that fold to one key collapse into one (the oldest stays); nothing else
-- is deleted. Safe to run again.
delete from product_selling_hold h
using product_selling_hold o
where h.scope_type in ('BRAND', 'BRAND_IN_CATEGORY') and o.product_id = h.product_id and o.scope_type = h.scope_type
  and (case h.scope_type when 'BRAND' then brand_key(h.scope_key)
       else brand_key(split_part(h.scope_key, '|', 1)) || '|' || substr(h.scope_key, position('|' in h.scope_key) + 1) end)
    = (case o.scope_type when 'BRAND' then brand_key(o.scope_key)
       else brand_key(split_part(o.scope_key, '|', 1)) || '|' || substr(o.scope_key, position('|' in o.scope_key) + 1) end)
  and (o.held_at, o.scope_key) < (h.held_at, h.scope_key);

update product_selling_hold
set scope_key = case scope_type when 'BRAND' then brand_key(scope_key)
    else brand_key(split_part(scope_key, '|', 1)) || '|' || substr(scope_key, position('|' in scope_key) + 1) end
where scope_type in ('BRAND', 'BRAND_IN_CATEGORY')
  and scope_key <> case scope_type when 'BRAND' then brand_key(scope_key)
    else brand_key(split_part(scope_key, '|', 1)) || '|' || substr(scope_key, position('|' in scope_key) + 1) end;
