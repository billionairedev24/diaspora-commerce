-- S264: where each order's package is on its run, from orders-service shipment events (picked_up, out_for_delivery,
-- delivery_failed, delivered, returned). The run moves to collecting / out for delivery / completed from these.
alter table run_order
    add column stage varchar(20) not null default 'PENDING'
        check (stage in ('PENDING', 'COLLECTED', 'OUT_FOR_DELIVERY', 'FAILED', 'DELIVERED', 'RETURNED')),
    add column stage_at timestamptz,
    add column delivered_at timestamptz;
