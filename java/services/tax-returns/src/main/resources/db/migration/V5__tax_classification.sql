-- Tax classification (docs/engineering/tax-classification-design.md): what an item is for tax, decided from the
-- catalog product type and the seller's answers by staff-maintained rules.

create table tax_code (
    code          varchar(40)  primary key,
    label         varchar(120) not null,
    category      varchar(20)  not null check (category in ('GENERAL', 'GROCERY', 'PREPARED_FOOD')),
    provider_code varchar(60),
    active        boolean      not null default true,
    updated_at    timestamptz  not null default now()
);

create table tax_classification_rule (
    id           bigserial    primary key,
    product_type varchar(80)  not null,
    attr_key     varchar(80),
    attr_value   varchar(120),
    tax_code     varchar(40)  not null references tax_code (code),
    note         varchar(300),
    updated_at   timestamptz  not null default now(),
    constraint tax_rule_attr_pair check ((attr_key is null) = (attr_value is null))
);
create unique index tax_rule_unique
    on tax_classification_rule (product_type, coalesce(attr_key, ''), coalesce(lower(attr_value), ''));

-- Items seen on placed orders whose classification needs a person: the staff review queue.
create table tax_classification_seen (
    product_type varchar(80)  not null,
    attributes   jsonb        not null default '{}'::jsonb,
    attr_hash    varchar(64)  not null,
    tax_code     varchar(40)  not null,
    basis        varchar(10)  not null,
    sample_atin  varchar(40),
    seen_count   bigint       not null default 1,
    first_seen   timestamptz  not null default now(),
    last_seen    timestamptz  not null default now(),
    reviewed_at  timestamptz,
    reviewed_by  varchar(120),
    review_note  varchar(300),
    primary key (product_type, attr_hash)
);

alter table order_line_snapshot add column tax_code varchar(40);
create index order_line_snapshot_tax_code on order_line_snapshot (tax_code);

insert into tax_code (code, label, category, provider_code) values
    ('GENERAL_GOODS', 'General taxable goods', 'GENERAL', 'txcd_99999999'),
    ('CLOTHING', 'Clothing and accessories', 'GENERAL', null),
    ('BOOKS', 'Books', 'GENERAL', null),
    ('PERSONAL_CARE', 'Cosmetics and personal care', 'GENERAL', null),
    ('OTC_MEDICINE', 'Over-the-counter medicine', 'GENERAL', null),
    ('PACKAGED_FOOD', 'Packaged food (shelf-stable)', 'GROCERY', null),
    ('FRESH_PRODUCE', 'Fresh fruit and vegetables', 'GROCERY', null),
    ('FROZEN_FOOD', 'Frozen or raw food, not prepared', 'GROCERY', null),
    ('PREPARED_FOOD', 'Hot or ready-to-eat food', 'PREPARED_FOOD', null),
    ('CANDY', 'Candy and confectionery', 'GENERAL', null),
    ('SOFT_DRINK', 'Soft drinks and sweetened beverages', 'GENERAL', null),
    ('BOTTLED_WATER', 'Bottled water', 'GENERAL', null),
    ('SUPPLEMENT', 'Dietary supplements', 'GENERAL', null);

-- Provider codes stay empty (except general goods) until staff copy them from the provider's list; a quote with a
-- missing provider code is taxed by the rules engine instead. Food, drink, supplement and drug rules below are
-- the conservative reading (taxable unless positively exempt); the CPA must confirm them before go-live.
insert into tax_classification_rule (product_type, attr_key, attr_value, tax_code, note) values
    ('dress', null, null, 'CLOTHING', 'Clothing'),
    ('top', null, null, 'CLOTHING', 'Clothing'),
    ('headwrap', null, null, 'CLOTHING', 'Clothing accessory'),
    ('menwear', null, null, 'CLOTHING', 'Clothing'),
    ('kids', null, null, 'CLOTHING', 'Clothing'),
    ('fabric', null, null, 'GENERAL_GOODS', 'Fabric by the yard: taxable goods'),
    ('book', null, null, 'BOOKS', 'Books'),
    ('home', null, null, 'GENERAL_GOODS', 'Home goods'),
    ('hair', null, null, 'GENERAL_GOODS', 'Braiding hair'),
    ('beauty', null, null, 'PERSONAL_CARE', 'Cosmetics and skincare unless the seller says it is medicated'),
    ('beauty', 'tax_class', 'Medicated (sunscreen, dandruff, acne)', 'OTC_MEDICINE', 'Drug-like products'),
    ('grocery', 'tax_class', 'Packaged, shelf-stable', 'PACKAGED_FOOD', 'Food for home consumption'),
    ('grocery', 'tax_class', 'Fresh fruit or vegetables', 'FRESH_PRODUCE', 'Food for home consumption'),
    ('grocery', 'tax_class', 'Frozen, not prepared', 'FROZEN_FOOD', 'Food for home consumption'),
    ('grocery', 'tax_class', 'Hot or ready-to-eat', 'PREPARED_FOOD', 'Prepared food'),
    ('grocery', 'tax_class', 'Candy or sweets', 'CANDY', 'Taxable in most states'),
    ('grocery', 'tax_class', 'Soft drink or sweetened drink', 'SOFT_DRINK', 'Taxable in most states'),
    ('grocery', 'tax_class', 'Bottled water', 'BOTTLED_WATER', 'Taxable in some states'),
    ('grocery', 'tax_class', 'Dietary supplement', 'SUPPLEMENT', 'Taxable in some states'),
    ('perishable', 'tax_class', 'Raw meat or fish', 'FROZEN_FOOD', 'Food for home consumption'),
    ('perishable', 'tax_class', 'Fresh fruit or vegetables', 'FRESH_PRODUCE', 'Food for home consumption'),
    ('perishable', 'tax_class', 'Frozen, not prepared', 'FROZEN_FOOD', 'Food for home consumption'),
    ('perishable', 'tax_class', 'Cooked, hot or ready-to-eat', 'PREPARED_FOOD', 'Prepared food');
-- grocery, perishable and beauty without an answer, and custom, fall to GENERAL_GOODS and appear in the review queue.
