-- carrier-gateway schema (database `carriers`). Idempotent: applied on every start.

create table if not exists shipment_label (
    id                  uuid primary key,
    idempotency_key     text        not null unique,
    request_hash        text        not null,
    reference           text        not null default '',
    origin_node         text        not null default '',
    carrier             text        not null,
    service             text        not null,
    tracking_number     text,
    carrier_shipment_id text,
    cost_minor          bigint      not null default 0,
    currency            text        not null default 'USD',
    label_format        text        not null default 'PDF',
    label_key           text,
    status              text        not null check (status in ('purchasing', 'purchased', 'voided')),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    voided_at           timestamptz
);
create unique index if not exists shipment_label_tracking_uq
    on shipment_label (carrier, tracking_number) where tracking_number is not null;

-- Every tracking number we follow: labels we bought and numbers registered for seller-bought labels.
create table if not exists tracked_shipment (
    carrier         text        not null,
    tracking_number text        not null,
    label_id        uuid references shipment_label (id),
    reference       text        not null default '',
    state           text        not null default '',
    last_event_at   timestamptz,
    last_sequence   int         not null default 0,
    active          boolean     not null default true,
    next_poll_at    timestamptz not null default now(),
    poll_failures   int         not null default 0,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    primary key (carrier, tracking_number)
);
create index if not exists tracked_shipment_due_idx on tracked_shipment (carrier, next_poll_at) where active;
create index if not exists tracked_shipment_label_idx on tracked_shipment (label_id);

-- Scan history, de-duplicated by (carrier, event id).
create table if not exists tracking_event (
    carrier         text        not null,
    event_id        text        not null,
    tracking_number text        not null,
    code            text        not null default '',
    description     text        not null default '',
    state           text        not null default '',
    occurred_at     timestamptz not null,
    location        text        not null default '',
    sequence        int         not null default 0,
    received_at     timestamptz not null default now(),
    primary key (carrier, event_id)
);
create index if not exists tracking_event_number_idx on tracking_event (carrier, tracking_number, occurred_at, sequence);

-- Transactional outbox relayed to Kafka (at.carrier-shipment.events) by the dispatcher.
create table if not exists outbox (
    seq             bigserial primary key,
    id              uuid        not null unique,
    aggregate       text        not null,
    aggregate_id    text        not null,
    event_type      text        not null,
    payload         bytea       not null,
    occurred_at     timestamptz not null,
    created_at      timestamptz not null default now(),
    published_at    timestamptz,
    attempts        int         not null default 0,
    next_attempt_at timestamptz not null default now(),
    last_error      text
);
create index if not exists outbox_pending_idx on outbox (next_attempt_at, seq) where published_at is null;

create table if not exists carrier_pickup (
    id           uuid primary key,
    carrier      text        not null,
    confirmation text        not null,
    pickup_date  date        not null,
    origin_node  text        not null default '',
    request      jsonb       not null,
    created_at   timestamptz not null default now()
);

create table if not exists pickup_location (
    id          uuid primary key,
    kind        text        not null check (kind in ('PLATFORM_COUNTER', 'LOCKER', 'SELLER')),
    owner_org   text        not null,
    name        text        not null,
    address     jsonb       not null,
    postal_code text        not null,
    timezone    text        not null,
    hours       jsonb       not null,
    ready_hours int         not null default 0,
    active      boolean     not null default true,
    updated_at  timestamptz not null default now()
);
create index if not exists pickup_location_owner_idx on pickup_location (owner_org);
create index if not exists pickup_location_postal_idx on pickup_location (postal_code text_pattern_ops);
