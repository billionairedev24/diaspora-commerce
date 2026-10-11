-- Customer pickup locations: where they are (checkout shows the distance from the delivery address) when known.
alter table pickup_location add column if not exists latitude double precision;
alter table pickup_location add column if not exists longitude double precision;
