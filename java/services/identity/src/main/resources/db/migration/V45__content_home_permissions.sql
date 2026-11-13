-- Dynamic home (S417, S420): view and edit the home module settings and the community registry (reason mandatory in content).
insert into permission (key, module, description) values
    ('content:home.view', 'content', 'View the dynamic home settings, their version history and the community registry'),
    ('content:home.edit', 'content', 'Change the dynamic home thresholds, caps, mode, department labels and community registry')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key) values
    ('platform.super_admin', 'content:home.view'), ('platform.super_admin', 'content:home.edit'),
    ('platform.marketing', 'content:home.view'), ('platform.marketing', 'content:home.edit'),
    ('platform.product_owner', 'content:home.view'), ('platform.product_owner', 'content:home.edit'),
    ('platform.operations_manager', 'content:home.view')
on conflict do nothing;
