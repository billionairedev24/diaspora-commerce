-- Ask for a delivery quote (docs/engineering/listing-rules.md D20, D21; docs/runbooks/delivery-quotes.md).
-- An address outside the delivery zones gets a staff case instead of a dead end: the shopper asks, customer care
-- agrees an amount (reason required), the shopper checks out with it (single use, 48 h) and the order carries it as
-- the package's shipping. The case workflow (who has it, SLA, escalation) copies the application review workflow's.

create table delivery_quote (
    id                  uuid primary key,
    reference           text        not null unique,
    customer_id         text        not null,
    status              text        not null
        check (status in ('REQUESTED', 'IN_PROGRESS', 'READY', 'DECLINED', 'EXPIRED', 'USED')),
    -- The cart and address the quote is for. cart_hash covers the lines (variant, seller, fulfilment, quantity);
    -- ship_to_key the address a driver goes to. A quote is only valid for exactly these two.
    cart_hash           text        not null,
    ship_to_key         text        not null,
    dedupe_key          text        not null,
    currency            text        not null,
    packages            jsonb       not null,
    lines               jsonb       not null,
    ship_to             jsonb       not null,
    contact_preference  text        not null check (contact_preference in ('EMAIL', 'PHONE', 'SMS')),
    note                text,
    -- The answer.
    amount_minor        bigint check (amount_minor is null or amount_minor >= 0),
    agreed_reason       text,
    agreed_by_id        text,
    agreed_by_name      text,
    agreed_at           timestamptz,
    expires_at          timestamptz,
    declined_reason     text,
    declined_by_id      text,
    declined_by_name    text,
    declined_at         timestamptz,
    used_order_id       uuid,
    used_at             timestamptz,
    -- Who has it (the application_case shape): assignee or nobody, the SLA clock, the escalation.
    assignee_id         text,
    assignee_name       text,
    assigned_by_id      text,
    assigned_by_name    text,
    assigned_at         timestamptz,
    stage_entered_at    timestamptz not null,
    escalation_level    int         not null default 0 check (escalation_level between 0 and 2),
    escalated_at        timestamptz,
    escalation_reason   text,
    escalation_target_ids   text[]  not null default '{}',
    escalation_target_names text[]  not null default '{}',
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    version             bigint      not null default 0,
    check (status not in ('READY', 'USED') or amount_minor is not null),
    check (status <> 'DECLINED' or declined_reason is not null),
    check (status <> 'USED' or used_order_id is not null)
);

-- One live request per shopper, cart and address: a repeat (double click, retry, another tab) gets the same case.
create unique index delivery_quote_one_live on delivery_quote (customer_id, dedupe_key)
    where status in ('REQUESTED', 'IN_PROGRESS', 'READY');
create index delivery_quote_customer on delivery_quote (customer_id, created_at desc);
create index delivery_quote_queue on delivery_quote (status, stage_entered_at);
create index delivery_quote_assignee on delivery_quote (assignee_id) where assignee_id is not null;
create index delivery_quote_expiry on delivery_quote (expires_at) where status = 'READY';
-- A quote is consumed by exactly one order.
create unique index delivery_quote_used_order on delivery_quote (used_order_id) where used_order_id is not null;

-- The case timeline and the audit trail of every state change, with names (never ids on screen).
create table delivery_quote_event (
    id          uuid primary key,
    quote_id    uuid        not null references delivery_quote (id),
    action      text        not null,
    from_status text,
    to_status   text        not null,
    actor_id    text        not null,
    actor_name  text,
    note        text,
    amount_minor bigint,
    occurred_at timestamptz not null
);
create index delivery_quote_event_quote on delivery_quote_event (quote_id, occurred_at);

-- One row: SLA, escalation chains, assignment mode, amount bounds and validity.
create table delivery_quote_settings (
    id                      int primary key check (id = 1),
    sla_business_days       int     not null default 1 check (sla_business_days between 1 and 10),
    due_soon_percent        int     not null default 75 check (due_soon_percent between 1 and 99),
    unassigned_after_hours  int     not null default 4 check (unassigned_after_hours between 1 and 720),
    level2_after_hours      int     not null default 24 check (level2_after_hours between 1 and 720),
    auto_assign             text    not null default 'NONE' check (auto_assign in ('NONE', 'ROUND_ROBIN', 'LEAST_LOADED')),
    round_robin_cursor      text,
    level1_role             text,
    level1_user_ids         text[]  not null default '{}',
    level2_role             text,
    level2_user_ids         text[]  not null default '{}',
    min_amount_minor        bigint  not null default 500 check (min_amount_minor >= 0),
    max_amount_minor        bigint  not null default 50000 check (max_amount_minor >= min_amount_minor),
    validity_hours          int     not null default 48 check (validity_hours between 1 and 720),
    requests_per_hour       int     not null default 5 check (requests_per_hour between 1 and 100),
    duplicate_window_minutes int    not null default 15 check (duplicate_window_minutes between 1 and 1440),
    updated_by_id           text,
    updated_by_name         text,
    updated_at              timestamptz not null default now()
);
insert into delivery_quote_settings (id) values (1);

create table delivery_quote_settings_audit (
    id          uuid primary key,
    actor_id    text        not null,
    actor_name  text,
    reason      text        not null,
    before_json jsonb       not null,
    after_json  jsonb       not null,
    changed_at  timestamptz not null
);
