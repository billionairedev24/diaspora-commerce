-- The tax decision attaches to the VARIANT ATIN. variant_classification holds the variant's facts and its review state;
-- listing_tax_decision holds the stored decision per (variant ATIN, behaviour hash) because several sellers may offer one
-- variant and each seller's behaviour (sold heated, utensils...) can change the outcome.

create table variant_classification (
    atin                    varchar(40)  primary key,
    gtin                    varchar(20),
    product_id              uuid,
    title                   varchar(300),
    owner_org_id            varchar(80),
    category_code           varchar(80)  not null,
    product_type_code       varchar(80),
    facts                   jsonb        not null default '{}'::jsonb,
    staff_facts             jsonb        not null default '{}'::jsonb,
    seller_behaviour        jsonb        not null default '{}'::jsonb,
    refined_provider_codes  jsonb        not null default '{}'::jsonb,
    status                  varchar(20)  not null
        check (status in ('DRAFT', 'MISSING_FACTS', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'STALE')),
    resolution              jsonb,
    rule_version            varchar(80),
    classified_by           varchar(120),
    classified_at           timestamptz,
    reviewed_by             varchar(120),
    reviewed_at             timestamptz,
    decision_ref            varchar(160),
    info_request            text,
    info_requested_by       varchar(120),
    info_requested_at       timestamptz,
    version                 bigint       not null default 0,
    created_at              timestamptz  not null default now(),
    updated_at              timestamptz  not null default now()
);
create index variant_classification_queue on variant_classification (status, updated_at);
create index variant_classification_category on variant_classification (category_code, status);
create index variant_classification_owner on variant_classification (owner_org_id);

create table variant_classification_audit (
    id           bigserial primary key,
    atin         varchar(40)  not null,
    at           timestamptz  not null,
    actor        varchar(120) not null,
    action       varchar(20)  not null
        check (action in ('FACTS_CHANGED', 'SUBMITTED', 'APPROVED', 'REJECTED', 'RECLASSIFIED', 'MARKED_STALE', 'EVIDENCE_ADDED', 'INFORMATION_REQUESTED')),
    reason       text,
    rule_version varchar(80),
    before_state jsonb,
    after_state  jsonb
);
create index variant_classification_audit_atin on variant_classification_audit (atin, at, id);

create table variant_classification_evidence (
    id          bigserial primary key,
    atin        varchar(40)  not null,
    fact_id     varchar(60)  not null,
    file_name   varchar(200),
    url         varchar(500),
    uploaded_by varchar(120),
    uploaded_at timestamptz  not null default now()
);
create index variant_classification_evidence_atin on variant_classification_evidence (atin);

create table listing_tax_decision (
    atin             varchar(40)  not null,
    behaviour_hash   varchar(64)  not null,
    seller_behaviour jsonb        not null default '{}'::jsonb,
    seller_org_ids   jsonb        not null default '[]'::jsonb,
    category_code    varchar(80)  not null,
    facts_snapshot   jsonb        not null default '{}'::jsonb,
    status           varchar(20)  not null,
    outcome          varchar(10),
    internal_tax_code varchar(120),
    provider_codes   jsonb        not null default '{}'::jsonb,
    rule_version     varchar(80)  not null,
    reason           text,
    missing_facts    jsonb        not null default '[]'::jsonb,
    decided_at       timestamptz  not null,
    decided_by       varchar(120) not null,
    approved_by      varchar(120),
    primary key (atin, behaviour_hash)
);
create index listing_tax_decision_code on listing_tax_decision (internal_tax_code);

-- Shadow mode: the provider's quote next to the in-house quote on real orders. Never charged.
create table tax_shadow_quote (
    id                  bigserial primary key,
    quoted_at           timestamptz  not null,
    order_ref           varchar(80),
    line_id             varchar(80)  not null,
    atin                varchar(40),
    provider            varchar(10)  not null check (provider in ('STRIPE', 'AVALARA')),
    state               varchar(10),
    category_code       varchar(80),
    internal_tax_code   varchar(120),
    provider_code       varchar(60),
    kind                varchar(20)  not null
        check (kind in ('MATCH', 'OUTCOME', 'RATE', 'BOTH', 'PROVIDER_ERROR', 'NO_DECISION')),
    in_house_outcome    varchar(10),
    in_house_rate       numeric(9, 6),
    in_house_tax_minor  bigint,
    provider_outcome    varchar(10),
    provider_rate       numeric(9, 6),
    provider_tax_minor  bigint,
    provider_error      varchar(300),
    difference_minor    bigint       not null default 0,
    currency            char(3)
);
create index tax_shadow_quote_time on tax_shadow_quote (provider, quoted_at);
create index tax_shadow_quote_code on tax_shadow_quote (internal_tax_code, kind);

-- Placed order lines and ledger entries keep the decision they were taxed under, so refunds reverse with it. Internal
-- codes name outcomes and are longer than the old coarse codes.
alter table order_line_snapshot alter column tax_code type varchar(120);
alter table tax_transaction add column internal_tax_code varchar(120);
alter table tax_transaction add column outcome varchar(10);
alter table tax_transaction add column rule_version varchar(80);
alter table tax_transaction add column address_precision varchar(10);
