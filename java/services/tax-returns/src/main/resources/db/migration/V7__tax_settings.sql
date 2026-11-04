-- Platform tax settings, changed by staff at runtime (key/value) with an append-only history.
-- tax.method: RULES (our own rules over the rate tables, the default) or STRIPE_TAX (Stripe Tax, rules remain the fallback).
create table tax_setting (
    key        varchar(60)  primary key,
    value      varchar(200) not null,
    updated_by varchar(120) not null,
    updated_at timestamptz  not null
);

create table tax_setting_history (
    id         bigserial primary key,
    key        varchar(60)  not null,
    old_value  varchar(200),
    new_value  varchar(200) not null,
    reason     text         not null,
    changed_by varchar(120) not null,
    changed_at timestamptz  not null
);
create index tax_setting_history_key on tax_setting_history (key, changed_at desc);
