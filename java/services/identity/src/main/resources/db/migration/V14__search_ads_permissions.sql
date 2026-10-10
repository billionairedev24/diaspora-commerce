-- Search analytics, synonyms, sponsored products (ads) and the seller directory used by the indexer.
insert into permission (key, module, description) values
    ('ads:manage', 'ads', 'Create and manage sponsored product campaigns for the organisation'),
    ('ads:serve', 'ads', 'Read eligible ads for serving (search service)'),
    ('content:ads.view', 'content', 'View sponsored product campaigns and reports across sellers'),
    ('content:ads.edit', 'content', 'Pause, resume or end any sponsored product campaign'),
    ('search:analytics.view', 'search', 'View top queries, zero-result queries and click-through rates'),
    ('search:synonyms.edit', 'search', 'Edit search synonym rules'),
    ('sellers:directory', 'seller', 'Read the seller organisation directory (service clients)')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('seller.owner', 'ads:manage'), ('seller.admin', 'ads:manage'), ('seller.ads', 'ads:manage'),
    ('platform.super_admin', 'content:ads.view'), ('platform.super_admin', 'content:ads.edit'),
    ('platform.marketing', 'content:ads.view'), ('platform.marketing', 'content:ads.edit'),
    ('platform.operations_manager', 'content:ads.view'), ('platform.trust_safety', 'content:ads.view'),
    ('platform.finance', 'content:ads.view'),
    ('platform.super_admin', 'search:analytics.view'), ('platform.super_admin', 'search:synonyms.edit'),
    ('platform.marketing', 'search:analytics.view'), ('platform.catalog_manager', 'search:analytics.view'),
    ('platform.catalog_manager', 'search:synonyms.edit'), ('platform.product_owner', 'search:analytics.view'),
    ('platform.operations_manager', 'search:analytics.view')
) as r(role_key, permission_key)
on conflict do nothing;
