-- Sign-out policy per audience (customer-web, customer-app, seller, staff, driver): how long without activity and how
-- long in total before the gateway-bff and the apps end a session. Only audiences an admin changed have a row, and a
-- null column means "use the built-in default" (SessionPolicyDefaults).
create table session_policy_override (
    audience                 varchar(20) primary key
        check (audience in ('customer-web', 'customer-app', 'seller', 'staff', 'driver')),
    idle_timeout_minutes     integer,
    absolute_timeout_minutes integer,
    warn_seconds             integer,
    updated_at               timestamptz not null default now(),
    updated_by               varchar(100),
    version                  integer     not null default 0
);

-- The gateway-bff (client credentials) reads the effective policy; staff with identity:admin manage it.
insert into permission (key, module, description) values
    ('sessions:policy.read', 'identity', 'Read the effective sign-out policy per audience (service clients)')
on conflict (key) do nothing;
