-- Plan assignments by staff (S306): who moved an organisation to which plan, and why. The organisation's own plan
-- changes are not recorded here; they are on the subscription events.
create table plan_assignment (
    id          uuid primary key,
    org_id      varchar(40)  not null references organisation (id),
    from_plan   varchar(60),
    to_plan     varchar(60)  not null,
    cadence     varchar(16)  not null,
    applied     varchar(24)  not null,
    reason      text         not null,
    assigned_by varchar(120) not null,
    assigned_at timestamptz  not null
);
create index plan_assignment_org on plan_assignment (org_id, assigned_at desc);
