-- S205: compliance payout holds mirrored from seller-service (seller.payouts.paused / seller.payouts.resumed on
-- at.seller-compliance.events). While paused, payout requests and approvals are refused (payments.payouts_paused);
-- funds keep accruing. changed_at is the event's occurred-at, so an older event never overrides a newer one.
create table payout_hold (
    org_id     varchar(80)  primary key,
    account_id varchar(80),
    paused     boolean      not null,
    reason     varchar(40)  not null,
    actor_id   varchar(100),
    note       text,
    changed_at timestamptz  not null,
    updated_at timestamptz  not null
);
create index payout_hold_paused on payout_hold (org_id) where paused;
