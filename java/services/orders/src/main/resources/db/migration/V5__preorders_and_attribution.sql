-- S272 community pre-orders.
-- Orders keep how they came about: storefront attribution {src, cr, po}, the channel derived from it (AGENT_LATE for
-- customer-service late adds), tags (LATE_ADD) and, for orders created on a customer's behalf, the staff actor,
-- reason, note and payment link.
alter table customer_order
    add column origin  jsonb,
    add column channel varchar(20);
create index customer_order_awaiting_late_add on customer_order (created_at)
    where channel = 'AGENT_LATE' and status = 'PENDING_PAYMENT';

-- PREORDER shipments carry their window snapshot (id, code, name, delivery date, close time, door / pickup) and the
-- missed-delivery state; the window id is a column for dispatch and analytics queries.
alter table shipment
    add column preorder           jsonb,
    add column preorder_window_id varchar(80);
alter table shipment add constraint shipment_preorder_snapshot
    check ((fulfilment_type = 'PREORDER') = (preorder is not null));
create index shipment_preorder_window on shipment (preorder_window_id) where preorder_window_id is not null;
create index shipment_preorder_due on shipment (promised_by)
    where fulfilment_type = 'PREORDER' and status not in ('DELIVERED', 'RETURNED', 'CANCELLED');
