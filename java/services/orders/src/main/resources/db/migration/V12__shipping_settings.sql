-- Launch shipping (listing rules D16, D18): AfroTransact sets the shopper's delivery charge. ONE staff-editable record
-- per currency is the single source of the fee: the gateway-bff estimate reads it (GET /v1/public/shipping-settings) and
-- orders charges by it at placement, so what the shopper sees is what is charged. Every change is a new version with who,
-- when and a required reason (shipping_settings_change keeps the record before and after).
create table shipping_settings (
    currency                        text        primary key check (currency ~ '^[A-Z]{3}$'),
    version                         bigint      not null check (version >= 1),
    -- Delivery by AfroTransact, and AfroTransact collects from the store and delivers: one flat fee per package.
    flat_fee_minor                  bigint      not null check (flat_fee_minor between 0 and 10000),
    -- Items subtotal of a package from which that delivery is free (null = never free).
    free_above_minor                bigint      check (free_above_minor is null or free_above_minor > 0),
    -- Seller-shipped carrier option at checkout. OFF until sellers ship themselves (D16, D17).
    seller_carrier_shipping_enabled boolean     not null default false,
    updated_at                      timestamptz not null,
    updated_by                      text        not null,
    reason                          text        not null
);

create table shipping_settings_change (
    id          uuid        primary key,
    currency    text        not null,
    version     bigint      not null,
    before      jsonb,
    after       jsonb       not null,
    actor_id    text        not null,
    reason      text        not null,
    changed_at  timestamptz not null,
    unique (currency, version)
);

-- The launch default ($5.99, no free-above threshold, carrier off), so a value always exists and checkout never has to
-- guess one.
insert into shipping_settings (currency, version, flat_fee_minor, free_above_minor, seller_carrier_shipping_enabled,
                               updated_at, updated_by, reason)
values ('USD', 1, 599, null, false, now(), 'system', 'Launch default (listing rules D16)');
insert into shipping_settings_change (id, currency, version, before, after, actor_id, reason, changed_at)
values ('00000000-0000-4000-8000-000000000a01', 'USD', 1, null,
        '{"flatFeeMinor":599,"freeAboveMinor":null,"sellerCarrierShippingEnabled":false}'::jsonb, 'system',
        'Launch default (listing rules D16)', now());
