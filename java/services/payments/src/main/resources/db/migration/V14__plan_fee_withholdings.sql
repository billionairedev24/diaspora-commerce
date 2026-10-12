-- S298: seller plan fees collected from earnings. The seller service asks payments to withhold a plan invoice's amount
-- (POST /v1/orgs/{orgId}/plan-fee-withholdings, Idempotency-Key plan-fee-<invoice>) before Stripe finalises the
-- invoice; payments debits the seller payable (Dr 2000:org without an order / Cr 4600) and the organisation's next
-- transfers recover it. One row per invoice: a replay answers the first decision.
create table plan_fee_withholding (
    id              uuid         primary key,
    org_id          varchar(80)  not null,
    invoice_id      varchar(80)  not null unique,
    invoice_number  varchar(80),
    currency        char(3)      not null,
    requested_minor bigint       not null check (requested_minor > 0),
    withheld_minor  bigint       not null check (withheld_minor >= 0),
    released_minor  bigint       not null default 0 check (released_minor >= 0),
    -- Signed: recovered from transfers (positive) minus paid back with transfers (negative).
    settled_minor   bigint       not null default 0,
    status          varchar(12)  not null check (status in ('WITHHELD', 'DECLINED', 'RELEASED')),
    reason          text,
    journal_id      uuid         references journal_entry (id) deferrable initially deferred,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0,
    check (withheld_minor <= requested_minor),
    check (released_minor <= withheld_minor),
    check ((status = 'DECLINED') = (withheld_minor = 0)),
    check (status = 'DECLINED' or journal_id is not null)
);
create index plan_fee_withholding_org on plan_fee_withholding (org_id, created_at desc);
create index plan_fee_withholding_open on plan_fee_withholding (org_id, created_at)
    where withheld_minor - released_minor - settled_minor <> 0;

-- Releases (voided invoice, refund out of band): one per Idempotency-Key, so a retried release posts once.
create table plan_fee_release (
    idempotency_key varchar(120) primary key,
    withholding_id  uuid         not null references plan_fee_withholding (id),
    amount_minor    bigint       not null check (amount_minor > 0),
    reason          varchar(200),
    journal_id      uuid         not null references journal_entry (id) deferrable initially deferred,
    created_at      timestamptz  not null
);
create index plan_fee_release_withholding on plan_fee_release (withholding_id);

-- What each transfer recovered (or paid back): the "Plan fee (invoice …)" deduction lines of a seller's settlements.
create table plan_fee_deduction (
    id             uuid         primary key,
    withholding_id uuid         not null references plan_fee_withholding (id),
    order_id       varchar(80)  not null,
    amount_minor   bigint       not null check (amount_minor <> 0),
    journal_id     uuid         not null references journal_entry (id) deferrable initially deferred,
    created_at     timestamptz  not null
);
create index plan_fee_deduction_withholding on plan_fee_deduction (withholding_id, created_at);
