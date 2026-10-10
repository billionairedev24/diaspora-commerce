-- Offers service permissions and the reserved AfroTransact Retail (1P) organisation.
insert into permission (key, module, description) values
    ('offers:read', 'offers', 'View offers, prices and buy box status'),
    ('offers:write', 'offers', 'Create and change offers and prices'),
    ('coupons:write', 'offers', 'Create and manage seller-funded coupons'),
    ('coupons:platform:write', 'offers', 'Create and manage platform-funded coupons')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('seller.owner', 'offers:read'), ('seller.admin', 'offers:read'), ('seller.catalog', 'offers:read'),
    ('seller.fulfilment', 'offers:read'), ('seller.support', 'offers:read'), ('seller.ads', 'offers:read'),
    ('seller.owner', 'offers:write'), ('seller.admin', 'offers:write'), ('seller.catalog', 'offers:write'),
    ('seller.owner', 'coupons:write'), ('seller.admin', 'coupons:write'), ('seller.ads', 'coupons:write'),
    ('platform.super_admin', 'offers:read'), ('platform.super_admin', 'offers:write'),
    ('platform.operations_manager', 'offers:read'), ('platform.operations_manager', 'offers:write'),
    ('platform.catalog_manager', 'offers:read'), ('platform.catalog_manager', 'offers:write'),
    ('platform.retail_buyer', 'offers:read'), ('platform.retail_buyer', 'offers:write'),
    ('platform.customer_support', 'offers:read'), ('platform.trust_safety', 'offers:read'),
    ('platform.marketing', 'offers:read')
) as r(role_key, permission_key)
on conflict do nothing;

-- Platform coupons: every role that already manages coupons.
insert into role_permission (role_key, permission_key)
select role_key, 'coupons:platform:write' from role_permission where permission_key = 'coupons:manage'
on conflict do nothing;

-- AfroTransact Retail is a first-party seller with a fixed, well-known id (RETAIL_ORG_ID in every service).
insert into organization (id, kind, name, status, updated_at, market)
values ('org_afrotransact_retail', 'SELLER', 'AfroTransact Retail', 'APPROVED', now(), 'US')
on conflict (id) do nothing;
