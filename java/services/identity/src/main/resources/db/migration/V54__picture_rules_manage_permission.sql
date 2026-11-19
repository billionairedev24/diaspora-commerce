-- Settings > Picture rules (docs/engineering/picture-rules.md) becomes editable:
--   catalog:picture-rules.manage  change the picture-rules profile (strict, startup, testing) and its knobs
--                                 (catalog-service PUT /v1/catalog/picture-rules). Viewing stays under catalog:moderate.
-- Granted to Super Admin, Developer and Catalog Manager: the people who tune listing rules with product. Product Owner and the
-- other catalog:moderate roles can view and be given this one in Settings > Roles and permissions
-- (docs/runbooks/add-a-permission-to-a-role.md). Not an MCP scope and not granted to any OAuth client. Idempotent.
insert into permission (key, module, description) values
    ('catalog:picture-rules.manage', 'catalog', 'Change the picture rules: the profile (strict, startup, testing) and how many photos, how large, which checks')
on conflict (key) do update set module = excluded.module, description = excluded.description;

insert into role_permission (role_key, permission_key) values
    ('platform.super_admin', 'catalog:picture-rules.manage'),
    ('platform.developer', 'catalog:picture-rules.manage'),
    ('platform.catalog_manager', 'catalog:picture-rules.manage')
on conflict do nothing;
