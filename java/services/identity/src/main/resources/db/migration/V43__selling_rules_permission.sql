-- Selling approvals (docs/runbooks/selling-approvals.md): who edits the selling rules (scope rules, gated brands, required documents).
-- Reviewing, approving and assigning selling approval cases reuse applications:review, applications:approve and
-- applications:assign (V39), so the same roles work the "Selling approval" queue next to seller and provider applications.
insert into permission (key, module, description) values
    ('selling-rules:manage', 'catalog', 'Edit selling rules, gated brands and the documents an approval asks for (every change needs a reason and is audited)')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key) values
    ('platform.super_admin', 'selling-rules:manage'),
    ('platform.catalog_manager', 'selling-rules:manage'),
    ('platform.application_supervisor', 'selling-rules:manage')
on conflict do nothing;

-- The rules live under Catalog in the staff console: catalog managers already have it; supervisors get read access to it.
insert into module_access (portal, module_key, role_key, level) values
    ('PLATFORM', 'catalog', 'platform.application_supervisor', 'VIEW')
on conflict (portal, module_key, role_key) do nothing;
