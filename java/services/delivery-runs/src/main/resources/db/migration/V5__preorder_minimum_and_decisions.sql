-- ADR 0005 §4: staff set a minimum order count per pre-order window. When the close date passes below it the window
-- waits (outcome AWAITING_DECISION, no auto-cancel) until staff cancel or proceed; every decision is logged with who,
-- when and why. A cancelled window refunds every order (orders-service), and each order's refund progress is
-- projected here so staff can follow it.

alter table preorder_window
    add column min_orders integer not null default 0 check (min_orders between 0 and 100000),
    add column outcome    varchar(20) check (outcome in ('AWAITING_DECISION', 'PROCEEDED', 'CANCELLED'));

comment on column preorder_window.min_orders is
    'Fewest orders the window needs; 0 = no minimum. Below it at the close date the window waits for a staff decision.';
comment on column preorder_window.outcome is
    'NULL = follows its dates. AWAITING_DECISION: closed below the minimum. PROCEEDED: staff went ahead. CANCELLED.';

create index preorder_window_awaiting on preorder_window (closes_at) where outcome = 'AWAITING_DECISION';

-- Append-only decision log (who / when / why).
create table preorder_window_decision (
    id         uuid          primary key,
    window_id  uuid          not null references preorder_window (id) on delete cascade,
    kind       varchar(16)   not null check (kind in ('AWAITING', 'PROCEED', 'CANCEL', 'EARLY_CLOSE')),
    reason     varchar(500)  not null,
    actor      varchar(120)  not null,
    orders     integer       not null,
    minimum    integer       not null,
    decided_at timestamptz   not null
);
create index preorder_window_decision_window on preorder_window_decision (window_id, decided_at);

-- Refund progress of a cancelled order (reported by orders-service as orders.preorder.refund.*).
alter table preorder_order
    add column refund_status       varchar(10) check (refund_status in ('PENDING', 'REFUNDED', 'VOIDED', 'FAILED')),
    add column refund_amount_minor bigint,
    add column refund_updated_at   timestamptz,
    add column refund_error        varchar(500);
create index preorder_order_refund_open on preorder_order (window_id) where refund_status in ('PENDING', 'FAILED');
