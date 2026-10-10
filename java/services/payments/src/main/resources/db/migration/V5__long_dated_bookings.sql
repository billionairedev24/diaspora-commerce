-- S220: payments support for long-dated bookings.

-- Off-session charges: a follow-up PaymentIntent for lines of an order whose original authorisation was released or
-- lapsed (card holds last ~7 days, bookings can be a year ahead). The child shares the order id, so ledger
-- payables, the settlement hold and Connect transfers (transfer_group = order id) stay per order. Only the primary
-- payment of an order is unique per order id.
alter table payment add column parent_payment_id uuid references payment (id);
alter table payment drop constraint payment_order_id_key;
create unique index payment_primary_order on payment (order_id) where parent_payment_id is null;
create index payment_children on payment (parent_payment_id) where parent_payment_id is not null;

-- Late provider-cancellation fees (booking.cancelled with cancelledBy = PROVIDER on at.bookings.events).
-- One fee per booking (business-key dedupe on top of the event inbox). Accrued in the ledger when recorded
-- (Dr 1200:<org> / Cr 4300) and collected from the provider's connected account with a Stripe account debit
-- (Dr 1000 / Cr 1200:<org>). OUTSTANDING fees are retried by the settlement job and reserved out of the payout
-- balance so they cannot be paid out first.
create table provider_fee (
    id                uuid primary key,
    booking_id        varchar(80)  not null unique,
    booking_reference varchar(80),
    org_id            varchar(80)  not null,
    currency          char(3)      not null,
    amount_minor      bigint       not null check (amount_minor > 0),
    reason            varchar(40)  not null,
    status            varchar(20)  not null check (status in ('OUTSTANDING', 'COLLECTED')),
    stripe_debit_id   varchar(80) unique,
    attempts          integer      not null default 0,
    -- Suffix of the Stripe idempotency key of the account debit: bumped only after a definitive (4xx) refusal, so
    -- a retry after a timeout replays the same request and can never debit twice.
    debit_seq         integer      not null default 0,
    last_error        text,
    next_attempt_at   timestamptz,
    source_event_id   uuid,
    occurred_at       timestamptz  not null,
    created_at        timestamptz  not null,
    collected_at      timestamptz,
    version           bigint       not null default 0
);
create index provider_fee_org on provider_fee (org_id, currency);
create index provider_fee_due on provider_fee (next_attempt_at) where status = 'OUTSTANDING';
