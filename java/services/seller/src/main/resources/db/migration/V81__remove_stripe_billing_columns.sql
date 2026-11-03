-- Drop Stripe Billing specific columns and tables while preserving Stripe Connect and Stripe Payments.

-- 1. Subscription table: drop Stripe Billing subscription, item, and customer columns; add anchor_date for local rrule scheduling.
alter table subscription add column anchor_date timestamptz;
update subscription set anchor_date = coalesce(current_period_start, updated_at);

alter table subscription drop column if exists stripe_subscription_id;
alter table subscription drop column if exists stripe_item_id;
alter table subscription drop column if exists stripe_customer_id;

-- 2. Plan and Plan Version Price: drop Stripe product and price ids.
alter table plan drop column if exists stripe_product_id;

drop index if exists plan_version_price_stripe;
alter table plan_version_price drop column if exists stripe_price_id;

-- 3. Metered usage: drop metered subscription items table (metered prices on Stripe subscriptions no longer exist)
-- and drop stripe_customer_id from usage_report.
drop table if exists metered_subscription_item;
alter table usage_report drop column if exists stripe_customer_id;

-- 4. Billing grant / reason adjustments: drop Stripe billing specific fields
alter table billing_grant drop column if exists stripe_subscription_id;
alter table billing_grant drop column if exists stripe_customer_id;
alter table billing_grant drop column if exists stripe_coupon_id;
