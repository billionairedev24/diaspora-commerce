-- Pre-orders (S270, S271): staff open, publish and pause pre-order windows, export orders and add late orders;
-- delivery-runs-service verifies creatives with media-service (media:read). orders:on-behalf, which it needs to place
-- late orders on behalf of customers, is created by V23.
insert into permission (key, module, description) values
    ('preorders:manage', 'delivery-runs', 'Open, edit, publish and pause pre-orders; creatives, coverage and settings'),
    ('preorders:export', 'delivery-runs', 'Export pre-order orders, picking lists and advertorial reports (CSV)'),
    ('preorders:late-add', 'delivery-runs', 'Add a late pre-order for a customer after the window closed'),
    ('media:read', 'media', 'Read media asset metadata (service clients)')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.super_admin', 'delivery-runs:read'), ('platform.super_admin', 'preorders:manage'),
    ('platform.super_admin', 'preorders:export'), ('platform.super_admin', 'preorders:late-add'),
    ('platform.marketing', 'delivery-runs:read'), ('platform.marketing', 'preorders:manage'),
    ('platform.marketing', 'preorders:export'), ('platform.marketing', 'preorders:late-add'),
    ('platform.operations_manager', 'delivery-runs:read'), ('platform.operations_manager', 'preorders:manage'),
    ('platform.operations_manager', 'preorders:export'), ('platform.operations_manager', 'preorders:late-add'),
    ('platform.customer_support', 'delivery-runs:read'), ('platform.customer_support', 'preorders:late-add'),
    ('platform.finance', 'delivery-runs:read'), ('platform.finance', 'preorders:export'),
    ('platform.auditor', 'delivery-runs:read'), ('platform.auditor', 'preorders:export'),
    ('platform.product_owner', 'delivery-runs:read'),
    ('platform.fulfilment_ops', 'delivery-runs:read'),
    ('platform.retail_buyer', 'delivery-runs:read')
) as r(role_key, permission_key)
on conflict do nothing;
