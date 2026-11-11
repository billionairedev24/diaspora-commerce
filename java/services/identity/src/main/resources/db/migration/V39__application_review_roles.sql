-- Application review workflow (docs/runbooks/application-review-workflow.md): reviewers and approvers become separate
-- roles, and a supervisor assigns, takes over and manages escalation. Existing roles keep their permissions (Operations
-- Manager, Trust & Safety and Super Admin still hold review AND approve). The same person may hold both the reviewer
-- and the approver role; seller's two-person rule still applies per application.
insert into permission (key, module, description) values
    ('applications:assign', 'onboarding', 'Assign, reassign and take over application reviews; manage application SLAs and escalation targets')
on conflict (key) do nothing;

insert into role (key, portal, name, description, position) values
    ('platform.application_reviewer', 'PLATFORM', 'Application Reviewer', 'Step 1 of seller and provider applications: review, flag, return to the applicant', 19),
    ('platform.application_approver', 'PLATFORM', 'Application Approver', 'Step 2 of seller and provider applications: final approval, send back, reject', 20),
    ('platform.application_supervisor', 'PLATFORM', 'Application Supervisor', 'Sees every application, assigns and takes over reviews, manages SLAs and escalation (holds neither step permission by default)', 21)
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key) values
    ('platform.super_admin', 'applications:assign'),
    ('platform.application_reviewer', 'applications:review'),
    ('platform.application_approver', 'applications:approve'),
    ('platform.application_supervisor', 'applications:assign')
on conflict do nothing;

insert into module_access (portal, module_key, role_key, level) values
    ('PLATFORM', 'apps', 'platform.application_reviewer', 'APPROVE'),
    ('PLATFORM', 'apps', 'platform.application_approver', 'APPROVE'),
    ('PLATFORM', 'apps', 'platform.application_supervisor', 'EDIT')
on conflict (portal, module_key, role_key) do nothing;
