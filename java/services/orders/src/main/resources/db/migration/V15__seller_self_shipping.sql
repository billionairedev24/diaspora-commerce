-- Seller self-shipping, behind a staff switch that is OFF by default (docs/engineering/seller-self-shipping-options.md).
-- Two additions, neither of which changes an existing row's behaviour:
--
-- 1. shipping_settings.seller_self_shipping_enabled: the staff switch (default false, so uat and prod stay off). The old
--    seller_carrier_shipping_enabled column is kept for compatibility and is ignored (carrier labels are retired).
-- 2. seller_self_shipping: ONE flat per-order shipping charge per seller organisation, set once by the seller (not per
--    product). The seller keeps it; AfroTransact takes no commission on it. It is only read while the switch is on.
--
-- Existing shipments keep delivery_method SELLER_SHIPS / CARRIER and stay read-only history (LEGACY_SELLER_SHIPPED); new
-- self-shipped shipments carry the new method SELLER_SELF_SHIPS, so history is never confused with live data.
alter table shipping_settings
    add column seller_self_shipping_enabled boolean not null default false;

create table seller_self_shipping (
    seller_org_id text        primary key,
    currency      text        not null check (currency ~ '^[A-Z]{3}$'),
    -- The flat charge per order package of this seller (0 = the seller ships free). Same ceiling as the delivery fee.
    charge_minor  bigint      not null check (charge_minor between 0 and 10000),
    -- The seller's own opt-in: false = this seller does not ship themselves, whatever the staff switch says.
    enabled       boolean     not null default true,
    version       bigint      not null check (version >= 1),
    updated_at    timestamptz not null,
    updated_by    text        not null
);
