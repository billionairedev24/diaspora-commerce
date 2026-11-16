-- Shipment ownership by fulfilment mode (docs/engineering/delivery-fulfilment.md): a seller who ships their own orders is
-- responsible for their status and tracking. Admins can see them and, with this permission only, override the status
-- (forward only, a reason is required, audited). Granted to Super Admin only; give it to anyone else in Roles and permissions.
-- Idempotent: safe to run on a database that already has the rows.
insert into permission (key, module, description) values
    ('orders:override-shipment', 'orders', 'Override the status of a shipment the seller ships themselves (forward only, reason required, audited)')
on conflict (key) do update set module = excluded.module, description = excluded.description;

insert into role_permission (role_key, permission_key) values
    ('platform.super_admin', 'orders:override-shipment')
on conflict do nothing;
