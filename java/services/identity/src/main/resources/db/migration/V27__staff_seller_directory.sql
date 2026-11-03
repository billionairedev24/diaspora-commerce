-- Staff console: read seller/provider organisations (S213 directory) and register Connect accounts from seller-service.
insert into permission (key, module, description) values
    ('payments:connected-accounts', 'payments', 'Register Stripe Connect account ids for seller organisations (seller-service)')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.super_admin', 'sellers:directory'),
    ('platform.operations_manager', 'sellers:directory'),
    ('platform.trust_safety', 'sellers:directory'),
    ('platform.finance', 'sellers:directory'),
    ('platform.customer_support', 'sellers:directory'),
    ('platform.catalog_manager', 'sellers:directory'),
    ('platform.product_owner', 'sellers:directory'),
    ('platform.marketing', 'sellers:directory'),
    ('platform.auditor', 'sellers:directory'),
    ('platform.super_admin', 'payments:connected-accounts')
) as r(role_key, permission_key)
on conflict do nothing;
