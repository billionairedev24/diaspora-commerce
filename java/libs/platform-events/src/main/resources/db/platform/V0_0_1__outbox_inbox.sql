-- Shared by every service: add "classpath:db/platform" to spring.flyway.locations.
create table if not exists platform_outbox (
    id             uuid primary key,
    type           varchar(120) not null,
    aggregate_type varchar(60)  not null,
    aggregate_id   varchar(80)  not null,
    occurred_at    timestamptz  not null,
    payload        jsonb        not null,
    published_at   timestamptz
);
create index if not exists platform_outbox_unpublished on platform_outbox (occurred_at) where published_at is null;

create table if not exists platform_inbox (
    consumer     varchar(120) not null,
    event_id     uuid         not null,
    processed_at timestamptz  not null,
    primary key (consumer, event_id)
);
