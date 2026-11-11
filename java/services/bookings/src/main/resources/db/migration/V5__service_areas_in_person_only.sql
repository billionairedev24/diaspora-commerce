-- Services are in person only (no online/virtual/remote), providers declare where they work, metro zones.

-- 1. No virtual services: strip the mode; a service that was virtual-only is switched off (the provider re-lists it in
--    person from the Provider Hub); historic bookings made as VIRTUAL are rewritten to IN_PERSON.
update offering set modes = (select coalesce(jsonb_agg(m), '[]'::jsonb) from jsonb_array_elements(modes) m
                             where m <> to_jsonb('VIRTUAL'::text))
where modes @> '["VIRTUAL"]'::jsonb;
update offering set modes = '["IN_PERSON"]'::jsonb, active = false where jsonb_array_length(modes) = 0;
update booking set service = jsonb_set(service, '{mode}', '"IN_PERSON"') where service ->> 'mode' = 'VIRTUAL';

alter table offering add column image_url varchar(500);

-- 2. Provider service area: base location (ZIP centroid from geo), one radius, named extra areas, and the cities
--    the radius reaches (computed on save from geo's ZIP table) so results can say "Serves Georgetown, Hutto, ...".
create table provider_service_area (
    org_id        varchar(80) primary key references provider (org_id),
    base_zip      char(5)      not null,
    base_city     varchar(80)  not null,
    base_state    char(2)      not null,
    lat           double precision not null,
    lon           double precision not null,
    radius_miles  int          not null check (radius_miles between 1 and 150),
    extra_areas   jsonb        not null default '[]',
    served_cities jsonb        not null default '[]',
    updated_at    timestamptz  not null
);
create index provider_service_area_state on provider_service_area (base_state);

-- 3. Metro zones (staff-edited; every change is audited with its reason) and the radius policy (one row).
create table service_zone (
    key        varchar(40) primary key,
    name       varchar(80) not null,
    cities     jsonb       not null,
    updated_at timestamptz not null default now()
);
create table service_area_policy (
    id         int primary key check (id = 1),
    policy     jsonb       not null,
    updated_at timestamptz not null default now()
);
create table service_area_audit (
    id         bigserial primary key,
    at         timestamptz  not null default now(),
    actor      varchar(120) not null,
    subject    varchar(80)  not null,
    reason     text         not null,
    change     jsonb        not null
);

insert into service_area_policy (id, policy) values (1, '{
  "byCategory": {
    "hair & beauty": {"defaultMiles": 20, "minMiles": 5, "maxMiles": 40},
    "catering":      {"defaultMiles": 40, "minMiles": 10, "maxMiles": 100},
    "tailoring":     {"defaultMiles": 25, "minMiles": 5, "maxMiles": 60},
    "tax & legal":   {"defaultMiles": 30, "minMiles": 5, "maxMiles": 60}
  },
  "fallback": {"defaultMiles": 25, "minMiles": 5, "maxMiles": 60},
  "customerTravelMiles": 30,
  "nearestMaxMiles": 60
}');

insert into service_zone (key, name, cities) values
    ('greater-austin', 'Greater Austin', '[{"city":"Austin","state":"TX"},{"city":"Georgetown","state":"TX"},{"city":"Round Rock","state":"TX"},{"city":"Hutto","state":"TX"},{"city":"Pflugerville","state":"TX"},{"city":"Cedar Park","state":"TX"},{"city":"Leander","state":"TX"},{"city":"Taylor","state":"TX"},{"city":"Manor","state":"TX"},{"city":"Liberty Hill","state":"TX"},{"city":"Dripping Springs","state":"TX"},{"city":"Elgin","state":"TX"},{"city":"Jarrell","state":"TX"},{"city":"Kyle","state":"TX"},{"city":"Buda","state":"TX"},{"city":"San Marcos","state":"TX"},{"city":"Bastrop","state":"TX"}]'::jsonb),
    ('houston', 'Houston', '[{"city":"Houston","state":"TX"},{"city":"Sugar Land","state":"TX"},{"city":"Katy","state":"TX"},{"city":"Pearland","state":"TX"},{"city":"Spring","state":"TX"},{"city":"Cypress","state":"TX"},{"city":"Missouri City","state":"TX"},{"city":"Stafford","state":"TX"},{"city":"Humble","state":"TX"},{"city":"Baytown","state":"TX"},{"city":"Pasadena","state":"TX"},{"city":"Tomball","state":"TX"},{"city":"Conroe","state":"TX"},{"city":"League City","state":"TX"},{"city":"Friendswood","state":"TX"},{"city":"Richmond","state":"TX"},{"city":"Rosenberg","state":"TX"}]'::jsonb),
    ('dallas-fort-worth', 'Dallas-Fort Worth', '[{"city":"Dallas","state":"TX"},{"city":"Fort Worth","state":"TX"},{"city":"Arlington","state":"TX"},{"city":"Plano","state":"TX"},{"city":"Irving","state":"TX"},{"city":"Garland","state":"TX"},{"city":"Frisco","state":"TX"},{"city":"Grand Prairie","state":"TX"},{"city":"Mesquite","state":"TX"},{"city":"Carrollton","state":"TX"},{"city":"Richardson","state":"TX"},{"city":"Lewisville","state":"TX"},{"city":"Denton","state":"TX"},{"city":"Allen","state":"TX"},{"city":"Mansfield","state":"TX"},{"city":"Lancaster","state":"TX"},{"city":"Duncanville","state":"TX"},{"city":"Euless","state":"TX"},{"city":"Grapevine","state":"TX"},{"city":"Rowlett","state":"TX"}]'::jsonb),
    ('new-york-city', 'New York City', '[{"city":"New York","state":"NY"},{"city":"Brooklyn","state":"NY"},{"city":"Bronx","state":"NY"},{"city":"Staten Island","state":"NY"},{"city":"Yonkers","state":"NY"},{"city":"Mount Vernon","state":"NY"},{"city":"New Rochelle","state":"NY"},{"city":"Jamaica","state":"NY"},{"city":"Flushing","state":"NY"},{"city":"Astoria","state":"NY"},{"city":"Long Island City","state":"NY"},{"city":"Queens Village","state":"NY"},{"city":"Jackson Heights","state":"NY"},{"city":"Elmhurst","state":"NY"},{"city":"Ridgewood","state":"NY"},{"city":"Far Rockaway","state":"NY"},{"city":"Hempstead","state":"NY"},{"city":"Valley Stream","state":"NY"}]'::jsonb),
    ('north-jersey', 'North & Central Jersey', '[{"city":"Newark","state":"NJ"},{"city":"Jersey City","state":"NJ"},{"city":"Hoboken","state":"NJ"},{"city":"Elizabeth","state":"NJ"},{"city":"Paterson","state":"NJ"},{"city":"Edison","state":"NJ"},{"city":"East Orange","state":"NJ"},{"city":"Irvington","state":"NJ"},{"city":"Union City","state":"NJ"},{"city":"Bayonne","state":"NJ"},{"city":"Secaucus","state":"NJ"},{"city":"Woodbridge","state":"NJ"},{"city":"New Brunswick","state":"NJ"},{"city":"Piscataway","state":"NJ"},{"city":"Plainfield","state":"NJ"},{"city":"Passaic","state":"NJ"}]'::jsonb),
    ('dc-metro', 'Washington DC Metro', '[{"city":"Washington","state":"DC"},{"city":"Silver Spring","state":"MD"},{"city":"Bowie","state":"MD"},{"city":"Hyattsville","state":"MD"},{"city":"Alexandria","state":"VA"},{"city":"Arlington","state":"VA"},{"city":"Falls Church","state":"VA"},{"city":"Rockville","state":"MD"},{"city":"Takoma Park","state":"MD"}]'::jsonb),
    ('atlanta', 'Atlanta', '[{"city":"Atlanta","state":"GA"},{"city":"Decatur","state":"GA"},{"city":"Marietta","state":"GA"},{"city":"Smyrna","state":"GA"},{"city":"Stone Mountain","state":"GA"},{"city":"Lithonia","state":"GA"},{"city":"Duluth","state":"GA"},{"city":"Roswell","state":"GA"},{"city":"Tucker","state":"GA"},{"city":"Norcross","state":"GA"}]'::jsonb),
    ('miami', 'Miami', '[{"city":"Miami","state":"FL"},{"city":"Hialeah","state":"FL"},{"city":"Miami Beach","state":"FL"},{"city":"Fort Lauderdale","state":"FL"},{"city":"Hollywood","state":"FL"},{"city":"Pembroke Pines","state":"FL"}]'::jsonb);
