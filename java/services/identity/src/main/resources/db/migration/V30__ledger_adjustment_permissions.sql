-- Ledger adjustments (payments): one person proposes a correcting journal, a different person approves it (four eyes,
-- enforced by payments). Both are Finance staff powers; Super Admin has them too.
insert into permission (key, module, description) values
    ('ledger:adjust', 'payments', 'Propose a correcting ledger adjustment and mark reconciliation differences explained'),
    ('ledger:approve', 'payments', 'Approve or reject a proposed ledger adjustment (never your own)')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key) values
    ('platform.super_admin', 'ledger:adjust'),
    ('platform.super_admin', 'ledger:approve'),
    ('platform.finance', 'ledger:adjust'),
    ('platform.finance', 'ledger:approve')
on conflict do nothing;
