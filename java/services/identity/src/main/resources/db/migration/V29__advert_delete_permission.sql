insert into permission (key, module, description) values
    ('advert:delete', 'content', 'Permanently delete paid placement adverts (draft or ended)')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.super_admin', 'advert:delete'),
    ('platform.marketing', 'advert:delete')
) as r(role_key, permission_key)
on conflict do nothing;
