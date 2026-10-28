-- S260: the staff console gates run and pre-order actions on preorders:manage, while delivery-runs checks delivery-runs:manage.
-- Whoever may manage pre-orders may manage runs, so the buttons they see no longer answer 403.
insert into role_permission (role_key, permission_key)
select role_key, 'delivery-runs:manage' from role_permission where permission_key = 'preorders:manage'
on conflict do nothing;
