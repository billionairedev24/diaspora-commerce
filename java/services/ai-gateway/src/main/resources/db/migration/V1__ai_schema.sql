-- ai-gateway: usage ledger (budgets), Lola conversation memory (redacted) and support hand-offs.
-- Outbox/inbox tables come from classpath:db/platform.

create table ai_usage (
    id                 uuid         primary key,
    usage_day          date         not null,
    occurred_at        timestamptz  not null,
    subject            varchar(200) not null,
    feature            varchar(40)  not null,
    task               varchar(40)  not null,
    model              varchar(160) not null,
    prompt_tokens      bigint       not null check (prompt_tokens >= 0),
    completion_tokens  bigint       not null check (completion_tokens >= 0),
    cost_micros        bigint       not null check (cost_micros >= 0),
    degraded           boolean      not null default false,
    fallback           boolean      not null default false
);
create index ai_usage_subject_day on ai_usage (subject, feature, usage_day);
create index ai_usage_feature_day on ai_usage (feature, usage_day);

-- Daily roll-up kept in step with ai_usage so budget checks read one row instead of summing the ledger.
create table ai_usage_daily (
    usage_day    date         not null,
    scope        varchar(10)  not null check (scope in ('USER', 'FEATURE')),
    scope_key    varchar(260) not null,
    tokens       bigint       not null default 0,
    cost_micros  bigint       not null default 0,
    version      bigint       not null default 0,
    primary key (usage_day, scope, scope_key)
);

create table lola_message (
    id          bigserial    primary key,
    session_id  varchar(64)  not null,
    subject     varchar(200) not null,
    role        varchar(16)  not null check (role in ('user', 'assistant')),
    content     text         not null,
    products    jsonb        not null default '[]'::jsonb,
    created_at  timestamptz  not null
);
create index lola_message_session on lola_message (session_id, subject, id);
create index lola_message_created on lola_message (created_at);

create table ai_handoff (
    id          uuid         primary key,
    session_id  varchar(64)  not null,
    subject     varchar(200) not null,
    reason      varchar(120) not null,
    summary     text         not null,
    status      varchar(20)  not null default 'REQUESTED',
    created_at  timestamptz  not null
);
create index ai_handoff_session on ai_handoff (session_id);
