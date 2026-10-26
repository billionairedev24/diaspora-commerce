-- S264 (V9: renumbered from a clashing V7; idempotent so a database that already ran it as V7 is not broken): where each order's package is on its run, from orders-service shipment events (picked_up, out_for_delivery,
-- delivery_failed, delivered, returned). The run moves to collecting / out for delivery / completed from these.
alter table run_order
    add column if not exists stage varchar(20) not null default 'PENDING'
        check (stage in ('PENDING', 'COLLECTED', 'OUT_FOR_DELIVERY', 'FAILED', 'DELIVERED', 'RETURNED')),
    add column if not exists stage_at timestamptz,
    add column if not exists delivered_at timestamptz;
