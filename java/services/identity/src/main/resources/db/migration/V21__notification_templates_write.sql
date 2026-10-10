-- Notification template editor (S269): staff draft, publish and test-send template versions from the staff console.
insert into permission (key, module, description) values
    ('notifications:templates:write', 'notifications', 'Draft, publish, roll back and test-send notification templates')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.super_admin', 'notifications:templates:write'),
    ('platform.operations_manager', 'notifications:templates:write'),
    ('platform.marketing', 'notifications:templates:write')
) as r(role_key, permission_key)
on conflict do nothing;
