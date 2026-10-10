-- Permissions used by the content service (placements, markets, site config, legal, flags, careers, audit).
insert into permission (key, module, description) values
    ('content:placements.view', 'content', 'View announcements, banners and other placements'),
    ('content:placements.edit', 'content', 'Create and edit placements'),
    ('content:placements.approve', 'content', 'Approve paid third-party creatives (four-eyes)'),
    ('content:markets.view', 'content', 'View markets and locations'),
    ('content:markets.edit', 'content', 'Activate or deactivate countries, states and capabilities'),
    ('content:site.view', 'content', 'View site content (social links, contacts)'),
    ('content:site.edit', 'content', 'Edit site content without a deployment'),
    ('content:legal.view', 'content', 'View legal documents and versions'),
    ('content:legal.edit', 'content', 'Edit legal documents and raise versions'),
    ('content:flags.view', 'content', 'View feature flags'),
    ('content:flags.edit', 'content', 'Change feature flags, rollouts and kill switches'),
    ('content:careers.view', 'content', 'View job posts and applications'),
    ('content:careers.edit', 'content', 'Manage job posts and applications'),
    ('content:audit.view', 'content', 'View the content change audit log')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.super_admin', 'content:placements.view'), ('platform.super_admin', 'content:placements.edit'),
    ('platform.super_admin', 'content:placements.approve'), ('platform.super_admin', 'content:markets.view'),
    ('platform.super_admin', 'content:markets.edit'), ('platform.super_admin', 'content:site.view'),
    ('platform.super_admin', 'content:site.edit'), ('platform.super_admin', 'content:legal.view'),
    ('platform.super_admin', 'content:legal.edit'), ('platform.super_admin', 'content:flags.view'),
    ('platform.super_admin', 'content:flags.edit'), ('platform.super_admin', 'content:careers.view'),
    ('platform.super_admin', 'content:careers.edit'), ('platform.super_admin', 'content:audit.view'),
    ('platform.marketing', 'content:placements.view'), ('platform.marketing', 'content:placements.edit'),
    ('platform.marketing', 'content:site.view'), ('platform.marketing', 'content:site.edit'),
    ('platform.marketing', 'content:legal.view'), ('platform.marketing', 'content:careers.view'),
    ('platform.marketing', 'content:careers.edit'),
    ('platform.operations_manager', 'content:placements.view'), ('platform.operations_manager', 'content:placements.approve'),
    ('platform.operations_manager', 'content:markets.view'), ('platform.operations_manager', 'content:markets.edit'),
    ('platform.operations_manager', 'content:site.view'), ('platform.operations_manager', 'content:legal.view'),
    ('platform.operations_manager', 'content:flags.view'),
    ('platform.product_owner', 'content:markets.view'), ('platform.product_owner', 'content:markets.edit'),
    ('platform.product_owner', 'content:flags.view'), ('platform.product_owner', 'content:flags.edit'),
    ('platform.product_owner', 'content:placements.view'), ('platform.product_owner', 'content:site.view'),
    ('platform.developer', 'content:flags.view'), ('platform.developer', 'content:flags.edit'),
    ('platform.customer_support', 'content:site.view'), ('platform.customer_support', 'content:legal.view'),
    ('platform.trust_safety', 'content:placements.view'), ('platform.trust_safety', 'content:legal.view'),
    ('platform.auditor', 'content:audit.view'), ('platform.auditor', 'content:legal.view')
) as r(role_key, permission_key)
on conflict do nothing;
