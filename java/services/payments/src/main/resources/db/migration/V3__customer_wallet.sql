-- Saved customer payment methods (owned by payments-service; gateway-bff calls the /v1/customers/me API).
create table stripe_customer (
    customer_id               varchar(120) primary key,
    stripe_customer_id        varchar(80)  not null unique,
    default_payment_method_id varchar(80),
    created_at                timestamptz  not null
);

create table customer_payment_method (
    id           varchar(80) primary key,
    customer_id  varchar(120) not null references stripe_customer (customer_id),
    type         varchar(30)  not null,
    brand        varchar(40),
    last4        char(4),
    exp_month    integer,
    exp_year     integer,
    bank_name    varchar(120),
    account_type varchar(40),
    verified     boolean      not null,
    created_at   timestamptz  not null,
    detached_at  timestamptz
);
create index customer_payment_method_owner on customer_payment_method (customer_id) where detached_at is null;
