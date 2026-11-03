-- S298: how each organisation pays its plan. EARNINGS_FIRST takes the fee from its pending earnings (payments withholds
-- it from the next transfers) and charges the card only for any remainder; CARD always charges the card (sellers who
-- book plan fees as a separate card expense). Replaces the staff-only opt-in (collect_from_earnings).
alter table subscription add column plan_fee_collection varchar(16) not null default 'CARD'
    check (plan_fee_collection in ('EARNINGS_FIRST', 'CARD'));
-- DEFAULT (platform default when the subscription started), ONBOARDING (chosen on the Plan step), SELLER (owner or
-- finance in Plan & billing), STAFF (per-seller override, audited with a reason).
alter table subscription add column plan_fee_collection_source varchar(12) not null default 'DEFAULT'
    check (plan_fee_collection_source in ('DEFAULT', 'ONBOARDING', 'SELLER', 'STAFF'));
alter table subscription add column plan_fee_collection_changed_at timestamptz;
alter table subscription add column plan_fee_collection_changed_by varchar(80);
update subscription set plan_fee_collection = 'EARNINGS_FIRST', plan_fee_collection_source = 'STAFF'
    where collect_from_earnings;
alter table subscription drop column collect_from_earnings;

-- Platform billing settings (staff console › Plans & billing › Settings). One row per key, optimistic version.
create table billing_setting (
    key        varchar(60)  primary key,
    value      varchar(200) not null,
    updated_by varchar(80)  not null,
    updated_at timestamptz  not null,
    version    bigint       not null default 0
);
-- New sellers pay their plan from their sales unless they (or staff) choose the card.
insert into billing_setting (key, value, updated_by, updated_at)
values ('plan_fee_collection_default', 'EARNINGS_FIRST', 'system', now());

-- Every change of an organisation's choice: who (seller member or staff), why (staff: reason code and note) and the
-- first invoice it applies to.
create table plan_fee_collection_change (
    id           uuid        primary key,
    org_id       varchar(40) not null references organisation (id),
    previous     varchar(16) check (previous in ('EARNINGS_FIRST', 'CARD')),
    mode         varchar(16) not null check (mode in ('EARNINGS_FIRST', 'CARD')),
    source       varchar(12) not null check (source in ('ONBOARDING', 'SELLER', 'STAFF')),
    actor_id     varchar(80) not null,
    reason_code  varchar(40),
    note         text,
    applies_from timestamptz,
    created_at   timestamptz not null,
    check (source <> 'STAFF' or reason_code is not null)
);
create index plan_fee_collection_change_org on plan_fee_collection_change (org_id, created_at desc);

-- Collection of one invoice: what Stripe asked (invoice_amount_cents), what we asked payments for (amount_cents: the
-- invoice less the customer's credit balance), what payments withheld (possibly less), what we credited at Stripe and
-- what was released again (voided / refunded invoice). Retried with a back-off until attempts run out.
alter table earnings_collection add column invoice_number varchar(80);
alter table earnings_collection add column invoice_amount_cents bigint;
alter table earnings_collection add column withheld_cents bigint not null default 0 check (withheld_cents >= 0);
alter table earnings_collection add column credited_cents bigint not null default 0 check (credited_cents >= 0);
alter table earnings_collection add column released_cents bigint not null default 0 check (released_cents >= 0);
alter table earnings_collection add column next_attempt_at timestamptz;
alter table earnings_collection add column last_error text;
alter table earnings_collection drop constraint earnings_collection_status_check;
-- REQUESTED → WITHHELD → CREDITED; DECLINED (nothing withheld: the card pays); MISSED (Stripe finalised the invoice
-- first: anything withheld is released); FAILED (retries ran out: anything withheld is released); RELEASED (voided or
-- refunded after the credit).
alter table earnings_collection add constraint earnings_collection_status_check
    check (status in ('REQUESTED', 'WITHHELD', 'CREDITED', 'DECLINED', 'MISSED', 'FAILED', 'RELEASED'));
drop index earnings_collection_open;
create index earnings_collection_open on earnings_collection (next_attempt_at, created_at)
    where status in ('REQUESTED', 'WITHHELD');
create index earnings_collection_org on earnings_collection (org_id, created_at desc);

-- Releases to send to payments (and, when the credit already reached the invoice, the matching Stripe balance debit):
-- one per idempotency key (plan-fee-release-<invoice> for a void, plan-fee-release-<credit note> for a refund).
create table earnings_release (
    idempotency_key               varchar(120) primary key,
    stripe_invoice_id             varchar(80)  not null references earnings_collection (stripe_invoice_id),
    amount_cents                  bigint       not null check (amount_cents > 0),
    reason                        varchar(200) not null,
    debit_balance                 boolean      not null,
    status                        varchar(10)  not null check (status in ('PENDING', 'DONE', 'FAILED')),
    stripe_balance_transaction_id varchar(80),
    attempts                      int          not null default 0,
    next_attempt_at               timestamptz  not null,
    last_error                    text,
    created_at                    timestamptz  not null,
    updated_at                    timestamptz  not null
);
create index earnings_release_open on earnings_release (next_attempt_at) where status = 'PENDING';

-- Reason for staff overrides of how a seller pays its plan (also offered for other adjustments).
insert into billing_reason_code (code, label, sort_order) values
 ('SELLER_REQUEST', 'Requested by the seller', 70)
on conflict do nothing;
