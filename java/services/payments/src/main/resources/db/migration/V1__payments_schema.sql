-- payments-service schema: payments, captures, refunds, the immutable double-entry ledger, fee schedules,
-- settlement holds and transfers, payouts, disputes, the Stripe webhook inbox and reconciliation runs.

-- ---------------------------------------------------------------- fee schedules (versioned, immutable)
create table fee_schedule (
    id                           uuid primary key,
    version                      integer      not null unique check (version > 0),
    effective_from               timestamptz  not null,
    currency                     char(3)      not null,
    default_commission_bps       integer      not null check (default_commission_bps between 0 and 10000),
    card_pct_bps                 integer      not null check (card_pct_bps between 0 and 10000),
    card_fixed_minor             bigint       not null check (card_fixed_minor >= 0),
    fba_per_unit_minor           bigint       not null check (fba_per_unit_minor >= 0),
    reverse_commission_on_refund boolean      not null,
    note                         text,
    created_by                   varchar(120) not null,
    created_at                   timestamptz  not null
);
create index fee_schedule_effective on fee_schedule (effective_from desc, version desc);

create table fee_schedule_rate (
    schedule_id    uuid        not null references fee_schedule (id),
    category       varchar(80) not null,
    commission_bps integer     not null check (commission_bps between 0 and 10000),
    primary key (schedule_id, category)
);

create table fee_schedule_override (
    schedule_id    uuid        not null references fee_schedule (id),
    org_id         varchar(80) not null,
    category       varchar(80) not null,
    commission_bps integer     not null check (commission_bps between 0 and 10000),
    primary key (schedule_id, org_id, category)
);

-- ---------------------------------------------------------------- payments
create table payment (
    id                       uuid primary key,
    order_id                 varchar(80)  not null unique,
    customer_id              varchar(120),
    currency                 char(3)      not null,
    amount_minor             bigint       not null check (amount_minor > 0),
    status                   varchar(40)  not null,
    stripe_payment_intent_id varchar(80) unique,
    client_secret            varchar(255),
    fee_schedule_id          uuid         not null references fee_schedule (id),
    placed_at                timestamptz  not null,
    captured_minor           bigint       not null default 0 check (captured_minor >= 0),
    refunded_minor           bigint       not null default 0 check (refunded_minor >= 0),
    authorized_at            timestamptz,
    failure_message          text,
    metadata                 jsonb        not null default '{}'::jsonb,
    created_at               timestamptz  not null,
    updated_at               timestamptz  not null,
    version                  bigint       not null default 0,
    check (captured_minor <= amount_minor),
    check (refunded_minor <= captured_minor)
);

create table payment_line (
    payment_id                uuid         not null references payment (id),
    line_no                   integer      not null,
    line_id                   varchar(80)  not null,
    seller_org                varchar(80)  not null,
    category                  varchar(80)  not null,
    fulfilment                varchar(10)  not null check (fulfilment in ('FBA', 'SELF')),
    quantity                  integer      not null check (quantity > 0),
    items_minor               bigint       not null check (items_minor >= 0),
    shipping_minor            bigint       not null check (shipping_minor >= 0),
    tax_minor                 bigint       not null check (tax_minor >= 0),
    tax_jurisdiction          varchar(40)  not null,
    capture_id                uuid,
    commission_minor          bigint       not null default 0,
    fba_fee_minor             bigint       not null default 0,
    shipping_to_platform      boolean      not null,
    refunded_items_minor      bigint       not null default 0,
    refunded_shipping_minor   bigint       not null default 0,
    refunded_tax_minor        bigint       not null default 0,
    commission_reversed_minor bigint       not null default 0,
    primary key (payment_id, line_id),
    check (refunded_items_minor between 0 and items_minor),
    check (refunded_shipping_minor between 0 and shipping_minor),
    check (refunded_tax_minor between 0 and tax_minor)
);
create index payment_line_seller on payment_line (seller_org);

create table payment_capture (
    id               uuid primary key,
    payment_id       uuid        not null references payment (id),
    shipment_id      varchar(80) not null,
    amount_minor     bigint      not null check (amount_minor > 0),
    stripe_fee_minor bigint      not null check (stripe_fee_minor >= 0),
    fee_estimated    boolean     not null,
    stripe_charge_id varchar(80),
    final_capture    boolean     not null,
    line_ids         jsonb       not null,
    journal_id       uuid        not null,
    captured_at      timestamptz not null,
    unique (payment_id, shipment_id)
);

create table payment_refund (
    id               uuid primary key,
    payment_id       uuid        not null references payment (id),
    order_id         varchar(80) not null,
    stripe_refund_id varchar(80) not null unique,
    currency         char(3)     not null,
    amount_minor     bigint      not null check (amount_minor > 0),
    status           varchar(20) not null,
    reason           text,
    lines            jsonb       not null,
    created_at       timestamptz not null,
    settled_at       timestamptz
);
create index payment_refund_payment on payment_refund (payment_id, created_at);

-- ---------------------------------------------------------------- double-entry ledger (append-only)
create table journal_entry (
    id          uuid primary key,
    journal_key varchar(200) not null unique,
    type        varchar(30)  not null,
    order_id    varchar(80),
    payment_id  varchar(80),
    stripe_ref  varchar(120),
    description text,
    occurred_at timestamptz  not null,
    posted_at   timestamptz  not null,
    posted_by   varchar(120) not null
);
create index journal_entry_order on journal_entry (order_id);
create index journal_entry_posted on journal_entry (posted_at);

create table journal_line (
    journal_id   uuid        not null references journal_entry (id),
    line_no      integer     not null,
    account_code varchar(10) not null,
    account_key  varchar(80) not null default '',
    side         char(1)     not null check (side in ('D', 'C')),
    amount_minor bigint      not null check (amount_minor > 0),
    currency     char(3)     not null,
    org_id       varchar(80),
    order_id     varchar(80),
    primary key (journal_id, line_no)
);
create index journal_line_account on journal_line (account_code, account_key, currency);
create index journal_line_org on journal_line (org_id);
create index journal_line_order on journal_line (order_id, account_code);

create function ledger_reject_change() returns trigger language plpgsql as $$
begin
    raise exception 'the ledger is append-only: % on % is not allowed', tg_op, tg_table_name
        using errcode = '42501';
end $$;

create trigger journal_entry_immutable before update or delete on journal_entry
    for each row execute function ledger_reject_change();
create trigger journal_entry_no_truncate before truncate on journal_entry
    for each statement execute function ledger_reject_change();
create trigger journal_line_immutable before update or delete on journal_line
    for each row execute function ledger_reject_change();
create trigger journal_line_no_truncate before truncate on journal_line
    for each statement execute function ledger_reject_change();

-- Every journal balances per currency and has at least two lines; checked at commit (deferred) so lines can be
-- inserted one by one inside the posting transaction.
create function ledger_check_journal(jid uuid) returns void language plpgsql as $$
declare
    bad  record;
    n    integer;
begin
    select count(*) into n from journal_line where journal_id = jid;
    if n < 2 then
        raise exception 'journal % has % line(s); at least two are required', jid, n using errcode = '23514';
    end if;
    select currency, sum(case side when 'D' then amount_minor else -amount_minor end) as diff into bad
    from journal_line where journal_id = jid
    group by currency
    having sum(case side when 'D' then amount_minor else -amount_minor end) <> 0
    limit 1;
    if found then
        raise exception 'journal % does not balance in % (debits - credits = %)', jid, bad.currency, bad.diff
            using errcode = '23514';
    end if;
end $$;

create function ledger_entry_balanced() returns trigger language plpgsql as $$
begin
    perform ledger_check_journal(new.id);
    return null;
end $$;

create function ledger_line_balanced() returns trigger language plpgsql as $$
begin
    perform ledger_check_journal(new.journal_id);
    return null;
end $$;

create constraint trigger journal_entry_balanced after insert on journal_entry
    deferrable initially deferred for each row execute function ledger_entry_balanced();
create constraint trigger journal_line_balanced after insert on journal_line
    deferrable initially deferred for each row execute function ledger_line_balanced();

create trigger fee_schedule_immutable before update or delete on fee_schedule
    for each row execute function ledger_reject_change();
create trigger fee_schedule_rate_immutable before update or delete on fee_schedule_rate
    for each row execute function ledger_reject_change();
create trigger fee_schedule_override_immutable before update or delete on fee_schedule_override
    for each row execute function ledger_reject_change();

-- ---------------------------------------------------------------- Connect accounts, settlement hold, transfers
create table connected_account (
    org_id            varchar(80) primary key,
    stripe_account_id varchar(80) not null unique,
    updated_at        timestamptz not null
);

create table settlement (
    order_id      varchar(80) primary key,
    kind          varchar(20) not null,
    delivered_at  timestamptz not null,
    release_at    timestamptz not null,
    status        varchar(20) not null,
    next_check_at timestamptz not null,
    updated_at    timestamptz not null
);
create index settlement_due on settlement (next_check_at) where status = 'OPEN';

create table settlement_transfer (
    id                 uuid primary key,
    order_id           varchar(80) not null,
    org_id             varchar(80) not null,
    stripe_transfer_id varchar(80) not null unique,
    currency           char(3)     not null,
    amount_minor       bigint      not null check (amount_minor > 0),
    reversed_minor     bigint      not null default 0,
    created_at         timestamptz not null,
    check (reversed_minor between 0 and amount_minor)
);
create index settlement_transfer_order on settlement_transfer (order_id, org_id);
create index settlement_transfer_org on settlement_transfer (org_id, currency);

create table transfer_reversal (
    id                 uuid primary key,
    transfer_id        uuid         not null references settlement_transfer (id),
    stripe_reversal_id varchar(120) not null unique,
    amount_minor       bigint       not null check (amount_minor > 0),
    refund_id          uuid,
    created_at         timestamptz  not null
);

-- ---------------------------------------------------------------- payouts on request
create table payout_request (
    id               uuid primary key,
    org_id           varchar(80)  not null,
    currency         char(3)      not null,
    amount_minor     bigint       not null check (amount_minor > 0),
    status           varchar(20)  not null,
    requested_by     varchar(120) not null,
    requested_at     timestamptz  not null,
    request_day      date         not null,
    decided_by       varchar(120),
    decided_at       timestamptz,
    hold_reason      text,
    stripe_payout_id varchar(80) unique,
    failure_message  text,
    version          bigint       not null default 0
);
create index payout_request_queue on payout_request (status, requested_at);
-- Backstop of the "one request per day" rule (failed payouts do not count).
create unique index payout_request_one_per_day on payout_request (org_id, request_day) where status <> 'FAILED';

-- ---------------------------------------------------------------- chargebacks
create table dispute (
    id          varchar(80) primary key,
    payment_id  uuid        not null references payment (id),
    order_id    varchar(80) not null,
    charge_id   varchar(80),
    currency    char(3)     not null,
    amount_minor bigint     not null,
    fee_minor   bigint      not null default 0,
    status      varchar(20) not null,
    reason      varchar(80),
    allocations jsonb       not null,
    opened_at   timestamptz not null,
    closed_at   timestamptz
);

-- ---------------------------------------------------------------- Stripe webhook inbox
create table stripe_event (
    id                varchar(80) primary key,
    type              varchar(120) not null,
    account           varchar(80),
    livemode          boolean      not null,
    stripe_created_at timestamptz  not null,
    payload           jsonb        not null,
    received_at       timestamptz  not null,
    status            varchar(20)  not null,
    attempts          integer      not null default 0,
    next_attempt_at   timestamptz  not null,
    last_error        text,
    processed_at      timestamptz
);
create index stripe_event_due on stripe_event (next_attempt_at, stripe_created_at) where status = 'RECEIVED';

-- ---------------------------------------------------------------- idempotency & reconciliation
create table idempotency_record (
    scope       varchar(60)  not null,
    idem_key    varchar(200) not null,
    fingerprint varchar(64)  not null,
    response    jsonb,
    created_at  timestamptz  not null,
    primary key (scope, idem_key)
);

create table reconciliation_run (
    id               uuid primary key,
    window_start     timestamptz not null,
    window_end       timestamptz not null,
    currency         char(3)     not null,
    ledger_net_minor bigint      not null,
    stripe_net_minor bigint      not null,
    drift_minor      bigint      not null,
    drifts           jsonb       not null,
    created_at       timestamptz not null
);
create index reconciliation_run_recent on reconciliation_run (created_at desc);
