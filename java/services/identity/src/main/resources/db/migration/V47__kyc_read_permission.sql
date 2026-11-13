-- kyc:read (security audit F6 follow-up): media serves seller KYC documents and driver proof-of-delivery photos to a
-- STAFF principal only when it holds this dedicated permission, so media:moderate or catalog:moderate never opens them.
-- Granted to the roles that review applications or run trust cases: Super Admin, Operations Manager, Trust & Safety,
-- Application Reviewer and Application Approver. Service clients are unchanged (gateway-bff reads proof photos with its
-- own media:read scope). Test personas (local/dev seed) hold these roles, so they pick the permission up from here.
insert into permission (key, module, description) values
    ('kyc:read', 'onboarding', 'Open seller KYC documents and driver proof-of-delivery photos in the media service')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key) values
    ('platform.super_admin', 'kyc:read'),
    ('platform.operations_manager', 'kyc:read'),
    ('platform.trust_safety', 'kyc:read'),
    ('platform.application_reviewer', 'kyc:read'),
    ('platform.application_approver', 'kyc:read')
on conflict do nothing;
