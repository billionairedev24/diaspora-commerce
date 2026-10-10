-- S232: reviews are moderated before they are public, providers reply once, public lists per provider and service.
alter table booking_review
    add column service_name      varchar(200),
    add column reviewer_name     varchar(120) not null default 'Verified customer',
    add column status            varchar(10)  not null default 'PENDING',
    add column moderated_by      varchar(120),
    add column moderated_at      timestamptz,
    add column moderation_reason varchar(30),
    add column moderation_note   text,
    add column reply_text        text,
    add column reply_by          varchar(120),
    add column reply_at          timestamptz,
    add column version           bigint       not null default 0,
    add constraint booking_review_status check (status in ('PENDING', 'APPROVED', 'REJECTED'));

-- Reviews written before moderation existed were already public (S221): keep them published.
update booking_review set status = 'APPROVED', moderated_by = 'migration:V4', moderated_at = created_at;

-- Public lists (newest first) and ratings count approved reviews only; the moderation queue reads pending ones.
create index booking_review_public_service on booking_review (service_id, created_at desc) where status = 'APPROVED';
create index booking_review_public_org on booking_review (org_id, created_at desc) where status = 'APPROVED';
create index booking_review_org on booking_review (org_id, created_at desc);
create index booking_review_queue on booking_review (status, created_at);
