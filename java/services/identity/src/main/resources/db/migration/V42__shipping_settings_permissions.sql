-- Launch shipping (listing rules D16): the one flat delivery fee, its free-above threshold and the seller carrier switch
-- are a staff-edited record owned by orders-service (Settings > Flat delivery fee, in the Fees & commissions module).
-- Viewing the record and its history needs orders:shipping.view; changing it needs orders:shipping.edit and a reason.
insert into permission (key, module, description) values
    ('orders:shipping.view', 'orders', 'View the shipping settings (delivery fee, free-above threshold) and their change history'),
    ('orders:shipping.edit', 'orders', 'Change the shipping settings; every change needs a reason and is logged')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.super_admin', 'orders:shipping.view'), ('platform.super_admin', 'orders:shipping.edit'),
    ('platform.finance', 'orders:shipping.view'), ('platform.finance', 'orders:shipping.edit'),
    ('platform.operations_manager', 'orders:shipping.view'),
    ('platform.customer_support', 'orders:shipping.view'),
    ('platform.auditor', 'orders:shipping.view'),
    ('platform.product_owner', 'orders:shipping.view')
) as r(role_key, permission_key)
on conflict do nothing;
