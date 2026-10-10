-- Storefront visibility of provider organisations, driven by seller-service compliance enforcement
-- (seller.listings.hidden / restored on at.seller-compliance.events). Independent of the provider setup, so an
-- event that arrives before the provider configures bookings is still honoured.
create table org_visibility (
    org_id      varchar(80) primary key,
    hidden      boolean     not null,
    reason      varchar(40),
    occurred_at timestamptz not null,
    updated_at  timestamptz not null
);
