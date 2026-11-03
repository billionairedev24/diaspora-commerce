-- V82: AfroTransact bills plans itself (no Stripe Billing). Periods come from the subscription's RRULE
-- (MONTHLY / QUARTERLY / ANNUAL, see V81 anchor_date); this migration adds what the rest of the model needs.
--
-- 1. Own invoices and the payments that settle them (platform credit → earnings, if the seller chose it → card).
-- 2. The Stripe customer used only for plain PaymentIntents / SetupIntents (no Stripe Billing objects).
-- 3. Incentives: a charge gate per subscription (start charging after X sales or X listings, waive while X listings,
--    waive until a date for a reason), free months, coupons that last N invoices.

-- ── Invoices ────────────────────────────────────────────────────────────────────────────────────────────────────
-- The primary key keeps its name; ids are now ours ("inv_<uuid>").
alter table invoice add column subtotal_cents bigint not null default 0;
alter table invoice add column discount_cents bigint not null default 0;
alter table invoice add column plan_code varchar(40);
alter table invoice add column plan_version int;
alter table invoice add column cadence varchar(16);
alter table invoice add column due_at timestamptz;
alter table invoice add column paid_at timestamptz;
alter table invoice add column attempts int not null default 0;
alter table invoice add column next_attempt_at timestamptz;
alter table invoice add column last_error text;
alter table invoice add column waived_reason_code varchar(40);
alter table invoice add column waived_note text;
alter table invoice add column waived_by varchar(80);
alter table invoice add column version bigint not null default 0;

update invoice set subtotal_cents = amount_due_cents where subtotal_cents = 0;

-- One cycle invoice per organisation and period: issuing is idempotent.
create unique index invoice_cycle_period on invoice (org_id, period_start)
    where billing_reason in ('subscription_create', 'subscription_cycle');
-- Open invoices the collection job works through.
create index invoice_collectable on invoice (next_attempt_at) where status = 'open';

create sequence invoice_number_seq start 1000;

-- What settled each invoice, in order: CREDIT (platform credit balance), EARNINGS (withheld from the seller's sales,
-- only when the seller chose it), CARD (a plain Stripe PaymentIntent), WAIVER (staff waiver or an incentive).
create table invoice_payment (
    id           uuid        primary key,
    invoice_id   varchar(80) not null references invoice (stripe_invoice_id),
    org_id       varchar(40) not null references organisation (id),
    source       varchar(12) not null check (source in ('CREDIT', 'EARNINGS', 'CARD', 'WAIVER')),
    amount_cents bigint      not null check (amount_cents > 0),
    status       varchar(12) not null check (status in ('APPLIED', 'PENDING', 'FAILED', 'REVERSED')),
    reference    varchar(120),
    note         text,
    created_at   timestamptz not null
);
create index invoice_payment_invoice on invoice_payment (invoice_id, created_at);
-- A source settles an invoice once per reference (payment intent id, withholding id, credit key): replays are no-ops.
create unique index invoice_payment_reference on invoice_payment (invoice_id, source, reference)
    where reference is not null;

-- ── Stripe customer for off-session PaymentIntents ──────────────────────────────────────────────────────────────
create table billing_customer (
    org_id             varchar(40) primary key references organisation (id),
    stripe_customer_id varchar(80) not null unique,
    created_at         timestamptz not null
);

-- ── Incentives ──────────────────────────────────────────────────────────────────────────────────────────────────
-- One gate per subscription, set by staff with a reason:
--   START_AFTER   no plan charge until the seller reaches threshold of metric (sales since the gate was set, or live
--                 listings); periods before that are $0 "deferred" invoices, then billing starts normally;
--   WAIVE_WHILE   every period is waived while the seller keeps at least threshold of metric (e.g. 20 live listings);
--   WAIVE_UNTIL   every period is waived until charge_gate_until (a free stretch or a staff waiver with a reason).
alter table subscription add column charge_gate_mode varchar(16)
    check (charge_gate_mode in ('START_AFTER', 'WAIVE_WHILE', 'WAIVE_UNTIL'));
alter table subscription add column charge_gate_metric varchar(32)
    check (charge_gate_metric in ('GROSS_SALES', 'COMPLETED_ORDERS', 'COMPLETED_BOOKINGS', 'ACTIVE_LISTINGS'));
alter table subscription add column charge_gate_threshold bigint check (charge_gate_threshold > 0);
alter table subscription add column charge_gate_since timestamptz;
alter table subscription add column charge_gate_until timestamptz;
alter table subscription add column charge_gate_reason_code varchar(40);
alter table subscription add column charge_gate_note text;
alter table subscription add column charge_gate_by varchar(80);
alter table subscription add column charge_gate_set_at timestamptz;
alter table subscription add column charge_gate_met_at timestamptz;
alter table subscription add constraint charge_gate_shape check (
    charge_gate_mode is null
    or (charge_gate_mode = 'WAIVE_UNTIL' and charge_gate_until is not null)
    or (charge_gate_mode <> 'WAIVE_UNTIL' and charge_gate_metric is not null and charge_gate_threshold is not null));

-- Free months (calendar months, not 30-day blocks) and coupons that last N invoices.
alter table billing_grant add column free_months int check (free_months is null or free_months between 1 and 36);
alter table billing_grant add column remaining_invoices int check (remaining_invoices is null or remaining_invoices >= 0);
alter table billing_grant add column gate_mode varchar(16);
alter table billing_grant drop constraint billing_grant_type_check;
alter table billing_grant add constraint billing_grant_type_check check (type in ('FREE_DAYS', 'FREE_MONTHS',
    'CREDIT', 'PERCENT_OFF', 'WAIVE_INVOICE', 'TRIAL_END', 'CHARGE_GATE'));
update billing_grant set remaining_invoices = duration_invoices where type = 'PERCENT_OFF';

-- ── Leftovers of the Stripe catalogue sync ──────────────────────────────────────────────────────────────────────
alter table plan_version_price drop column if exists lookup_key;
alter table plan_version_price drop column if exists synced_at;
alter table plan_version_price drop column if exists sync_error;
alter table billing_grant drop column if exists stripe_balance_transaction_id;
-- What a grant changed on the subscription, for the audit trail (replaces the Stripe object ids).
alter table billing_grant add column reference varchar(120);

-- ── Sellers choose whether plans are paid from their earnings ──────────────────────────────────────────────────
-- The default is the card: platform credit and coupons always come first, earnings only when the seller chose them.
-- (Staff can still change the default in Plans & billing › Settings.)
update billing_setting set value = 'CARD', updated_by = 'system', updated_at = now()
where key = 'plan_fee_collection_default' and updated_by = 'system';
update subscription set plan_fee_collection = 'CARD' where plan_fee_collection_source = 'DEFAULT';
