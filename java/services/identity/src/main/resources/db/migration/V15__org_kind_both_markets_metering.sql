-- S215: organisations that sell products and offer services (kind BOTH) and market codes aligned with content's
-- market_country codes (ISO 3166-1 alpha-2, upper case).
alter table organization drop constraint if exists organization_kind_check;
alter table organization add constraint organization_kind_check check (kind in ('SELLER', 'PROVIDER', 'BOTH'));

update organization set market = upper(trim(market)) where market is not null;
update organization set market = 'GB' where market in ('UK', 'GBR');
update organization set market = 'US' where market = 'USA';
update organization set market = null where market is not null and market !~ '^[A-Z]{2}$';
alter table organization add constraint organization_market_code check (market is null or market ~ '^[A-Z]{2}$');

-- S206 metered billing: staff access to usage statements and metering runs, and the service scopes the
-- seller-service client uses to read usage from inventory, content and ai-gateway.
insert into permission (key, module, description) values
    ('billing:usage', 'billing', 'View every organisation''s metered usage statements and re-run metering'),
    ('inventory:storage.read', 'inventory', 'Read daily FBA storage volume per owner (service clients)'),
    ('ads:spend.read', 'ads', 'Read charged ads spend per organisation and day (service clients)'),
    ('ai:usage.read', 'ai', 'Read AI credits per organisation and day (service clients)')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.super_admin', 'billing:usage'), ('platform.finance', 'billing:usage')
) as r(role_key, permission_key)
on conflict do nothing;

-- Coordinator requests: booking review moderation (Trust & Safety) and media uploads by the retail service.
-- media-service checks no permission key today (any authenticated caller uploads into its own owner space);
-- media:write is registered so the retail-service client can request it and media can enforce it later.
insert into permission (key, module, description) values
    ('bookings:reviews:moderate', 'bookings', 'Moderate service reviews: hide, restore and resolve reports'),
    ('media:write', 'media', 'Upload media assets (service clients)')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.trust_safety', 'bookings:reviews:moderate'), ('platform.super_admin', 'bookings:reviews:moderate')
) as r(role_key, permission_key)
on conflict do nothing;
