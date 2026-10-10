-- Bookings: provider setups, team members and their hours, services & prices, bookings with deposits.
-- btree_gist lets the exclusion constraint combine "same team member" (=) with "overlapping time" (&&).
create extension if not exists btree_gist;

create sequence if not exists booking_reference_seq start with 24000;

create table provider (
    org_id       varchar(80)  primary key,
    display_name varchar(120) not null,
    time_zone    varchar(60)  not null,
    currency     char(3)      not null,
    location     varchar(200),
    rules        jsonb        not null,
    policy       jsonb        not null,
    strikes      int          not null default 0,
    created_at   timestamptz  not null,
    updated_at   timestamptz  not null,
    version      bigint       not null default 0
);

create table team_member (
    id           uuid primary key,
    org_id       varchar(80)  not null references provider (org_id),
    user_id      varchar(120),
    display_name varchar(120) not null,
    role         varchar(20)  not null check (role in ('OWNER', 'MANAGER', 'PRACTITIONER', 'FINANCE')),
    bookable     boolean      not null,
    active       boolean      not null default true,
    hours        jsonb        not null,
    created_at   timestamptz  not null,
    updated_at   timestamptz  not null,
    version      bigint       not null default 0,
    unique (org_id, user_id)
);
create index team_member_org on team_member (org_id);

-- Time off (closed) or special hours for one date.
create table day_override (
    member_id uuid    not null references team_member (id) on delete cascade,
    day       date    not null,
    closed    boolean not null,
    ranges    jsonb   not null,
    note      varchar(200),
    primary key (member_id, day)
);

create table offering (
    id               uuid primary key,
    org_id           varchar(80)  not null references provider (org_id),
    name             varchar(120) not null,
    description      text         not null default '',
    category         varchar(60)  not null,
    duration_minutes int          not null check (duration_minutes > 0),
    price_minor      bigint       not null check (price_minor > 0),
    deposit_minor    bigint       not null check (deposit_minor >= 0 and deposit_minor <= price_minor),
    currency         char(3)      not null,
    modes            jsonb        not null,
    add_ons          jsonb        not null,
    active           boolean      not null default true,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    version          bigint       not null default 0
);
create index offering_org on offering (org_id);
create index offering_active on offering (active, category);

create table offering_member (
    offering_id uuid not null references offering (id) on delete cascade,
    member_id   uuid not null references team_member (id) on delete cascade,
    primary key (offering_id, member_id)
);

create table booking (
    id                     uuid primary key,
    reference              varchar(20)  not null unique,
    org_id                 varchar(80)  not null references provider (org_id),
    member_id              uuid         not null references team_member (id),
    customer_id            varchar(120) not null,
    customer               jsonb        not null,
    service                jsonb        not null,
    location               varchar(300),
    notes                  text,
    channel                varchar(10)  not null,
    start_at               timestamptz  not null,
    end_at                 timestamptz  not null,
    buffer_minutes         int          not null,
    proposed_start_at      timestamptz,
    previous_status        varchar(30),
    status                 varchar(30)  not null,
    policy                 jsonb        not null,
    instant                boolean      not null,
    reschedules            int          not null default 0,
    payment_id             uuid,
    payment_state          varchar(20)  not null,
    client_secret          text,
    payment_failure        text,
    hold_expires_at        timestamptz,
    request_expires_at     timestamptz,
    authorized_minor       bigint       not null default 0,
    deposit_captured_minor bigint       not null default 0,
    balance_captured_minor bigint       not null default 0,
    refunded_minor         bigint       not null default 0,
    released_minor         bigint       not null default 0,
    provider_fee_minor     bigint       not null default 0,
    reason                 text,
    cancelled_by           varchar(10),
    created_at             timestamptz  not null,
    updated_at             timestamptz  not null,
    version                bigint       not null default 0,
    check (end_at > start_at),
    check (refunded_minor <= deposit_captured_minor + balance_captured_minor)
);
create index booking_org_start on booking (org_id, start_at);
create index booking_member_start on booking (member_id, start_at);
create index booking_customer on booking (customer_id, start_at desc);
create index booking_hold_due on booking (hold_expires_at) where status = 'PENDING_PAYMENT';
create index booking_request_due on booking (request_expires_at) where status = 'REQUESTED';
create index booking_confirmed_end on booking (end_at) where status = 'CONFIRMED';

-- Time a booking blocks in its team member's calendar: the appointment (PRIMARY) and, while a new time is being
-- decided, the proposed time (PROPOSED), each including the buffer. No two bookings of one member may overlap:
-- this constraint is what makes double booking impossible, whatever the application does.
create table booking_time_block (
    booking_id uuid        not null references booking (id) on delete cascade,
    kind       varchar(10) not null check (kind in ('PRIMARY', 'PROPOSED')),
    member_id  uuid        not null,
    period     tstzrange   not null,
    primary key (booking_id, kind),
    constraint booking_time_block_no_overlap
        exclude using gist (member_id with =, period with &&, booking_id with <>)
);

create table booking_activity (
    id           bigserial primary key,
    booking_id   uuid         not null references booking (id) on delete cascade,
    at           timestamptz  not null,
    by_party     varchar(10)  not null,
    action       text         not null,
    money_kind   varchar(20),
    amount_minor bigint       not null default 0
);
create index booking_activity_booking on booking_activity (booking_id, id);

create table idempotency_record (
    scope       varchar(40)  not null,
    idem_key    varchar(200) not null,
    fingerprint varchar(64)  not null,
    booking_id  uuid,
    created_at  timestamptz  not null,
    primary key (scope, idem_key)
);
