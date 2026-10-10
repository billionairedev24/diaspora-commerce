create sequence if not exists atin_seq start with 1000;
create sequence if not exists category_request_seq start with 1;

create table product_type (
    code            varchar(40)  primary key,
    label           varchar(120) not null,
    identifier_rule varchar(10)  not null check (identifier_rule in ('GTIN', 'ISBN')),
    attributes      jsonb        not null,
    themes          jsonb        not null
);

create table category (
    id                uuid primary key,
    parent_id         uuid references category (id),
    name              varchar(120) not null,
    level             varchar(20)  not null check (level in ('DEPARTMENT', 'CATEGORY', 'PRODUCT_TYPE')),
    product_type_code varchar(40)  references product_type (code),
    path              varchar(400) not null,
    position          int          not null default 0,
    active            boolean      not null default true,
    unique (parent_id, name)
);

create table category_request (
    id                   uuid primary key,
    reference            varchar(20)  not null unique,
    requested_by_org     varchar(80)  not null,
    parent_path          varchar(200) not null,
    name                 varchar(80)  not null,
    example_product      varchar(200) not null,
    details_needed       text         not null,
    status               varchar(20)  not null,
    resolved_category_id uuid references category (id),
    decision_note        text,
    created_at           timestamptz  not null,
    decided_at           timestamptz,
    version              bigint       not null default 0
);
create index category_request_org on category_request (requested_by_org);
create index category_request_status on category_request (status);

create table product (
    id                  uuid primary key,
    atin                char(10)     not null unique,
    owner_kind          varchar(10)  not null check (owner_kind in ('SELLER', 'PLATFORM')),
    owner_org           varchar(80)  not null,
    category_id         uuid references category (id),
    category_request_id uuid references category_request (id),
    product_type        varchar(40)  not null references product_type (code),
    title               varchar(200) not null,
    attributes          jsonb        not null,
    images              jsonb        not null,
    variation_theme     varchar(60)  not null,
    status              varchar(30)  not null,
    moderation_note     text,
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,
    version             bigint       not null default 0
);
create index product_owner on product (owner_org, created_at desc);
create index product_status on product (status);
create index product_category_request on product (category_request_id) where category_request_id is not null;

create table product_variant (
    id          uuid primary key,
    product_id  uuid         not null references product (id) on delete cascade,
    atin        char(10)     not null unique,
    owner_org   varchar(80)  not null,
    seller_sku  varchar(40)  not null,
    gtin        varchar(14),
    gtin_exempt boolean      not null default false,
    options     jsonb        not null,
    price_minor bigint       not null check (price_minor > 0),
    currency    char(3)      not null,
    quantity    int          not null check (quantity >= 0),
    position    int          not null,
    unique (owner_org, seller_sku)
);
create index product_variant_gtin on product_variant (gtin) where gtin is not null;
