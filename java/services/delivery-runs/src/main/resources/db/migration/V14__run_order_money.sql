-- S265: what each order of a run actually paid and what came back, so a run's P&L is built from its real orders and not
-- from orders x one fee. All columns are filled by what delivery-runs is told: the reservation (fee, total, how it is
-- delivered), payments.captured (the platform's commission on the goods) and payments.refunded (money handed back).
-- Null = not reported (older rows and orders-service versions that do not send it): the P&L then falls back to the
-- run's fee and says so.
alter table run_order
    add column if not exists fee_minor bigint check (fee_minor >= 0),
    add column if not exists total_minor bigint check (total_minor >= 0),
    add column if not exists delivery_kind varchar(12) check (delivery_kind in ('DOOR', 'PICKUP', 'CARRIER')),
    add column if not exists commission_minor bigint check (commission_minor >= 0),
    add column if not exists refunded_minor bigint not null default 0 check (refunded_minor >= 0),
    add column if not exists refunded_fee_minor bigint not null default 0 check (refunded_fee_minor >= 0),
    add column if not exists refunded_at timestamptz;
