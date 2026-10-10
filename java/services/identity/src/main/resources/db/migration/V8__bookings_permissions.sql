-- Bookings service permission keys. The bookings service checks module-prefixed keys; the V2 seed used generic ones
-- (services:write, availability:manage, team:manage). Every role holding an old key also gets the new key, so
-- existing grants carry over; the old keys stay because other modules (identity's org team) still use team:manage.
insert into permission (key, module, description) values
    ('bookings:services:write', 'bookings', 'Services, prices, deposits, add-ons and cancellation policies'),
    ('bookings:availability:write', 'bookings', 'Working hours, breaks, time off and booking rules'),
    ('bookings:team:manage', 'bookings', 'Assign team members to services and manage their calendars')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select rp.role_key, m.new_key
from role_permission rp
join (values
    ('services:write', 'bookings:services:write'),
    ('availability:manage', 'bookings:availability:write')
) as m(old_key, new_key) on m.old_key = rp.permission_key
on conflict do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.super_admin', 'bookings:services:write'),
    ('platform.super_admin', 'bookings:availability:write'),
    ('platform.super_admin', 'bookings:team:manage'),
    ('provider.owner', 'bookings:read'), ('provider.owner', 'bookings:manage'),
    ('provider.owner', 'bookings:services:write'), ('provider.owner', 'bookings:availability:write'),
    ('provider.owner', 'bookings:team:manage'),
    ('provider.manager', 'bookings:read'), ('provider.manager', 'bookings:manage'),
    ('provider.manager', 'bookings:services:write'), ('provider.manager', 'bookings:availability:write'),
    ('provider.manager', 'bookings:team:manage'),
    -- practitioners: own calendar and bookings only (scoped in the bookings service)
    ('provider.practitioner', 'bookings:read'), ('provider.practitioner', 'bookings:manage'),
    ('provider.practitioner', 'bookings:availability:write'),
    ('provider.finance', 'bookings:read'),
    ('platform.customer_support', 'bookings:read'), ('platform.customer_support', 'bookings:manage')
) as r(role_key, permission_key)
on conflict do nothing;
