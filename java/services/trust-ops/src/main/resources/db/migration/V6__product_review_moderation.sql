-- S324: verified-purchase product reviews (catalog-service catalog.review.submitted when a review fails the automatic
-- checks, catalog.review.reported when someone reports a published one) join the moderation queue as PRODUCT_REVIEW
-- items. Like service reviews they are keyed by the review id (stored in booking_id, so one open item per review)
-- and carry the reviewer, rating and text; the product id lives in service_id, the ATIN in atin, the product title
-- in service_name. Reports on a published review use report_count and report_reason.
alter table moderation_item drop constraint moderation_item_item_type_check;
alter table moderation_item add constraint moderation_item_item_type_check
    check (item_type in ('NEW_LISTING', 'LIVE_LISTING', 'SERVICE_REVIEW', 'PRODUCT_REVIEW'));

alter table moderation_item
    add column review_title  varchar(200),
    add column review_photos text,
    add column review_flags  varchar(120);

alter table moderation_item drop constraint moderation_item_subject_check;
alter table moderation_item add constraint moderation_item_subject_check check (
    (item_type in ('SERVICE_REVIEW', 'PRODUCT_REVIEW') and booking_id is not null and rating is not null
        and product_id is null)
    or (item_type in ('NEW_LISTING', 'LIVE_LISTING') and product_id is not null and booking_id is null));
