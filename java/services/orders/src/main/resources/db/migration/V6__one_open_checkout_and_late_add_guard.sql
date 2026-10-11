-- Double-charge guards (docs/engineering/payments-idempotency.md).
-- 1. One open storefront checkout per customer. A checkout holds the slot from its claim until it is placed or fails
--    (open_checkout = true); a second checkout under another Idempotency-Key either resumes the open one (same cart)
--    or supersedes it (its PaymentIntent cancelled, its stock released) before it may claim the slot. Late pre-order
--    adds by customer service never hold it. Existing rows start without the slot, so the index builds on any data.
alter table checkout_request add column open_checkout boolean not null default false;
create unique index checkout_request_one_open_checkout on checkout_request (customer_id) where open_checkout;

-- 2. Late pre-order adds: sha-256 of (window, item set). A second live late add of the same items for the same
--    customer and window within a short period is refused even under another Idempotency-Key.
alter table checkout_request add column late_add_guard varchar(64);
create index checkout_request_late_add_guard on checkout_request (customer_id, late_add_guard, created_at)
    where late_add_guard is not null;
