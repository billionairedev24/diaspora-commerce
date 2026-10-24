-- ADR 0005 §3: delivery fees are configuration staff manage, by delivery method, in one rule resolution that window
-- checkout, late adds, runs and carrier shipping all share. This replaces the flat per-currency door / pickup fee
-- maps of the pre-order payment settings.
--
--   AFROTRANSACT_DELIVERY  flat fee by distance band (config.bands)
--   CARRIER                carrier-gateway quote + handling; fallback when there is no quote; free over a threshold
--   PICKUP                 the pickup fee (often 0)
--
-- One rule per (method, currency); every change bumps version and is logged with who and why.

create table delivery_fee_rule (
    id         uuid         primary key,
    method     varchar(24)  not null check (method in ('AFROTRANSACT_DELIVERY', 'CARRIER', 'PICKUP')),
    currency   char(3)      not null,
    config     jsonb        not null,
    version    bigint       not null,
    updated_at timestamptz  not null,
    updated_by varchar(120) not null,
    reason     varchar(500) not null,
    unique (method, currency)
);

-- Append-only audit of every change: the rule before and after, who, when, why.
create table delivery_fee_rule_change (
    id         uuid         primary key,
    rule_id    uuid         not null references delivery_fee_rule (id),
    method     varchar(24)  not null,
    currency   char(3)      not null,
    version    bigint       not null,
    before     jsonb,
    after      jsonb        not null,
    actor      varchar(120) not null,
    reason     varchar(500) not null,
    changed_at timestamptz  not null
);
create index delivery_fee_rule_change_rule on delivery_fee_rule_change (method, currency, changed_at desc);

-- Carry the existing door and pickup fees over as version 1 of the rules (one band 0-100 miles at the old door fee).
insert into delivery_fee_rule (id, method, currency, config, version, updated_at, updated_by, reason)
select gen_random_uuid(), 'AFROTRANSACT_DELIVERY', f.key,
       jsonb_build_object('bands', jsonb_build_array(jsonb_build_object(
               'fromMiles', 0, 'toMiles', 100, 'feeMinor', f.value::bigint))),
       1, now(), 'migration', 'Carried over from the pre-order payment settings'
from preorder_settings s, jsonb_each_text(coalesce(s.payment -> 'doorFees', '{"USD": 799}'::jsonb)) as f(key, value)
where s.id = 1;

insert into delivery_fee_rule (id, method, currency, config, version, updated_at, updated_by, reason)
select gen_random_uuid(), 'PICKUP', c.currency,
       jsonb_build_object('feeMinor', coalesce((s.payment -> 'pickupFees' ->> c.currency)::bigint, 0)),
       1, now(), 'migration', 'Carried over from the pre-order payment settings'
from preorder_settings s, (select currency from delivery_fee_rule where method = 'AFROTRANSACT_DELIVERY') as c
where s.id = 1;

-- Carrier shipping beyond coverage: what orders-service charged until now ($5.99, free over $35).
insert into delivery_fee_rule (id, method, currency, config, version, updated_at, updated_by, reason)
values (gen_random_uuid(), 'CARRIER', 'USD',
        '{"handlingMinor": 0, "fallbackMinor": 599, "freeAboveMinor": 3500}'::jsonb,
        1, now(), 'migration', 'The flat carrier rate orders-service used before fee rules');

update preorder_settings set payment = payment - 'doorFees' - 'pickupFees' where id = 1;

-- Each rule's first version is in the audit trail too.
insert into delivery_fee_rule_change (id, rule_id, method, currency, version, before, after, actor, reason, changed_at)
select gen_random_uuid(), id, method, currency, version, null,
       config || jsonb_build_object('method', method, 'currency', currency), updated_by, reason, updated_at
from delivery_fee_rule;
