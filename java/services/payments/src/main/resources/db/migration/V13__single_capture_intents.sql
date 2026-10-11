-- Stripe multicapture needs flexible payments on the account; without it each order intent is captured once.
-- Payments created before this column requested multicapture (if_available), so existing rows keep true.
alter table payment add column if not exists multicapture boolean not null default true;
