-- Withdrawal of a scheduled fee-schedule version before it takes effect. Fee schedules stay immutable: the version
-- row is never updated or deleted; a cancellation is its own append-only row (who, when, why) and the version is then
-- skipped when picking the schedule in force. Only versions whose effective_from is still in the future can be
-- cancelled (enforced by the application), so no order was ever placed under a cancelled version.
create table fee_schedule_cancellation (
    schedule_id  uuid primary key references fee_schedule (id),
    reason       text         not null check (length(trim(reason)) > 0),
    cancelled_by varchar(120) not null,
    cancelled_at timestamptz  not null
);

create trigger fee_schedule_cancellation_immutable before update or delete on fee_schedule_cancellation
    for each row execute function ledger_reject_change();
