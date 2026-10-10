-- S231: ship-from addresses of a seller organisation (label purchase in Seller Center). One default per org.
create table ship_from_address (
    id          uuid         primary key,
    org_id      varchar(40)  not null references organisation (id),
    label       varchar(60)  not null,
    name        varchar(120) not null,
    company     varchar(120),
    phone       varchar(40),
    line1       varchar(120) not null,
    line2       varchar(120),
    city        varchar(80)  not null,
    state       varchar(40)  not null,
    postal_code varchar(12)  not null,
    country     char(2)      not null,
    is_default  boolean      not null default false,
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null,
    version     bigint       not null default 0
);
create index ship_from_address_org on ship_from_address (org_id, created_at);
create unique index ship_from_address_default on ship_from_address (org_id) where is_default;
