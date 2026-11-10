-- Category tax engine (tax-returns, docs/engineering/category-tax-design.md): who may resolve a category's tax outcome and who
-- reviews it. tax-returns accepts tax:configure holders for the admin actions as well, so nothing breaks before this runs.
insert into permission (key, module, description) values
    ('tax:classify', 'tax', 'Resolve a category''s tax outcome from facts without charging (catalog-service client, staff previews)'),
    ('tax:classify-admin', 'tax', 'Approve and reclassify variant tax classifications, change tax profile gates, edit the provider code map')
on conflict (key) do nothing;

-- Tax Reviewer: reviews variant classifications in the staff console (Tax screens under Fees & commissions).
insert into role (key, portal, name, description, position) values
    ('platform.tax_reviewer', 'PLATFORM', 'Tax Reviewer', 'Tax review queue, tax profiles and the provider code map', 18)
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.super_admin', 'tax:classify'),
    ('platform.super_admin', 'tax:classify-admin'),
    ('platform.catalog_manager', 'tax:classify-admin'),
    ('platform.catalog_manager', 'tax:read'),
    ('platform.tax_reviewer', 'tax:classify-admin'),
    ('platform.tax_reviewer', 'tax:classify'),
    ('platform.tax_reviewer', 'tax:read'),
    ('platform.auditor', 'tax:classify')
) as r(role_key, permission_key)
on conflict do nothing;

-- The Tax screens live in the Fees & commissions module: view access for the two reviewing roles.
insert into module_access (portal, module_key, role_key, level) values
    ('PLATFORM', 'fees', 'platform.catalog_manager', 'VIEW'),
    ('PLATFORM', 'fees', 'platform.tax_reviewer', 'VIEW')
on conflict (portal, module_key, role_key) do update set level = excluded.level
    where module_access.level = 'NONE';
