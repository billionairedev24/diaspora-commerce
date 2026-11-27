-- Plan fees by card or US bank debit (ACH), settled by the platform webhook: payments hands every plan-fee Stripe event to
-- seller (payments.plan_fee.stripe_event); seller has no Stripe webhook of its own for plan fees any more.
--
-- 1. A charge Stripe needs the seller to confirm (3-D Secure off-session) is ACTION_REQUIRED until they confirm it in
--    Plan & billing (or the next attempt replaces it).
alter table invoice_payment drop constraint if exists invoice_payment_status_check;
alter table invoice_payment alter column status type varchar(16);
alter table invoice_payment add constraint invoice_payment_status_check
    check (status in ('APPLIED', 'PENDING', 'FAILED', 'REVERSED', 'ACTION_REQUIRED'));
create index invoice_payment_by_reference on invoice_payment (reference) where source = 'CARD';

-- 2. The online ACH mandate the seller accepted when saving a US bank account (SetupIntent.mandate).
alter table payment_method add column stripe_mandate_id varchar(80);

-- 3. Refunds of plan fees: by staff (full or partial, with a reason, Stripe idempotency key) or made in the Stripe
--    dashboard (reconciled from charge.refunded). A refund is a credit on the invoice; it never reopens it.
create table invoice_refund (
    id                uuid         primary key,
    invoice_id        varchar(80)  not null references invoice (stripe_invoice_id),
    org_id            varchar(40)  not null references organisation (id),
    payment_intent_id varchar(80)  not null,
    stripe_refund_id  varchar(80)  unique,
    amount_cents      bigint       not null check (amount_cents > 0),
    status            varchar(12)  not null check (status in ('PENDING', 'SUCCEEDED', 'FAILED')),
    origin            varchar(12)  not null check (origin in ('STAFF', 'DASHBOARD')),
    reason_code       varchar(40),
    note              text,
    requested_by      varchar(120),
    idempotency_key   varchar(160) not null unique,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null
);
create index invoice_refund_invoice on invoice_refund (invoice_id, created_at);

-- 4. Disputes (chargebacks, ACH unauthorised returns) on plan fees: staff see the open ones; a lost dispute reopens the
--    amount on the invoice and restarts dunning, a won one just closes.
create table invoice_dispute (
    id                uuid        primary key,
    stripe_dispute_id varchar(80) not null unique,
    invoice_id        varchar(80) not null references invoice (stripe_invoice_id),
    org_id            varchar(40) not null references organisation (id),
    payment_intent_id varchar(80) not null,
    charge_id         varchar(80),
    amount_cents      bigint      not null,
    reason            varchar(60),
    stripe_status     varchar(40) not null,
    outcome           varchar(8)  not null check (outcome in ('OPEN', 'WON', 'LOST')),
    funds_withdrawn   boolean     not null default false,
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    closed_at         timestamptz
);
create index invoice_dispute_open on invoice_dispute (outcome, created_at);
