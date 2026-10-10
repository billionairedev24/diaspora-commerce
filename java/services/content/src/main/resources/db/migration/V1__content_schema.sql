-- Content service: placements, markets & locations, site config, legal documents, cookie consent,
-- feature flags, newsletter, launch lists, careers and the admin audit log.

-- ---------------------------------------------------------------- placements
create table slot (
    key            varchar(40)  primary key,
    name           varchar(80)  not null,
    description    text         not null,
    max_items      int          not null check (max_items between 1 and 50),
    size_spec      text         not null,
    payload_schema jsonb        not null,
    sort_order     int          not null default 0
);

create table placement (
    id            varchar(40)  primary key,
    slot          varchar(40)  not null references slot (key),
    name          varchar(120) not null,
    status        varchar(20)  not null check (status in ('DRAFT', 'IN_REVIEW', 'SCHEDULED', 'LIVE', 'ENDED')),
    priority      int          not null default 0,
    payload       jsonb        not null,
    targeting     jsonb        not null,
    starts_at     timestamptz  not null,
    ends_at       timestamptz,
    frequency_cap jsonb        not null,
    paid          boolean      not null default false,
    advertiser    varchar(120),
    created_by    varchar(80)  not null,
    submitted_by  varchar(80),
    approved_by   varchar(80),
    review_note   text,
    version       bigint       not null default 0,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    constraint placement_window check (ends_at is null or ends_at > starts_at)
);
create index placement_status_slot on placement (status, slot);

-- ---------------------------------------------------------------- markets (draft working copy)
create table market_country (
    code        char(2)      primary key,
    name        varchar(80)  not null,
    status      varchar(20)  not null check (status in ('LIVE', 'PAUSED', 'COMING_SOON', 'PLANNED')),
    launch_note text,
    currency    char(3),
    sort_order  int          not null default 1000,
    updated_at  timestamptz  not null default now(),
    updated_by  varchar(80)  not null default 'seed'
);

create table market_region (
    country          char(2)     not null references market_country (code),
    code             varchar(10) not null,
    name             varchar(80) not null,
    kind             varchar(20) not null check (kind in ('STATE', 'DISTRICT', 'TERRITORY', 'PROVINCE', 'NATION')),
    marketplace      boolean     not null default false,
    services         boolean     not null default false,
    food             boolean     not null default false,
    pickup           boolean     not null default false,
    tax_registration boolean     not null default false,
    zip_prefixes     jsonb       not null default '[]',
    primary key (country, code),
    -- turning the marketplace off turns everything off
    constraint region_marketplace_cascade check (marketplace or not (services or food or pickup or tax_registration))
);

-- Published, immutable snapshots: eligibility only ever reads the latest version.
create table market_publication (
    version      int          primary key,
    published_at timestamptz  not null,
    published_by varchar(80)  not null,
    note         text
);

create table market_country_published (
    version     int          not null references market_publication (version),
    code        char(2)      not null,
    name        varchar(80)  not null,
    status      varchar(20)  not null,
    launch_note text,
    currency    char(3),
    sort_order  int          not null,
    primary key (version, code)
);

create table market_region_published (
    version          int         not null references market_publication (version),
    country          char(2)     not null,
    code             varchar(10) not null,
    name             varchar(80) not null,
    kind             varchar(20) not null,
    marketplace      boolean     not null,
    services         boolean     not null,
    food             boolean     not null,
    pickup           boolean     not null,
    tax_registration boolean     not null,
    zip_prefixes     jsonb       not null,
    primary key (version, country, code)
);

create table launch_list_entry (
    id               uuid         primary key,
    email            varchar(254) not null,
    country          char(2)      not null references market_country (code),
    audience         varchar(20)  not null check (audience in ('SELLER', 'PROVIDER', 'SHOPPER')),
    shopping_updates boolean      not null default false,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    unique (email, country, audience)
);

-- ---------------------------------------------------------------- site config (append-only revisions)
create table site_config_revision (
    revision   int          primary key,
    document   jsonb        not null,
    summary    text,
    created_at timestamptz  not null,
    created_by varchar(80)  not null
);

-- ---------------------------------------------------------------- legal
create table legal_version (
    id             uuid         primary key,
    doc_type       varchar(40)  not null,
    version        varchar(20)  not null,
    major          int          not null,
    minor          int          not null,
    patch          int          not null,
    effective_date date         not null,
    status         varchar(20)  not null check (status in ('CURRENT', 'ARCHIVED')),
    draft_banner   boolean      not null default true,
    bodies         jsonb        not null,
    change_summary text,
    created_at     timestamptz  not null,
    created_by     varchar(80)  not null,
    updated_at     timestamptz  not null,
    archived_at    timestamptz,
    unique (doc_type, major, minor, patch)
);
create unique index legal_version_one_current on legal_version (doc_type) where status = 'CURRENT';

create table cookie_category (
    key         varchar(30)  primary key,
    name        varchar(60)  not null,
    description text         not null,
    required    boolean      not null default false,
    gpc_opt_out boolean      not null default false,
    sort_order  int          not null default 0
);

create table cookie_definition (
    name     varchar(80)  primary key,
    provider varchar(80)  not null,
    purpose  text         not null,
    duration varchar(40)  not null,
    category varchar(30)  not null references cookie_category (key)
);

create table consent_record (
    id             uuid         primary key,
    subject        varchar(120) not null,
    categories     jsonb        not null,
    gpc            boolean      not null,
    policy_version varchar(20)  not null,
    source         varchar(30)  not null,
    recorded_at    timestamptz  not null
);
create index consent_record_subject on consent_record (subject, recorded_at desc);

-- ---------------------------------------------------------------- feature flags
create table feature_flag (
    key                varchar(64)   primary key,
    description        text          not null,
    enabled            boolean       not null default false,
    rollout_percentage numeric(5, 2) not null default 0 check (rollout_percentage between 0 and 100),
    kill_switch        boolean       not null default false,
    targeting          jsonb         not null,
    version            bigint        not null default 0,
    updated_at         timestamptz   not null,
    updated_by         varchar(80)   not null
);

-- ---------------------------------------------------------------- newsletter
create table newsletter_subscription (
    id              uuid         primary key,
    email           varchar(254) not null unique,
    status          varchar(20)  not null check (status in ('PENDING', 'CONFIRMED', 'UNSUBSCRIBED')),
    locale          varchar(10)  not null,
    source          varchar(40)  not null,
    created_at      timestamptz  not null,
    confirmed_at    timestamptz,
    unsubscribed_at timestamptz,
    updated_at      timestamptz  not null
);

-- ---------------------------------------------------------------- careers
create table job (
    id               varchar(60)  primary key,
    title            varchar(120) not null,
    team             varchar(60)  not null,
    location         varchar(80)  not null,
    employment_type  varchar(40)  not null,
    pay              varchar(60)  not null,
    summary          text         not null,
    responsibilities jsonb        not null,
    requirements     jsonb        not null,
    status           varchar(20)  not null check (status in ('DRAFT', 'OPEN', 'CLOSED')),
    sort_order       int          not null default 0,
    posted_at        timestamptz,
    updated_at       timestamptz  not null
);

create sequence career_reference_seq start with 100001 minvalue 100001 maxvalue 999999 no cycle;

create table job_application (
    id                 uuid         primary key,
    reference          varchar(12)  not null unique,
    job_id             varchar(60)  not null references job (id),
    name               varchar(120) not null,
    email              varchar(254) not null,
    linkedin_url       varchar(300),
    work_authorization varchar(30)  not null,
    resume_asset_id    varchar(80)  not null,
    resume_file_name   varchar(200) not null,
    cover_note         text,
    consent_at         timestamptz  not null,
    submitted_at       timestamptz  not null
);
create index job_application_job on job_application (job_id, submitted_at desc);

-- ---------------------------------------------------------------- audit
create table audit_log (
    id          bigserial    primary key,
    at          timestamptz  not null,
    actor       varchar(80)  not null,
    action      varchar(60)  not null,
    entity_type varchar(40)  not null,
    entity_id   varchar(80)  not null,
    detail      jsonb        not null
);
create index audit_log_entity on audit_log (entity_type, entity_id, at desc);
