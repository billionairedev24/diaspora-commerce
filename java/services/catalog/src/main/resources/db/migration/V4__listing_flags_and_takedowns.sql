-- S228: reports / flags on live listings (published as catalog.product.flagged for the trust-ops moderation queue)
-- and Trust and Safety takedowns of live listings (product status TAKEN_DOWN until restored).
create table product_flag (
    id          uuid         primary key,
    product_id  uuid         not null references product (id),
    owner_org   varchar(80)  not null,
    source      varchar(20)  not null check (source in ('CUSTOMER_REPORT', 'STAFF', 'AUTOMATED')),
    reason_code varchar(40)  not null,
    details     text,
    reporter_id varchar(160),
    risk_level  varchar(20),
    created_at  timestamptz  not null
);
create index product_flag_product on product_flag (product_id, created_at desc);
-- One report per shopper and listing; repeated reports are acknowledged without a new event.
create unique index product_flag_customer on product_flag (product_id, reporter_id)
    where source = 'CUSTOMER_REPORT';

create table product_takedown (
    id              uuid         primary key,
    product_id      uuid         not null references product (id),
    reason_code     varchar(40)  not null,
    note            text,
    actor_id        varchar(160) not null,
    previous_status varchar(30)  not null check (previous_status in ('LIVE', 'PAUSED')),
    taken_down_at   timestamptz  not null,
    restored_at     timestamptz,
    restored_by     varchar(160),
    restore_note    text,
    version         bigint       not null default 0
);
create unique index product_takedown_active on product_takedown (product_id) where restored_at is null;
