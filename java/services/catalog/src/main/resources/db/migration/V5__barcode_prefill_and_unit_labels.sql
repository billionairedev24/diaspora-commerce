-- S031: cached answers of the product-data providers (GS1, Open Food Facts) per GTIN-14. An empty results array
-- is a cached miss. Shared by every pod; expired rows are refreshed on the next lookup.
create table product_data_lookup (
    gtin       varchar(14) primary key,
    found      boolean     not null,
    results    jsonb       not null,
    fetched_at timestamptz not null,
    expires_at timestamptz not null
);

-- S032: AT-FN unit label codes (ATF + 7 base-36), one per stock owner organisation and variant ATIN, so units of
-- different sellers are never mixed and platform stock without a vendor GTIN can be labelled at receiving.
create sequence if not exists unit_label_seq start with 1000;
create table unit_label (
    code       char(10)    primary key,
    owner_org  varchar(80) not null,
    atin       char(10)    not null,
    created_at timestamptz not null,
    unique (owner_org, atin)
);
