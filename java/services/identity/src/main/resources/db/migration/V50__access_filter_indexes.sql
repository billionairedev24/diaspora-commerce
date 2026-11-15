-- Exact "who holds this role" and "changes to this role / permission" queries for the staff console
-- (GET /v1/admin/users?role=, GET /v1/admin/access-log?role=&permission=): no scanning.
create index if not exists user_role_role_idx on user_role (role_key, user_id);
create index if not exists access_change_log_role_idx on access_change_log (target_role, id desc);
create index if not exists access_change_log_subject_idx on access_change_log (subject, id desc);
