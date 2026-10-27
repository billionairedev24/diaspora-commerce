-- AfroTransact credit (S320) and gift cards (S321), ADR 0006.
-- Credit is stored value that only buys products and services. It is a ledger of append-only entries; the balance is
-- their sum. Nothing here converts credit to cash: there is no payout, card-refund or transfer table or column.

create table credit_account (
    customer_id   varchar(120) primary key,
    currency      char(3)     not null,
    -- Sum of the customer's entries, kept in step by the transaction that appends an entry (row lock). The check is
    -- the last line of defence against overspending.
    balance_minor bigint      not null default 0 check (balance_minor >= 0),
    created_at    timestamptz not null,
    updated_at    timestamptz not null
);

create table credit_entry (
    id                  uuid primary key,
    customer_id         varchar(120) not null references credit_account (customer_id),
    kind                varchar(12)  not null check (kind in ('GRANT', 'SPEND', 'REFUND', 'ADJUSTMENT')),
    -- signed: grants and refunds are positive, spends negative, adjustments either
    amount_minor        bigint       not null check (amount_minor <> 0),
    currency            char(3)      not null,
    source              varchar(12)  not null
        check (source in ('REFERRAL', 'GIFT_CARD', 'MEMBERSHIP', 'REFUND', 'STAFF', 'PROMO', 'ORDER')),
    reason              text         not null,
    actor               varchar(120) not null,
    order_id            varchar(80),
    reference           varchar(120),
    idempotency_key     varchar(200) not null unique,
    balance_after_minor bigint       not null check (balance_after_minor >= 0),
    created_at          timestamptz  not null,
    check ((kind in ('GRANT', 'REFUND') and amount_minor > 0) or (kind = 'SPEND' and amount_minor < 0)
        or kind = 'ADJUSTMENT')
);
create index credit_entry_customer on credit_entry (customer_id, created_at desc, id);
create index credit_entry_order on credit_entry (order_id) where order_id is not null;

create function credit_entry_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'credit_entry is append-only (% rejected)', tg_op using errcode = 'integrity_constraint_violation';
end $$;
create trigger credit_entry_no_update before update or delete on credit_entry
    for each row execute function credit_entry_append_only();
create trigger credit_entry_no_truncate before truncate on credit_entry
    for each statement execute function credit_entry_append_only();

-- Staff grants and adjustments with a second approver above the staff-set limit (same maker-checker as ledger_adjustment).
create table credit_adjustment (
    id            uuid primary key,
    customer_id   varchar(120) not null,
    currency      char(3)      not null,
    amount_minor  bigint       not null check (amount_minor <> 0),
    source        varchar(12)  not null check (source in ('STAFF', 'PROMO')),
    reason        text         not null,
    status        varchar(16)  not null check (status in ('PROPOSED', 'POSTED', 'REJECTED')),
    proposed_by   varchar(120) not null,
    proposed_at   timestamptz  not null,
    decided_by    varchar(120),
    decided_at    timestamptz,
    decision_note text,
    entry_id      uuid
);
create index credit_adjustment_status on credit_adjustment (status, proposed_at desc);
create index credit_adjustment_customer on credit_adjustment (customer_id, proposed_at desc);

-- Staff-configurable money settings (one row) and their history.
create table money_settings (
    id                          smallint primary key check (id = 1),
    gift_card_min_minor         bigint       not null check (gift_card_min_minor > 0),
    gift_card_max_minor         bigint       not null check (gift_card_max_minor >= gift_card_min_minor),
    gift_card_presets           jsonb        not null,
    gift_card_terms             jsonb        not null default '{}'::jsonb,
    credit_approval_limit_minor bigint       not null check (credit_approval_limit_minor >= 0),
    updated_by                  varchar(120) not null,
    updated_at                  timestamptz  not null,
    version                     bigint       not null default 0
);
insert into money_settings (id, gift_card_min_minor, gift_card_max_minor, gift_card_presets, credit_approval_limit_minor,
                            updated_by, updated_at)
values (1, 1000, 50000, '[2500, 5000, 10000, 20000]'::jsonb, 10000, 'system', now());

create table money_settings_change (
    id         uuid primary key,
    changed_by varchar(120) not null,
    changed_at timestamptz  not null,
    reason     text         not null,
    before     jsonb        not null,
    after      jsonb        not null
);
create index money_settings_change_at on money_settings_change (changed_at desc);

create table gift_card (
    id                uuid primary key,
    -- HMAC-SHA256 of the normalised code; the code itself is never stored. Null until the code is generated for
    -- delivery (on the send date), so a scheduled card has no code at rest.
    code_hash         char(64) unique,
    code_last4        char(4),
    amount_minor      bigint       not null check (amount_minor > 0),
    currency          char(3)      not null,
    origin            varchar(10)  not null check (origin in ('PURCHASE', 'STAFF')),
    status            varchar(16)  not null
        check (status in ('PENDING_PAYMENT', 'ACTIVE', 'REDEEMED', 'VOID', 'PAYMENT_FAILED')),
    purchaser_id      varchar(120),
    sender_name       varchar(120),
    recipient_email   varchar(254) not null,
    recipient_name    varchar(120),
    message           varchar(500),
    locale            varchar(10),
    send_at           timestamptz  not null,
    delivery_status   varchar(10)  not null check (delivery_status in ('SCHEDULED', 'SENT')),
    delivery_count    integer      not null default 0,
    sent_at           timestamptz,
    payment_intent_id varchar(80) unique,
    charge_id         varchar(80),
    issue_reason      text,
    issued_by         varchar(120),
    redeemed_by       varchar(120),
    redeemed_at       timestamptz,
    void_reason       text,
    voided_by         varchar(120),
    voided_at         timestamptz,
    refunded_to_card  boolean      not null default false,
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    version           bigint       not null default 0
);
create index gift_card_purchaser on gift_card (purchaser_id, created_at desc);
create index gift_card_due on gift_card (send_at) where status = 'ACTIVE' and delivery_status = 'SCHEDULED';
create index gift_card_recipient on gift_card (lower(recipient_email));

-- Redeem attempts, for rate limits against guessing codes.
create table gift_card_attempt (
    id      bigserial primary key,
    subject varchar(160) not null,
    success boolean      not null,
    made_at timestamptz  not null
);
create index gift_card_attempt_subject on gift_card_attempt (subject, made_at desc);

-- Credit as a payment source (S320): the part of an order paid with credit. payment.amount stays the whole order, the
-- Stripe PaymentIntent is for amount - credit (none when credit pays everything).
alter table payment add column credit_minor bigint not null default 0 check (credit_minor >= 0);
alter table payment add column shopper_id varchar(120);
alter table payment add constraint payment_credit_within_amount check (credit_minor <= amount_minor);
alter table payment_capture add column credit_minor bigint not null default 0 check (credit_minor >= 0);
alter table payment_refund add column credit_minor bigint not null default 0 check (credit_minor >= 0);
alter table payment_refund add column card_as_credit boolean not null default false;
