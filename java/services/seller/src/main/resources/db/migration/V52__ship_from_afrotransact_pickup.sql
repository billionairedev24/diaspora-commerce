-- S255: AfroTransact Delivery from the seller's store. Ship-from addresses are geocoded (geo-service) and can be
-- enabled as AfroTransact pickup locations with pickup hours, once the organisation is approved and the location is
-- inside (or near) an active AfroTransact DELIVERY zone.
alter table ship_from_address add column latitude double precision check (latitude between -90 and 90);
alter table ship_from_address add column longitude double precision check (longitude between -180 and 180);
alter table ship_from_address add column geocode_precision varchar(10);
alter table ship_from_address add column geocoded_at timestamptz;
alter table ship_from_address add column afrotransact_pickup boolean not null default false;
-- [{"day": "MONDAY", "opens": "09:00", "closes": "17:00"}, …] (location's local time)
alter table ship_from_address add column pickup_hours jsonb not null default '[]'::jsonb;
alter table ship_from_address add column pickup_zone varchar(120);
alter table ship_from_address add column pickup_enabled_at timestamptz;
alter table ship_from_address add constraint ship_from_pickup_needs_position
    check (not afrotransact_pickup or (latitude is not null and longitude is not null));
create index ship_from_address_pickup on ship_from_address (org_id) where afrotransact_pickup;
