-- Review decisions: the staff member's display name on the timeline (the "already approved by …" message) and the
-- decision's Idempotency-Key, so a retried or double-submitted decision replays instead of failing.
alter table application_event add column actor_name varchar(200);
alter table application_event add column request_key varchar(128);
create unique index application_event_request_key on application_event (application_id, request_key)
    where request_key is not null;
