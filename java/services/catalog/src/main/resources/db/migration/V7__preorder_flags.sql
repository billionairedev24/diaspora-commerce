-- S272 pre-orders: whether a product may be sold in a community pre-order window (default on for food product types)
-- and whether it always ships now by carrier (shea, fabric …) even when its category is in a window.
alter table product
    add column preorder_eligible boolean not null default false,
    add column ships_now         boolean not null default false;
update product set preorder_eligible = true where product_type in ('grocery', 'perishable');
