-- S205: seller.listings.hidden / restored (seller-service compliance enforcement, keyed by org). While an
-- organisation is hidden its products are excluded from public reads and the search index; the products themselves
-- keep their moderation status so restoring brings back exactly what was live. changed_at is the event's
-- occurred-at, so an older decision never overrides a newer one.
create table org_listing_suppression (
    org_id     varchar(80) primary key,
    hidden     boolean     not null,
    reason     varchar(40) not null,
    changed_at timestamptz not null,
    updated_at timestamptz not null
);
create index org_listing_suppression_hidden on org_listing_suppression (org_id) where hidden;
