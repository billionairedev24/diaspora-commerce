-- S243: customer reviews of bookings (bookings-service booking.review.submitted) join the moderation queue as
-- SERVICE_REVIEW items. A review item has no product: it is keyed by the booking id (= review id) and seller_org_id
-- holds the reviewed provider organisation; title holds a one-line summary of the review.
alter table moderation_item alter column product_id drop not null;

alter table moderation_item drop constraint moderation_item_item_type_check;
alter table moderation_item add constraint moderation_item_item_type_check
    check (item_type in ('NEW_LISTING', 'LIVE_LISTING', 'SERVICE_REVIEW'));

alter table moderation_item
    add column booking_id        uuid,
    add column service_id        uuid,
    add column service_name      varchar(200),
    add column customer_id       varchar(160),
    add column reviewer_name     varchar(120),
    add column rating            smallint check (rating between 1 and 5),
    add column review_text       text,
    add column review_created_at timestamptz;

-- Listings carry a product, reviews a booking and a rating.
alter table moderation_item add constraint moderation_item_subject_check check (
    (item_type = 'SERVICE_REVIEW' and booking_id is not null and rating is not null and product_id is null)
    or (item_type <> 'SERVICE_REVIEW' and product_id is not null and booking_id is null));

-- One open queue item per review (as per product for listings).
create unique index moderation_item_open_review on moderation_item (booking_id)
    where status in ('PENDING', 'ESCALATED') and booking_id is not null;
