-- Decisions made in the same instant keep the order they were made in. The log was ordered by (decided_at, id) with a
-- random UUID as the tie-breaker, so an "awaiting a decision" entry and the staff decision that followed it within the
-- same clock tick could come back in either order (run_action_log already orders by a seq for the same reason).
alter table preorder_window_decision add column seq bigint generated always as identity;
create index preorder_window_decision_order on preorder_window_decision (window_id, decided_at, seq);
drop index preorder_window_decision_window;
