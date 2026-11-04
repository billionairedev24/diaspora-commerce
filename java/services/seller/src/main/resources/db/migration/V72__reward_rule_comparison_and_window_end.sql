-- S299: reward rules that reward a quiet start ("the 2nd month is free if you sold less than $1,000 in the first 30
-- days"). A rule compares its metric AT_LEAST (reach the threshold, as before) or LESS_THAN (stay under it); its window
-- starts at approval (as before) or at the subscription's start (the trial's start); and a rule can be checked once,
-- when its window closes, instead of as the metric moves (always so for LESS_THAN).
alter table reward_rule add column comparison varchar(12) not null default 'AT_LEAST'
    check (comparison in ('AT_LEAST', 'LESS_THAN'));
alter table reward_rule add column window_anchor varchar(20) not null default 'APPROVAL'
    check (window_anchor in ('APPROVAL', 'SUBSCRIPTION_START'));
alter table reward_rule add column check_at_window_end boolean not null default false;
-- The check runs this long before the window closes (or before the renewal when the window closes with the trial or
-- billing period), so a free period is in place before Stripe creates the invoice.
alter table reward_rule add column check_lead_hours int not null default 24 check (check_lead_hours between 0 and 168);
alter table reward_rule add constraint reward_rule_window_end_check
    check ((comparison = 'AT_LEAST' and not check_at_window_end) or within_days_of_approval is not null);

-- When the subscription started (the trial's first day): the first time a Stripe subscription was mirrored. Existing
-- subscriptions take their current period start (a trialing subscription's period is its trial).
alter table subscription add column started_at timestamptz;
update subscription set started_at = coalesce(current_period_start, updated_at) where stripe_subscription_id is not null;

-- One check per (rule, organisation, window): a rule checked at window end is decided once, whatever the outcome.
create table reward_window_check (
    rule_id      uuid        not null references reward_rule (id),
    org_id       varchar(40) not null references organisation (id),
    window_start date        not null,
    window_end   timestamptz not null,
    checked_at   timestamptz not null,
    value        bigint      not null,
    threshold    bigint      not null,
    comparison   varchar(12) not null,
    -- QUALIFIED (a grant was recorded with it) or NOT_QUALIFIED.
    outcome      varchar(16) not null check (outcome in ('QUALIFIED', 'NOT_QUALIFIED')),
    reason       text,
    grant_id     uuid        references billing_grant (id),
    primary key (rule_id, org_id, window_start)
);
create index reward_window_check_org on reward_window_check (org_id, checked_at desc);
