-- ADR 0005 §7: customers pay in US dollars, but stock is sourced and received in the currency actually paid (MXN, NGN,
-- GHS, CAD, EUR, GBP …). A lot keeps its cost in that currency, the exchange rate to USD used when it was received
-- (with the date and where it came from) and the USD value fixed at that moment, so valuation, COGS and margin are in
-- USD while the original amount stays visible. A staff-managed table of rates by currency and date is the default
-- suggestion at receiving; every change to it is audited with a reason.

-- Until now every lot was costed in USD (AfroTransact Retail's base currency; FBA stock carries no cost): backfill them
-- as USD at rate 1 with the whole cost under "goods".
update lot set currency = 'USD' where currency <> 'USD';
update reservation_allocation set currency = 'USD' where currency <> 'USD';

alter table lot
    add column fx_rate_to_usd      numeric(20, 10) check (fx_rate_to_usd > 0),
    add column fx_rate_date        date,
    add column fx_rate_source      varchar(8) check (fx_rate_source in ('BASE', 'ENTERED', 'TABLE')),
    add column unit_cost_usd_minor bigint check (unit_cost_usd_minor >= 0),
    add column cost_goods_minor    bigint check (cost_goods_minor >= 0),
    add column cost_freight_minor  bigint check (cost_freight_minor >= 0),
    add column cost_duty_minor     bigint check (cost_duty_minor >= 0),
    add column cost_other_minor    bigint check (cost_other_minor >= 0);

update lot
set fx_rate_to_usd      = 1,
    fx_rate_date        = (received_at at time zone 'UTC')::date,
    fx_rate_source      = 'BASE',
    unit_cost_usd_minor = unit_landed_cost_minor,
    cost_goods_minor    = unit_landed_cost_minor,
    cost_freight_minor  = 0,
    cost_duty_minor     = 0,
    cost_other_minor    = 0;

alter table lot
    alter column fx_rate_to_usd set not null,
    alter column fx_rate_date set not null,
    alter column fx_rate_source set not null,
    alter column unit_cost_usd_minor set not null,
    alter column cost_goods_minor set not null,
    alter column cost_freight_minor set not null,
    alter column cost_duty_minor set not null,
    alter column cost_other_minor set not null,
    add constraint lot_cost_components_add_up
        check (cost_goods_minor + cost_freight_minor + cost_duty_minor + cost_other_minor = unit_landed_cost_minor),
    add constraint lot_usd_has_rate_one check (currency <> 'USD' or fx_rate_to_usd = 1);

-- COGS at shipment is taken from the USD value of the lots the units were allocated from.
alter table reservation_allocation add column unit_cost_usd_minor bigint;
update reservation_allocation set unit_cost_usd_minor = unit_landed_cost_minor;
alter table reservation_allocation alter column unit_cost_usd_minor set not null;

-- Rates by currency and date: 1 unit of the currency = rate_to_usd US dollars. The latest row on or before the receiving
-- date is the default suggestion; staff may always enter another rate on the receipt itself.
create table exchange_rate (
    currency    char(3)         not null,
    rate_date   date            not null,
    rate_to_usd numeric(20, 10) not null check (rate_to_usd > 0),
    note        varchar(200),
    updated_by  varchar(120)    not null,
    created_at  timestamptz     not null,
    updated_at  timestamptz     not null,
    version     bigint          not null default 0,
    primary key (currency, rate_date),
    constraint exchange_rate_not_usd check (currency <> 'USD')
);

-- Append-only: who set which rate, what it was before and why.
create table exchange_rate_audit (
    id          bigint generated always as identity primary key,
    occurred_at timestamptz     not null,
    currency    char(3)         not null,
    rate_date   date            not null,
    action      varchar(8)      not null check (action in ('CREATE', 'UPDATE')),
    old_rate    numeric(20, 10),
    new_rate    numeric(20, 10) not null check (new_rate > 0),
    reason      text            not null check (length(trim(reason)) > 0),
    actor       varchar(120)    not null
);
create index exchange_rate_audit_currency on exchange_rate_audit (currency, id desc);

create function exchange_rate_audit_append_only() returns trigger
    language plpgsql as
$$
begin
    raise exception 'exchange_rate_audit is append-only: % is not allowed', tg_op using errcode = '55000';
end;
$$;
create trigger exchange_rate_audit_no_update_delete
    before update or delete on exchange_rate_audit
    for each row execute function exchange_rate_audit_append_only();
create trigger exchange_rate_audit_no_truncate
    before truncate on exchange_rate_audit
    for each statement execute function exchange_rate_audit_append_only();
