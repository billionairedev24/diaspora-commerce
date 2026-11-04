-- S263: location-targeted advertorials for the visitors of the area a pre-order window or a delivery run serves.
-- They are drafted from the window or run (generated) or written by staff, may have A/B variants, end when their
-- source closes or is cancelled, and report views, clicks and (attributed) orders.

create table local_advertorial (
    id          uuid         primary key,
    source_kind varchar(8)   not null check (source_kind in ('RUN', 'WINDOW')),
    source_id   uuid         not null,
    location_id uuid         references location (id),
    variant     varchar(8)   not null,
    weight      integer      not null check (weight between 1 and 100),
    priority    integer      not null check (priority between 0 and 1000),
    generated   boolean      not null,
    headline    varchar(140) not null,
    body        varchar(400) not null,
    cta_label   varchar(40)  not null,
    starts_at   timestamptz,
    ends_at     timestamptz,
    status      varchar(8)   not null check (status in ('DRAFT', 'LIVE', 'PAUSED', 'ENDED')),
    ended       varchar(16)  check (ended in ('SOURCE_CLOSED', 'CANCELLED', 'SCHEDULE', 'STAFF')),
    created_by  varchar(120) not null,
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null,
    version     bigint       not null,
    check ((status = 'ENDED') = (ended is not null)),
    unique (source_kind, source_id, variant)
);
create index local_advertorial_live on local_advertorial (source_id) where status = 'LIVE';
create index local_advertorial_source on local_advertorial (source_kind, source_id);

-- Views and clicks per advertorial and UTC day.
create table local_advertorial_stat (
    advertorial_id uuid    not null references local_advertorial (id) on delete cascade,
    day            date    not null,
    views          bigint  not null default 0,
    clicks         bigint  not null default 0,
    primary key (advertorial_id, day)
);
