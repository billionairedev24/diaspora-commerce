-- FBA fee per unit per plan: plans without a row pay the version's default fba_per_unit_minor.
create table fee_schedule_plan_fba (
    schedule_id        uuid        not null references fee_schedule (id),
    plan_code          varchar(80) not null check (length(trim(plan_code)) > 0),
    fba_per_unit_minor bigint      not null check (fba_per_unit_minor >= 0),
    primary key (schedule_id, plan_code)
);

create trigger fee_schedule_plan_fba_immutable before update or delete on fee_schedule_plan_fba
    for each row execute function ledger_reject_change();
