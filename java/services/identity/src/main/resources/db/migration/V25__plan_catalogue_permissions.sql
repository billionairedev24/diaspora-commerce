-- Plans, trials, credits and rewards owned by AfroTransact (S292–S297): staff manage the plan catalogue, trial campaigns
-- and reward rules (plans:manage) and adjust an organisation's billing (billing:adjust: credits, free days, waived
-- invoices, trial overrides, all audited with a reason code). seller:billing lets staff read any organisation's plan,
-- invoices and grants (Plans & billing module in the staff console).
insert into permission (key, module, description) values
    ('plans:manage', 'billing', 'Create, version, publish and retire plans; trial campaigns; reward rules; Stripe sync'),
    ('billing:adjust', 'billing', 'Credit, give free days, waive invoices and override trials for an organisation')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.super_admin', 'plans:manage'),
    ('platform.super_admin', 'billing:adjust'),
    ('platform.super_admin', 'seller:billing'),
    ('platform.finance', 'plans:manage'),
    ('platform.finance', 'billing:adjust'),
    ('platform.finance', 'seller:billing'),
    ('platform.product_owner', 'seller:billing'),
    ('platform.auditor', 'seller:billing')
) as r(role_key, permission_key)
on conflict do nothing;
