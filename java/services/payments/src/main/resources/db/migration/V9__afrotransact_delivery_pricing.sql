-- S255: AfroTransact Delivery from the seller's store or warehouse. The seller packs, an AfroTransact driver collects
-- and delivers; AfroTransact charges by distance (or a flat fee).
--
-- 1. A third fulfilment mode on payment lines, with the seller's pickup-delivery fee quoted at checkout (snapshotted
--    by orders), what the capture charged of it (credited to 4250 Local delivery fees; never for AfroTransact Retail)
--    and what customer-fault refunds gave back of it.
alter table payment_line drop constraint payment_line_fulfilment_check;
alter table payment_line alter column fulfilment type varchar(30);
alter table payment_line add constraint payment_line_fulfilment_check
    check (fulfilment in ('FBA', 'SELF', 'AFROTRANSACT_DELIVERY'));
alter table payment_line add column delivery_fee_minor bigint not null default 0 check (delivery_fee_minor >= 0);
alter table payment_line add column delivery_fee_charged_minor bigint not null default 0;
alter table payment_line add column delivery_fee_reversed_minor bigint not null default 0;
alter table payment_line add constraint payment_line_delivery_fee_charged_check
    check (delivery_fee_charged_minor between 0 and delivery_fee_minor);
alter table payment_line add constraint payment_line_delivery_fee_reversed_check
    check (delivery_fee_reversed_minor between 0 and delivery_fee_charged_minor);

-- 2. The "Delivery pricing" section of a fee-schedule version: immutable child rows, written once with the version
--    (append-only like fee_schedule_rate / _override). A version without a row offers no AfroTransact Delivery from
--    seller stores (quotes say NOT_CONFIGURED).
create table fee_schedule_delivery (
    schedule_id      uuid          primary key references fee_schedule (id),
    max_radius_miles numeric(6, 2) not null check (max_radius_miles > 0 and max_radius_miles <= 200)
);

-- One tariff per party: CUSTOMER (the shopper's delivery charge, org_id '') and SELLER (the seller's pickup-delivery
-- fee; org_id '' = default, else a per-seller override like commission overrides). Bands are
-- [{fromMiles, toMiles, feeMinor}] (DISTANCE only).
create table fee_schedule_delivery_tariff (
    schedule_id              uuid        not null references fee_schedule_delivery (schedule_id),
    party                    varchar(10) not null check (party in ('CUSTOMER', 'SELLER')),
    org_id                   varchar(80) not null default '',
    mode                     varchar(10) not null check (mode in ('FLAT', 'DISTANCE')),
    flat_per_package_minor   bigint      not null default 0 check (flat_per_package_minor >= 0),
    base_minor               bigint      not null default 0 check (base_minor >= 0),
    per_mile_minor           bigint      not null default 0 check (per_mile_minor >= 0),
    bands                    jsonb       not null default '[]'::jsonb,
    min_minor                bigint      not null default 0 check (min_minor >= 0),
    max_minor                bigint               check (max_minor is null or max_minor >= min_minor),
    primary key (schedule_id, party, org_id),
    check (party = 'SELLER' or org_id = '')
);

create trigger fee_schedule_delivery_immutable before update or delete on fee_schedule_delivery
    for each row execute function ledger_reject_change();
create trigger fee_schedule_delivery_tariff_immutable before update or delete on fee_schedule_delivery_tariff
    for each row execute function ledger_reject_change();

-- Launch defaults on the seed version (local / test environments start with it; production creates a new version
-- in the staff fee editor): customer $4.99 + $0.85/mi (min $5.99, max $24.99) within 25 miles, seller $2.00 per
-- package.
insert into fee_schedule_delivery (schedule_id, max_radius_miles)
select id, 25 from fee_schedule where id = '00000000-0000-0000-0000-00000000fee1';
insert into fee_schedule_delivery_tariff (schedule_id, party, org_id, mode, base_minor, per_mile_minor, min_minor,
                                          max_minor)
select id, 'CUSTOMER', '', 'DISTANCE', 499, 85, 599, 2499 from fee_schedule
 where id = '00000000-0000-0000-0000-00000000fee1';
insert into fee_schedule_delivery_tariff (schedule_id, party, org_id, mode, flat_per_package_minor)
select id, 'SELLER', '', 'FLAT', 200 from fee_schedule where id = '00000000-0000-0000-0000-00000000fee1';
