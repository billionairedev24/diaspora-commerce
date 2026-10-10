create table customer_address (
    id           uuid primary key,
    user_id      uuid         not null references user_account (id) on delete cascade,
    label        varchar(30),
    full_name    varchar(100) not null,
    phone        varchar(20),
    line1        varchar(120) not null,
    line2        varchar(60),
    city         varchar(60)  not null,
    state        varchar(10),
    postal_code  varchar(12)  not null,
    country      char(2)      not null,
    latitude     double precision,
    longitude    double precision,
    instructions varchar(300),
    verified     boolean      not null default false,
    is_default   boolean      not null default false,
    created_at   timestamptz  not null,
    updated_at   timestamptz  not null
);
create index customer_address_user on customer_address (user_id);
create unique index customer_address_one_default on customer_address (user_id) where is_default;
