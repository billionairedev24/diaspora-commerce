-- Guided-tour progress per user (Seller Center, Provider Hub and staff console tours), so a tour a person finished or
-- dismissed does not start again in another browser or on another device. One row per user and tour (a tour id is
-- namespaced by app, for example sc.orders or st.settings); content_version is the version of the tour text the person
-- last dealt with, so a new version shows a quiet "What's new" mark and never starts by itself.
-- No personal data: ids of tours and steps, a status and a time. "Not started" is the absence of a row.
create table user_tour_progress (
    user_id         uuid        not null references user_account (id) on delete cascade,
    tour_id         varchar(64) not null,
    content_version integer     not null check (content_version >= 1),
    status          varchar(16) not null check (status in ('IN_PROGRESS', 'COMPLETED', 'DISMISSED')),
    last_step       varchar(64),
    updated_at      timestamptz not null,
    primary key (user_id, tour_id)
);
