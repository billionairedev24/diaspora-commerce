-- seller-service schema: organisations, onboarding applications with two-step review, Stripe Connect compliance
-- mirror, plans and Stripe Billing subscriptions. Outbox/inbox tables come from classpath:db/platform.

create table organisation (
    id           varchar(40)  primary key,
    kind         varchar(16)  not null check (kind in ('SELLER', 'PROVIDER')),
    legal_name   varchar(200),
    display_name varchar(120),
    country      char(2),
    status       varchar(32)  not null check (status in ('DRAFT', 'PENDING_REVIEW', 'FLAGGED',
                     'AWAITING_FINAL_APPROVAL', 'INFO_REQUESTED', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    created_at   timestamptz  not null,
    updated_at   timestamptz  not null,
    version      bigint       not null default 0
);
create index organisation_status on organisation (status);

create table org_member (
    org_id     varchar(40) not null references organisation (id),
    user_id    varchar(80) not null,
    role_key   varchar(60) not null,
    created_at timestamptz not null,
    primary key (org_id, user_id)
);
create index org_member_user on org_member (user_id);

create sequence application_reference_seq start with 30200;

create table application (
    id                varchar(40)  primary key,
    reference         varchar(20)  not null unique,
    org_id            varchar(40)  not null unique references organisation (id),
    applicant_user_id varchar(80)  not null unique,
    kind              varchar(16)  not null,
    status            varchar(32)  not null check (status in ('DRAFT', 'PENDING_REVIEW', 'FLAGGED',
                          'AWAITING_FINAL_APPROVAL', 'INFO_REQUESTED', 'APPROVED', 'REJECTED')),
    current_step      varchar(40)  not null,
    -- Wizard sections. The tax ID is only present as last 4 + AES-GCM ciphertext (FieldCipher).
    data              jsonb        not null,
    prefilled_fields  jsonb        not null default '[]',
    reviewer_id       varchar(80),
    reviewed_at       timestamptz,
    final_approver_id varchar(80),
    decided_at        timestamptz,
    failed_checks     jsonb        not null default '[]',
    requested_items   jsonb        not null default '[]',
    reason_code       varchar(60),
    submission_count  int          not null default 0,
    submitted_at      timestamptz,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    version           bigint       not null default 0,
    -- Two-step review: the final approver is never the reviewer.
    constraint application_two_people check (final_approver_id is null or reviewer_id is null
                                             or final_approver_id <> reviewer_id)
);
create index application_queue on application (status, submitted_at);

create table application_event (
    id             uuid         primary key,
    application_id varchar(40)  not null references application (id),
    action         varchar(32)  not null,
    from_status    varchar(32),
    to_status      varchar(32)  not null,
    actor_id       varchar(80)  not null,
    reason_code    varchar(60),
    note           text,
    items          jsonb        not null default '[]',
    occurred_at    timestamptz  not null
);
create index application_event_app on application_event (application_id, occurred_at);

create table review_reason_code (
    code       varchar(60)  primary key,
    label      varchar(200) not null,
    applies_to varchar(16)  not null check (applies_to in ('APPROVE', 'FLAG', 'INFO', 'REJECT', 'ANY')),
    sort_order int          not null default 0
);

-- Every reviewer view of a document or of unmasked PII.
create table document_access_log (
    id             uuid        primary key,
    application_id varchar(40) not null references application (id),
    asset_id       varchar(80),
    kind           varchar(40) not null,
    viewer_id      varchar(80) not null,
    accessed_at    timestamptz not null
);
create index document_access_log_app on document_access_log (application_id, accessed_at);

create table launch_list (
    id               uuid         primary key,
    country          char(2)      not null,
    email            varchar(254) not null,
    shopping_updates boolean      not null default false,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    unique (country, email)
);

create table plan (
    code          varchar(40)  primary key,
    kind          varchar(16)  not null check (kind in ('SELLER', 'PROVIDER')),
    name          varchar(80)  not null,
    tagline       varchar(120),
    monthly_cents bigint       not null check (monthly_cents >= 0),
    trial_days    int          not null default 0,
    features      jsonb        not null default '[]',
    sort_order    int          not null default 0,
    active        boolean      not null default true
);

create table plan_price (
    plan_code         varchar(40) not null references plan (code),
    cadence           varchar(16) not null check (cadence in ('MONTHLY', 'QUARTERLY', 'ANNUAL')),
    stripe_lookup_key varchar(80) not null unique,
    primary key (plan_code, cadence)
);

create table connect_account (
    account_id              varchar(60)  primary key,
    org_id                  varchar(40)  unique references organisation (id),
    currently_due           jsonb        not null default '[]',
    eventually_due          jsonb        not null default '[]',
    past_due                jsonb        not null default '[]',
    pending_verification    jsonb        not null default '[]',
    errors                  jsonb        not null default '[]',
    current_deadline        timestamptz,
    disabled_reason         varchar(120),
    charges_enabled         boolean      not null default false,
    payouts_enabled         boolean      not null default false,
    details_submitted       boolean      not null default false,
    individual_verification varchar(40),
    capabilities            jsonb        not null default '{}',
    persons                 jsonb        not null default '{}',
    external_account_status varchar(40),
    status                  varchar(24)  not null,
    requirements_version    bigint       not null default 0,
    payouts_paused          boolean      not null default false,
    hold_reason             varchar(24),
    listings_hidden         boolean      not null default false,
    deauthorized            boolean      not null default false,
    reminders_sent          int          not null default 0,
    last_reminder_at        timestamptz,
    updated_at              timestamptz  not null,
    version                 bigint       not null default 0
);
create index connect_account_status on connect_account (status);
create index connect_account_deadline on connect_account (current_deadline) where current_deadline is not null;

create table connect_account_history (
    id          uuid        primary key,
    account_id  varchar(60) not null references connect_account (account_id),
    source      varchar(24) not null,
    event_id    varchar(80),
    event_type  varchar(80),
    status      varchar(24) not null,
    summary     text        not null,
    snapshot    jsonb       not null,
    actor_id    varchar(80),
    occurred_at timestamptz not null
);
create index connect_account_history_acct on connect_account_history (account_id, occurred_at desc);

create table compliance_reminder (
    id             uuid        primary key,
    account_id     varchar(60) not null references connect_account (account_id),
    deadline       timestamptz,
    threshold_days int,
    manual         boolean     not null,
    actor_id       varchar(80),
    sent_at        timestamptz not null
);
-- One scheduled reminder per account, deadline and threshold (the job is idempotent across replicas).
create unique index compliance_reminder_once on compliance_reminder (account_id, deadline, threshold_days)
    where manual = false;

-- Stripe webhook inbox (event ids are strings such as evt_…).
create table stripe_webhook_event (
    event_id    varchar(80) primary key,
    endpoint    varchar(16) not null,
    type        varchar(80) not null,
    account_id  varchar(60),
    created     bigint      not null,
    outcome     varchar(60),
    received_at timestamptz not null
);

create table subscription (
    org_id                  varchar(40) primary key references organisation (id),
    plan_code               varchar(40) not null references plan (code),
    cadence                 varchar(16) not null,
    status                  varchar(24) not null,
    payment_method_kind     varchar(24),
    stripe_customer_id      varchar(60) unique,
    stripe_subscription_id  varchar(60) unique,
    stripe_item_id          varchar(60),
    trial_ends_at           timestamptz,
    current_period_start    timestamptz,
    current_period_end      timestamptz,
    cancel_at_period_end    boolean     not null default false,
    pending_plan_code       varchar(40) references plan (code),
    pending_cadence         varchar(16),
    pending_effective_at    timestamptz,
    dunning                 varchar(24) not null default 'NONE',
    failed_payment_attempts int         not null default 0,
    next_payment_attempt_at timestamptz,
    updated_at              timestamptz not null,
    version                 bigint      not null default 0
);
create index subscription_pending on subscription (pending_effective_at) where pending_plan_code is not null;

create table payment_method (
    id                       uuid        primary key,
    org_id                   varchar(40) not null references organisation (id),
    stripe_payment_method_id varchar(80) not null,
    kind                     varchar(24) not null check (kind in ('CARD', 'US_BANK_ACCOUNT')),
    brand                    varchar(24),
    last4                    char(4),
    exp_month                int,
    exp_year                 int,
    bank_name                varchar(80),
    account_type             varchar(16),
    verified                 boolean     not null default false,
    receives_payouts         boolean     not null default false,
    is_default               boolean     not null default false,
    created_at               timestamptz not null,
    unique (org_id, stripe_payment_method_id)
);
create unique index payment_method_one_default on payment_method (org_id) where is_default;

create table invoice (
    stripe_invoice_id  varchar(80) primary key,
    org_id             varchar(40) not null references organisation (id),
    number             varchar(60),
    period_start       timestamptz,
    period_end         timestamptz,
    amount_due_cents   bigint      not null,
    amount_paid_cents  bigint      not null,
    currency           char(3)     not null,
    status             varchar(24),
    billing_reason     varchar(40),
    hosted_invoice_url text,
    invoice_pdf        text,
    created_at         timestamptz not null,
    updated_at         timestamptz not null
);
create index invoice_org on invoice (org_id, created_at desc);
