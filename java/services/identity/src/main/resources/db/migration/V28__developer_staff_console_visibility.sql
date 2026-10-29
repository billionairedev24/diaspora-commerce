-- Developers need every staff console module visible for incident response (senior/junior split later).

update module_access
set level = 'VIEW'
where portal = 'PLATFORM'
  and role_key = 'platform.developer'
  and level = 'NONE';

update module_access
set level = 'EDIT'
where portal = 'PLATFORM'
  and role_key = 'platform.developer'
  and module_key in ('marketing', 'coupons', 'catalog', 'orders', 'siteCfg');

insert into role_permission (role_key, permission_key)
values ('platform.developer', 'users:read'),
       ('platform.developer', 'orgs:read'),
       ('platform.developer', 'inventory:read'),
       ('platform.developer', 'delivery-runs:read'),
       ('platform.developer', 'preorders:manage'),
       ('platform.developer', 'preorders:export'),
       ('platform.developer', 'notifications:read')
on conflict do nothing;
