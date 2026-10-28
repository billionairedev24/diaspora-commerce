-- S260: staff notes on a run, and the append-only log of what staff (or the cutoff job) decided about it.
alter table run add column notes varchar(500);

create table run_action_log (
    id          uuid          primary key,
    -- Entries made in the same instant keep the order they were made in.
    seq         bigint        generated always as identity,
    run_id      uuid          not null references run (id) on delete cascade,
    kind        varchar(24)   not null check (kind in ('EXTEND', 'CONFIRM', 'CONFIRM_OVERRIDE', 'CANCEL')),
    reason      varchar(500),
    actor       varchar(120)  not null,
    from_status varchar(24)   not null,
    to_status   varchar(24)   not null,
    orders      integer       not null,
    minimum     integer       not null,
    from_cutoff timestamptz,
    to_cutoff   timestamptz,
    acted_at    timestamptz   not null,
    -- A staff override of the minimum and a cancellation always say why.
    check (kind not in ('CANCEL', 'CONFIRM_OVERRIDE') or (reason is not null and length(trim(reason)) > 0))
);
create index run_action_log_run on run_action_log (run_id, acted_at);
