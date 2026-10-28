-- S324: verified-purchase product reviews. A customer who received a product (orders-service says so) rates it 1-5
-- stars with an optional title, text and up to 5 photos. One live review per customer and product; new reviews pass
-- automatic checks and are published or wait in trust-ops' moderation queue; the product owner replies once.
create table product_review (
    id                uuid primary key,
    product_id        uuid         not null references product (id),
    atin              char(10)     not null,
    product_title     varchar(200) not null,
    seller_org_id     varchar(80)  not null,
    customer_id       varchar(80)  not null,
    reviewer_name     varchar(120) not null,
    order_id          varchar(80)  not null,
    order_number      varchar(40)  not null,
    rating            smallint     not null check (rating between 1 and 5),
    title             varchar(120),
    body              text,
    photos            jsonb        not null default '[]',
    status            varchar(12)  not null check (status in ('PENDING', 'PUBLISHED', 'REJECTED', 'REMOVED', 'DELETED')),
    flags             varchar(120) not null default '',
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    moderated_by      varchar(120),
    moderated_at      timestamptz,
    moderation_reason varchar(30),
    moderation_note   text,
    reply_text        text,
    reply_by          varchar(120),
    reply_at          timestamptz,
    report_count      int          not null default 0,
    version           bigint       not null default 0
);

-- One review per customer per product; a deleted review frees the slot.
create unique index product_review_one_per_customer on product_review (product_id, customer_id)
    where status <> 'DELETED';
create index product_review_public on product_review (product_id, created_at desc) where status = 'PUBLISHED';
create index product_review_public_rating on product_review (product_id, rating, created_at desc)
    where status = 'PUBLISHED';
create index product_review_seller on product_review (seller_org_id, created_at desc) where status = 'PUBLISHED';
create index product_review_customer on product_review (customer_id, created_at desc) where status <> 'DELETED';
create index product_review_status on product_review (status, created_at);

-- Customers and sellers report a published review; one report per reporter and review.
create table product_review_report (
    id            uuid primary key,
    review_id     uuid         not null references product_review (id) on delete cascade,
    reporter_id   varchar(80)  not null,
    reporter_kind varchar(10)  not null check (reporter_kind in ('CUSTOMER', 'SELLER')),
    reason_code   varchar(30)  not null,
    details       text,
    created_at    timestamptz  not null,
    unique (review_id, reporter_id)
);

-- Per-product aggregate over PUBLISHED reviews, rewritten in the transaction of every status change.
create table product_rating (
    product_id   uuid primary key references product (id),
    atin         char(10)      not null,
    average      numeric(3, 2) not null,
    review_count int           not null,
    star1        int           not null,
    star2        int           not null,
    star3        int           not null,
    star4        int           not null,
    star5        int           not null,
    updated_at   timestamptz   not null
);
