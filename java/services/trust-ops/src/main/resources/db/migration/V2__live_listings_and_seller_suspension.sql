-- S228: flagged live listings join the moderation queue next to new submissions, and seller suspension goes through
-- seller-service's own suspension hold (POST /v1/orgs/{orgId}/suspend | reinstate) instead of the compliance payout
-- hold, so the step flag records "seller-service suspended the organisation" (payouts held and listings hidden).
alter table moderation_item
    add column item_type      varchar(20)  not null default 'NEW_LISTING'
        check (item_type in ('NEW_LISTING', 'LIVE_LISTING')),
    add column flag_id        uuid,
    add column report_source  varchar(20)  check (report_source in ('CUSTOMER_REPORT', 'STAFF', 'AUTOMATED')),
    add column report_reason  varchar(40),
    add column report_details text,
    add column reporter_id    varchar(160),
    add column report_risk    varchar(20),
    add column report_count   integer      not null default 0;
create index moderation_item_type on moderation_item (item_type, status, due_at);

alter table seller_enforcement rename column payouts_held to seller_suspended;
