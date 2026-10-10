create extension if not exists postgis;

create table if not exists zone (
    id           uuid primary key,
    type         text        not null check (type in ('DELIVERY', 'FC_CATCHMENT', 'SERVICE_AREA')),
    owner_id     text        not null,
    name         text        not null,
    area         geography(MultiPolygon, 4326),
    center       geography(Point, 4326),
    radius_m     double precision,
    active       boolean     not null default true,
    updated_at   timestamptz not null default now(),
    check (area is not null or (center is not null and radius_m > 0))
);
create index if not exists zone_area_gix on zone using gist (area);
create index if not exists zone_center_gix on zone using gist (center);
create index if not exists zone_owner on zone (type, owner_id);
