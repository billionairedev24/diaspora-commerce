-- Markets & locations: states get a status (LIVE = marketplace on, PLANNED, OFF) and cities, so the back office
-- shows one tree (country → states → cities) of what is live or planned. Rows get optimistic-lock versions so
-- staff can edit in place (If-Match): a country's status checks market_country.version; a state's status, its
-- switches and its cities check market_region.version.

alter table market_country add column version bigint not null default 0;

alter table market_region
    add column planned    boolean not null default false,
    add column every_city boolean not null default true,
    add column version    bigint  not null default 0;

comment on column market_region.planned is
    'In use but not live: PLANNED while the marketplace is off (LIVE = marketplace on, otherwise OFF).';
comment on column market_region.every_city is
    'Every city of the state is covered (listed cities are overrides); false = only cities listed LIVE are served.';

-- Cities of a state. Eligibility with a city: LIVE served; PLANNED / OFF not served; unlisted follows every_city.
create table market_city (
    country    char(2)      not null,
    region     varchar(10)  not null,
    name       varchar(80)  not null,
    status     varchar(10)  not null check (status in ('LIVE', 'PLANNED', 'OFF')),
    position   int          not null,
    updated_at timestamptz  not null default now(),
    updated_by varchar(80)  not null default 'seed',
    foreign key (country, region) references market_region (country, code) on delete cascade
);
create unique index market_city_key on market_city (country, region, lower(name));

alter table market_region_published
    add column planned    boolean not null default false,
    add column every_city boolean not null default true;

create table market_city_published (
    version  int          not null references market_publication (version),
    country  char(2)      not null,
    region   varchar(10)  not null,
    name     varchar(80)  not null,
    status   varchar(10)  not null,
    position int          not null,
    primary key (version, country, region, name)
);
