-- A plan's "charge once the condition is met" has been met by this organisation: charging stays on.
create table plan_policy_state (
    org_id varchar(40) primary key references organisation (id),
    met_at timestamptz not null
);
