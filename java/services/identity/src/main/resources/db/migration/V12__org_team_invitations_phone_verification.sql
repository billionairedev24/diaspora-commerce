-- S231 organisation member directory and invitations, S234 phone number verification, and the tax and carrier
-- (shipping) permissions used by tax-returns, orders and carrier-gateway.

-- ---------------------------------------------------------------- permissions
insert into permission (key, module, description) values
    ('tax:quote', 'tax', 'Calculate sales tax for a cart or order (service access)'),
    ('tax:read', 'tax', 'View tax transactions, liabilities and returns'),
    ('tax:configure', 'tax', 'Configure tax registrations, nexus and filing settings'),
    ('shipping:rate', 'delivery', 'Rate shopping, tracking look-ups and address validation (carrier gateway)'),
    ('shipping:label', 'delivery', 'Buy shipping labels, register tracking and book carrier pickups'),
    ('shipping:void', 'delivery', 'Void unused shipping labels'),
    ('shipping:admin', 'delivery', 'Manage every pickup location and carrier-gateway settings')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.super_admin', 'tax:quote'),
    ('platform.super_admin', 'tax:read'), ('platform.finance', 'tax:read'), ('platform.auditor', 'tax:read'),
    ('platform.super_admin', 'tax:configure'), ('platform.finance', 'tax:configure'),
    -- carrier gateway: staff who run fulfilment and sellers who ship their own orders
    ('platform.super_admin', 'shipping:rate'), ('platform.operations_manager', 'shipping:rate'),
    ('platform.fulfilment_ops', 'shipping:rate'), ('platform.dispatcher', 'shipping:rate'),
    ('platform.customer_support', 'shipping:rate'),
    ('seller.owner', 'shipping:rate'), ('seller.admin', 'shipping:rate'), ('seller.fulfilment', 'shipping:rate'),
    ('platform.super_admin', 'shipping:label'), ('platform.operations_manager', 'shipping:label'),
    ('platform.fulfilment_ops', 'shipping:label'),
    ('seller.owner', 'shipping:label'), ('seller.admin', 'shipping:label'), ('seller.fulfilment', 'shipping:label'),
    ('platform.super_admin', 'shipping:void'), ('platform.operations_manager', 'shipping:void'),
    ('platform.fulfilment_ops', 'shipping:void'),
    ('seller.owner', 'shipping:void'), ('seller.admin', 'shipping:void'), ('seller.fulfilment', 'shipping:void')
) as r(role_key, permission_key)
on conflict do nothing;

-- shipping:admin goes to every role that already manages carrier integrations (shipping:manage).
insert into role_permission (role_key, permission_key)
select role_key, 'shipping:admin' from role_permission where permission_key = 'shipping:manage'
on conflict do nothing;

-- ---------------------------------------------------------------- organisation invitations
-- The single-use token is only ever stored as its SHA-256; EXPIRED is derived from expires_at.
create table org_invitation (
    id          uuid primary key,
    org_id      varchar(80)  not null references organization (id) on delete cascade,
    email       varchar(254) not null,
    role_key    varchar(80)  not null references role (key),
    token_hash  char(64)     not null,
    status      varchar(10)  not null check (status in ('PENDING', 'ACCEPTED', 'REVOKED')),
    invited_by  varchar(100) not null,
    created_at  timestamptz  not null,
    expires_at  timestamptz  not null,
    accepted_at timestamptz,
    accepted_by uuid references user_account (id) on delete set null,
    revoked_at  timestamptz,
    revoked_by  varchar(100)
);
create unique index org_invitation_token_uq on org_invitation (token_hash);
-- One open invitation per email and organisation (inviting again replaces it).
create unique index org_invitation_pending_uq on org_invitation (org_id, lower(email)) where status = 'PENDING';
create index org_invitation_org_idx on org_invitation (org_id, status, created_at desc);

-- ---------------------------------------------------------------- phone verification
-- The number the account confirmed with an SMS code. Changing user_account.phone_e164 deletes the row.
create table phone_verification (
    user_id     uuid primary key references user_account (id) on delete cascade,
    phone_e164  varchar(20) not null,
    verified_at timestamptz not null
);

-- Phones already confirmed as an SMS sign-in factor stay verified.
insert into phone_verification (user_id, phone_e164, verified_at)
select s.user_id, s.phone_e164, s.enabled_at
from mfa_sms s join user_account u on u.id = s.user_id and u.phone_e164 = s.phone_e164
on conflict (user_id) do nothing;
