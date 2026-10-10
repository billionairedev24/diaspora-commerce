-- S237: the SLA sweep reminds the seller once, 24 h before the claim's response deadline
-- (trustops.claim.response_due_soon). The timestamp makes the reminder idempotent per claim.
alter table claim add column response_reminder_sent_at timestamptz;
create index claim_response_reminder on claim (due_at)
    where status = 'AWAITING_SELLER' and response_reminder_sent_at is null;
