-- trust-ops: listing moderation queue, disputes / A-to-z claims, immutable audit log, seller health and
-- enforcement, support cases from Lola hand-offs. Outbox/inbox tables come from classpath:db/platform.

create sequence if not exists claim_reference_seq start with 10001;
create sequence if not exists support_case_reference_seq start with 1001;

-- ---------------------------------------------------------------- S117 listing moderation queue
create table moderation_item (
    id                uuid primary key,
    product_id        uuid         not null,
    atin              varchar(10),
    seller_org_id     varchar(80)  not null,
    owner_kind        varchar(10),
    title             varchar(200) not null,
    product_type_code varchar(40),
    status            varchar(20)  not null check (status in ('PENDING', 'ESCALATED', 'APPROVED', 'REJECTED', 'CLOSED')),
    priority          varchar(10)  not null check (priority in ('NORMAL', 'HIGH')),
    triage_status     varchar(20)  not null check (triage_status in ('COMPLETED', 'UNAVAILABLE')),
    triage            jsonb,
    enqueued_at       timestamptz  not null,
    sla_started_at    timestamptz  not null,
    due_at            timestamptz  not null,
    assignee          varchar(120),
    assigned_at       timestamptz,
    escalated         boolean      not null default false,
    escalation_reason text,
    escalated_at      timestamptz,
    decided_by        varchar(120),
    decided_at        timestamptz,
    reason_code       varchar(40),
    decision_note     text,
    version           bigint       not null default 0
);
-- One open queue item per product; a resubmission after a decision opens a new one.
create unique index moderation_item_open_product on moderation_item (product_id)
    where status in ('PENDING', 'ESCALATED');
create index moderation_item_queue on moderation_item (status, due_at);
create index moderation_item_assignee on moderation_item (assignee) where assignee is not null;

-- ---------------------------------------------------------------- S118 disputes and A-to-z claims
create table claim (
    id                  uuid primary key,
    reference           varchar(20)  not null unique,
    type                varchar(30)  not null,
    status              varchar(20)  not null check (status in ('AWAITING_SELLER', 'UNDER_REVIEW', 'ESCALATED',
                                                               'RESOLVED_REFUND', 'RESOLVED_DENIED', 'WITHDRAWN')),
    order_id            varchar(80)  not null,
    order_number        varchar(40),
    shipment_id         varchar(80),
    seller_org_id       varchar(80)  not null,
    customer_id         varchar(120) not null,
    amount_minor        bigint       not null check (amount_minor > 0),
    currency            char(3)      not null,
    description         text         not null,
    evidence            jsonb        not null default '[]'::jsonb,
    seller_response     text,
    seller_responded_at timestamptz,
    sla_started_at      timestamptz  not null,
    due_at              timestamptz  not null,
    escalated           boolean      not null default false,
    escalation_reason   text,
    outcome             varchar(10) check (outcome in ('REFUND', 'DENY')),
    refund_minor        bigint,
    refund_id           varchar(80),
    decided_by          varchar(120),
    decided_at          timestamptz,
    decision_note       text,
    opened_at           timestamptz  not null,
    updated_at          timestamptz  not null,
    version             bigint       not null default 0
);
create index claim_queue on claim (status, due_at);
create index claim_customer on claim (customer_id, opened_at desc);
create index claim_seller on claim (seller_org_id, opened_at desc);
create index claim_order on claim (order_id);
-- One open claim per order + shipment.
create unique index claim_open_per_shipment on claim (order_id, coalesce(shipment_id, ''))
    where status in ('AWAITING_SELLER', 'UNDER_REVIEW', 'ESCALATED');

create table claim_history (
    id          bigserial primary key,
    claim_id    uuid         not null references claim (id),
    at          timestamptz  not null,
    actor_id    varchar(160) not null,
    action      varchar(40)  not null,
    from_status varchar(20),
    to_status   varchar(20)  not null,
    note        text
);
create index claim_history_claim on claim_history (claim_id, id);

-- ---------------------------------------------------------------- S119 immutable audit log
create table audit_entry (
    seq             bigint       primary key check (seq > 0),
    id              uuid         not null unique,
    source_event_id uuid         unique,
    source_topic    varchar(120),
    event_type      varchar(120) not null,
    actor_id        varchar(160) not null,
    actor_type      varchar(40),
    action          varchar(120) not null,
    entity_type     varchar(60)  not null,
    entity_id       varchar(160) not null,
    org_id          varchar(80),
    summary         text,
    -- The payload is stored as text, byte for byte as hashed (jsonb would normalise it and break the chain).
    payload         text         not null,
    occurred_at     timestamptz  not null,
    recorded_at     timestamptz  not null,
    prev_hash       char(64)     not null,
    hash            char(64)     not null unique
);
create index audit_entry_actor on audit_entry (actor_id, seq);
create index audit_entry_entity on audit_entry (entity_type, entity_id, seq);
create index audit_entry_action on audit_entry (action, seq);
create index audit_entry_org on audit_entry (org_id, seq) where org_id is not null;
create index audit_entry_occurred on audit_entry (occurred_at);

-- Append-only at the database level: every UPDATE, DELETE and TRUNCATE is rejected, whoever runs it.
create function audit_entry_append_only() returns trigger
    language plpgsql as
$$
begin
    raise exception 'audit_entry is append-only: % is not allowed', tg_op
        using errcode = '42501', hint = 'Audit records cannot be edited or deleted';
end;
$$;

create trigger audit_entry_no_update_delete
    before update or delete on audit_entry
    for each row execute function audit_entry_append_only();

create trigger audit_entry_no_truncate
    before truncate on audit_entry
    for each statement execute function audit_entry_append_only();

revoke update, delete, truncate on audit_entry from public;

-- ---------------------------------------------------------------- S120 / S223 seller health and enforcement
-- One row per shipment, projected from orders events (at.order.events).
create table seller_shipment (
    shipment_id   varchar(80) primary key,
    order_id      varchar(80),
    seller_org_id varchar(80) not null,
    placed_at     timestamptz,
    promised_by   date,
    delivered_at  timestamptz,
    cancelled_at  timestamptz,
    cancelled_by  varchar(20),
    last_status   varchar(30),
    updated_at    timestamptz not null
);
create index seller_shipment_org on seller_shipment (seller_org_id, placed_at);

create table seller_health_snapshot (
    id                   uuid primary key,
    org_id               varchar(80)  not null,
    window_start         timestamptz  not null,
    window_end           timestamptz  not null,
    shipments            int          not null,
    delivered            int          not null,
    late_deliveries      int          not null,
    seller_cancellations int          not null,
    claims               int          not null,
    status               varchar(20)  not null,
    breaches             jsonb        not null default '[]'::jsonb,
    computed_at          timestamptz  not null,
    pushed_to_offers     boolean      not null default false
);
create index seller_health_snapshot_org on seller_health_snapshot (org_id, computed_at desc);

create table seller_enforcement (
    org_id             varchar(80) primary key,
    status             varchar(20) not null check (status in ('ACTIVE', 'SUSPENDED')),
    reason_code        varchar(40),
    note               text,
    actor_id           varchar(160),
    changed_at         timestamptz not null,
    payouts_held       boolean     not null default false,
    buy_box_restricted boolean     not null default false,
    version            bigint      not null default 0
);

-- ---------------------------------------------------------------- support cases (Lola hand-offs)
create table support_case (
    id             uuid primary key,
    reference      varchar(20)  not null unique,
    source         varchar(20)  not null,
    handoff_id     uuid unique,
    session_id     varchar(120),
    customer_id    varchar(120) not null,
    reason         varchar(120) not null,
    summary        text         not null,
    status         varchar(20)  not null check (status in ('OPEN', 'IN_PROGRESS', 'RESOLVED')),
    assignee       varchar(120),
    sla_started_at timestamptz  not null,
    due_at         timestamptz  not null,
    opened_at      timestamptz  not null,
    resolved_at    timestamptz,
    resolution     text,
    version        bigint       not null default 0
);
create index support_case_queue on support_case (status, due_at);
