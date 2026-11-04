-- The payout bank account Stripe holds for a seller's connected account (Stripe-collected: Stripe's embedded
-- onboarding and account management collect and change it). Bank name, last 4, Stripe's status and id only; never
-- the numbers. Mirrored from the account and account.external_account.* webhooks; external_account_status keeps the
-- status alone, as before. V60 leaves room for the plan catalogue migrations in progress (V53+).
alter table connect_account add column payout_bank jsonb;
