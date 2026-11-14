-- Delivery module (staff console, docs/engineering/delivery-fulfilment.md): AfroTransact-delivered orders (Fulfilled by
-- AfroTransact, or sellers who opted into delivery by AfroTransact) are run end to end by staff, without the driver app.
--   delivery:read   view the Delivery queue, the delivery proof and the delivery audit trail
--   delivery:manage take a job, assign a courier by name, mark picked up / out for delivery / delivered (with proof) /
--                   failed, and correct a saved proof (audited)
-- delivery:operate (driver app) and delivery:dispatch (routes) are unchanged. Idempotent: safe to run on a database that
-- already has the rows.
insert into permission (key, module, description) values
    ('delivery:read', 'delivery', 'View the Delivery queue, delivery proof and the delivery audit trail (staff console)'),
    ('delivery:manage', 'delivery', 'Run AfroTransact deliveries end to end: take a job, assign a courier, pick up, out for delivery, delivered with proof, failed, proof correction')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key) values
    ('platform.super_admin', 'delivery:read'),
    ('platform.super_admin', 'delivery:manage'),
    ('platform.operations_manager', 'delivery:read'),
    ('platform.operations_manager', 'delivery:manage'),
    ('platform.fulfilment_ops', 'delivery:read'),
    ('platform.fulfilment_ops', 'delivery:manage'),
    ('platform.dispatcher', 'delivery:read'),
    ('platform.dispatcher', 'delivery:manage'),
    ('platform.customer_support', 'delivery:read'),
    ('platform.product_owner', 'delivery:read'),
    ('platform.auditor', 'delivery:read')
on conflict do nothing;

-- The sidebar module (Commerce > Delivery). Level per platform role; the permission above is checked as well.
insert into access_module (portal, key, label, position) values
    ('PLATFORM', 'delivery', 'Delivery', 27)
on conflict (portal, key) do nothing;

insert into module_access (portal, module_key, role_key, level) values
    ('PLATFORM', 'delivery', 'platform.super_admin', 'FULL'),
    ('PLATFORM', 'delivery', 'platform.operations_manager', 'EDIT'),
    ('PLATFORM', 'delivery', 'platform.trust_safety', 'NONE'),
    ('PLATFORM', 'delivery', 'platform.finance', 'NONE'),
    ('PLATFORM', 'delivery', 'platform.customer_support', 'VIEW'),
    ('PLATFORM', 'delivery', 'platform.marketing', 'NONE'),
    ('PLATFORM', 'delivery', 'platform.catalog_manager', 'NONE'),
    ('PLATFORM', 'delivery', 'platform.retail_buyer', 'NONE'),
    ('PLATFORM', 'delivery', 'platform.fulfilment_ops', 'FULL'),
    ('PLATFORM', 'delivery', 'platform.inventory_manager', 'NONE'),
    ('PLATFORM', 'delivery', 'platform.dispatcher', 'FULL'),
    ('PLATFORM', 'delivery', 'platform.product_owner', 'VIEW'),
    ('PLATFORM', 'delivery', 'platform.developer', 'NONE'),
    ('PLATFORM', 'delivery', 'platform.auditor', 'VIEW'),
    ('PLATFORM', 'delivery', 'platform.driver', 'NONE')
on conflict (portal, module_key, role_key) do nothing;
