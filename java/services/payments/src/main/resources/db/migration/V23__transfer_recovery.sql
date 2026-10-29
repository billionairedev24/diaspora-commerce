-- Refunds Stripe would not take back from a seller's transfer. When a refund debits a seller whose net was already
-- transferred, payments reverses the transfer; if Stripe refuses for good (a 4xx such as balance_insufficient because the
-- seller's balance was already paid out) the customer's refund still completes and the amount the seller owes the platform
-- becomes one row here. The ledger shows it as a negative seller payable (TRANSFER_RECOVERY_OPENED); the seller's later
-- transfers recover it (transfer_recovery_deduction), or Finance resolves it with four eyes (received outside Stripe, or
-- written off). One row per (refund, transfer): a replay of the refund never opens a second.
create table transfer_recovery (
    id                 uuid         primary key,
    refund_id          uuid         not null references payment_refund (id),
    order_id           varchar(80)  not null,
    org_id             varchar(80)  not null,
    transfer_id        uuid         not null references settlement_transfer (id),
    stripe_transfer_id varchar(80)  not null,
    currency           char(3)      not null,
    amount_minor       bigint       not null check (amount_minor > 0),
    -- Taken from later transfers of the organisation.
    recovered_minor    bigint       not null default 0 check (recovered_minor >= 0),
    -- Closed by an approved resolution: received outside Stripe, or written off.
    resolved_minor     bigint       not null default 0 check (resolved_minor >= 0),
    status             varchar(12)  not null check (status in ('OPEN', 'RECOVERED', 'WRITTEN_OFF')),
    stripe_code        varchar(80),
    reason             text,
    -- A resolution one Finance person proposed and a different one has yet to approve.
    pending_kind       varchar(12)  check (pending_kind in ('RECOVERED', 'WRITE_OFF')),
    pending_reason     text,
    pending_reference  varchar(120),
    pending_by         varchar(120),
    pending_at         timestamptz,
    decided_by         varchar(120),
    decided_at         timestamptz,
    decision_note      text,
    resolution         varchar(12)  check (resolution in ('RECOVERED', 'WRITE_OFF')),
    resolution_ref     varchar(120),
    journal_id         uuid         not null references journal_entry (id) deferrable initially deferred,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0,
    unique (refund_id, transfer_id),
    check (recovered_minor + resolved_minor <= amount_minor),
    check (status <> 'RECOVERED' or recovered_minor + resolved_minor = amount_minor),
    check (status <> 'WRITTEN_OFF' or (resolution = 'WRITE_OFF' and recovered_minor + resolved_minor = amount_minor)),
    check ((pending_kind is null) = (pending_by is null))
);
create index transfer_recovery_org on transfer_recovery (org_id, created_at desc);
create index transfer_recovery_status on transfer_recovery (status, created_at desc);
create index transfer_recovery_open on transfer_recovery (org_id, created_at) where status = 'OPEN';

-- What each transfer recovered: the "Refund recovery" deduction lines of a seller's settlements.
create table transfer_recovery_deduction (
    id          uuid        primary key,
    recovery_id uuid        not null references transfer_recovery (id),
    order_id    varchar(80) not null,
    amount_minor bigint     not null check (amount_minor > 0),
    journal_id  uuid        not null references journal_entry (id) deferrable initially deferred,
    created_at  timestamptz not null
);
create index transfer_recovery_deduction_recovery on transfer_recovery_deduction (recovery_id, created_at);
