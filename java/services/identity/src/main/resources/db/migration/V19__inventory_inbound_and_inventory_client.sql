-- FBA inbound (inventory-service): sellers create and track inbound shipments to AfroTransact FCs and print the AT-FN
-- unit labels for them; fulfilment staff receive them. The inventory-service client itself (catalog:read,
-- catalog:write, shipping:label) is configured in application-<env>.yml.
insert into permission (key, module, description) values
    ('inventory:inbound', 'inventory', 'Create and track FBA inbound shipments to AfroTransact fulfilment centres')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('seller.owner', 'inventory:inbound'), ('seller.admin', 'inventory:inbound'),
    ('seller.fulfilment', 'inventory:inbound'),
    ('platform.super_admin', 'inventory:inbound'), ('platform.inventory_manager', 'inventory:inbound'),
    ('platform.fulfilment_ops', 'inventory:inbound')
) as r(role_key, permission_key)
on conflict do nothing;
