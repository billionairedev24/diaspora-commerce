-- Category tax profiles (docs/engineering/category-tax-design.md): typed fact definitions, one profile per category with
-- a status gate and an ordered decision table, outcome-level internal tax codes, the per-provider code map and the rule
-- version history. The data comes from V12 (generated from tools/taxonomy/category_tax_map.json by
-- tools/taxonomy/gen_seed_sql.py). The old coarse classification tables (V5, V6) are kept untouched and are no longer
-- used by the quote path.

create table tax_fact_definition (
    id       varchar(60)  primary key,
    label    varchar(120) not null,
    kind     varchar(10)  not null check (kind in ('BOOLEAN', 'DECIMAL', 'INTEGER', 'PERCENT', 'CHOICE', 'FILE')),
    unit     varchar(20),
    owner    varchar(10)  not null check (owner in ('VARIANT', 'OFFER', 'SELLER', 'STAFF', 'CONTEXT')),
    help     text         not null,
    evidence boolean      not null default false,
    choices  jsonb        not null default '[]'::jsonb
);

create table tax_profile (
    category_code    varchar(80)  primary key,
    name             varchar(120) not null,
    department_code  varchar(80)  not null,
    department_name  varchar(120) not null,
    position         int          not null default 0,
    status           varchar(20)  not null
        check (status in ('ACTIVE', 'NEEDS_APPROVAL', 'BLOCKED_BUNDLE', 'REFINE_CODE', 'CONFLICT_OPEN')),
    status_reasons   jsonb        not null default '[]'::jsonb,
    default_outcome  varchar(12)  not null check (default_outcome in ('EXEMPT', 'TAXABLE', 'BLOCKED', 'CONDITIONAL')),
    stripe_codes     jsonb        not null default '[]'::jsonb,
    conflicting_codes jsonb       not null default '[]'::jsonb,
    fact_groups      jsonb        not null default '[]'::jsonb,
    note             text,
    changed_by       varchar(120) not null default 'system:seed',
    changed_at       timestamptz  not null default now(),
    version          bigint       not null default 0
);
create index tax_profile_department on tax_profile (department_code, position);

create table tax_profile_fact (
    category_code varchar(80) not null references tax_profile (category_code),
    fact_id       varchar(60) not null references tax_fact_definition (id),
    position      int         not null,
    required      boolean     not null,
    required_when jsonb,
    choices       jsonb       not null default '[]'::jsonb,
    primary key (category_code, fact_id)
);

-- One internal code per decision row; it names the OUTCOME, not the category.
create table tax_outcome_code (
    internal_tax_code varchar(120) primary key,
    category_code     varchar(80)  not null references tax_profile (category_code),
    outcome           varchar(10)  not null check (outcome in ('EXEMPT', 'TAXABLE', 'BLOCKED')),
    description       text         not null,
    rate_display      varchar(40),
    note              text
);
create index tax_outcome_code_category on tax_outcome_code (category_code);

create table tax_decision_row (
    id                varchar(8)   primary key,
    category_code     varchar(80)  not null references tax_profile (category_code),
    position          int          not null,
    scenario          int          not null unique,
    when_expr         jsonb,
    internal_tax_code varchar(120) not null unique references tax_outcome_code (internal_tax_code)
);
create index tax_decision_row_category on tax_decision_row (category_code, position);

-- The mapping is per OUTCOME code, never per category or per provider code: one provider code can carry different
-- outcomes in different scenarios. verified = a person confirmed the provider treats the code as this outcome.
create table provider_code_map (
    internal_tax_code varchar(120) not null references tax_outcome_code (internal_tax_code),
    provider          varchar(10)  not null check (provider in ('STRIPE', 'AVALARA')),
    provider_code     varchar(60),
    active            boolean      not null default true,
    verified          boolean      not null default false,
    verified_note     text,
    map_version       int          not null default 1,
    changed_by        varchar(120) not null default 'system:seed',
    changed_at        timestamptz  not null default now(),
    primary key (internal_tax_code, provider)
);
create index provider_code_map_provider on provider_code_map (provider, provider_code);

-- Every change of a profile's table, status or a code map entry creates a rule version; history is kept.
create table tax_rule_version (
    seq          bigserial primary key,
    rule_version varchar(80)  not null unique,
    base_version varchar(60)  not null,
    reason       text         not null,
    changed_by   varchar(120) not null,
    changed_at   timestamptz  not null default now()
);

-- Append-only audit of profile status and code map changes.
create table tax_profile_audit (
    id                bigserial primary key,
    kind              varchar(20)  not null check (kind in ('PROFILE_STATUS', 'PROVIDER_CODE_MAP')),
    category_code     varchar(80),
    internal_tax_code varchar(120),
    provider          varchar(10),
    at                timestamptz  not null,
    actor             varchar(120) not null,
    reason            text         not null,
    approved_by       varchar(160),
    decision_ref      varchar(160),
    rule_version      varchar(80)  not null,
    before_state      jsonb,
    after_state       jsonb
);
create index tax_profile_audit_category on tax_profile_audit (category_code, at desc);
