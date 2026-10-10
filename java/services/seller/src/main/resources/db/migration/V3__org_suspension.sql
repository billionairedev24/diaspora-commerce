-- S228: Trust & Safety suspension of an organisation, kept apart from Stripe compliance payout holds
-- (connect_account.payouts_paused / hold_reason). Reinstating releases only the suspension hold; payouts and
-- listings come back only when no compliance hold still requires them.
create table org_suspension (
    id              uuid         primary key,
    org_id          varchar(40)  not null references organisation (id),
    reason_code     varchar(40)  not null check (reason_code in ('POOR_PERFORMANCE', 'PROHIBITED_ITEMS',
                        'COUNTERFEIT', 'FRAUD_RISK', 'POLICY_VIOLATION', 'COMPLIANCE', 'OTHER')),
    note            text,
    actor_id        varchar(160) not null,
    previous_status varchar(32)  not null,
    suspended_at    timestamptz  not null,
    reinstated_at   timestamptz,
    reinstated_by   varchar(160),
    reinstate_note  text,
    version         bigint       not null default 0
);
-- At most one active suspension hold per organisation.
create unique index org_suspension_active on org_suspension (org_id) where reinstated_at is null;
create index org_suspension_org on org_suspension (org_id, suspended_at desc);
