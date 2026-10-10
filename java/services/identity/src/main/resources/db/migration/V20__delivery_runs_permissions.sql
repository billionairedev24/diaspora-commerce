-- Community delivery runs (E34): staff manage locations and runs; services reserve slots and read audiences.
insert into permission (key, module, description) values
    ('delivery-runs:read', 'delivery-runs', 'View delivery run locations, runs, orders and economics'),
    ('delivery-runs:manage', 'delivery-runs', 'Create, schedule, announce, extend and cancel delivery runs'),
    ('delivery-runs:reserve', 'delivery-runs', 'Reserve and release run slots for orders (service clients)'),
    ('delivery-runs:audience.read', 'delivery-runs', 'Read run subscribers per location (service clients)')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.super_admin', 'delivery-runs:read'), ('platform.super_admin', 'delivery-runs:manage'),
    ('platform.operations_manager', 'delivery-runs:read'), ('platform.operations_manager', 'delivery-runs:manage'),
    ('platform.dispatcher', 'delivery-runs:read'), ('platform.dispatcher', 'delivery-runs:manage'),
    ('platform.marketing', 'delivery-runs:read'), ('platform.finance', 'delivery-runs:read'),
    ('platform.customer_support', 'delivery-runs:read'), ('platform.fulfilment_ops', 'delivery-runs:read')
) as r(role_key, permission_key)
on conflict do nothing;
