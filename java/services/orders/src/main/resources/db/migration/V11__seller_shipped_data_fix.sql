-- Data fix: seller-shipped orders that sat at "waiting for admin to assign a driver".
--
-- Cause: with the seller carrier switch off (launch) checkout labelled every seller-packed package with no explicit choice
-- AFROTRANSACT_DELIVERY although the seller never opted into delivery by AfroTransact (the seller-store snapshot,
-- shipment.local_delivery, is only present when AfroTransact collects from the seller's store). The seller could then only
-- confirm, pack and mark ready, and staff saw "Waiting for the driver".
--
-- Fix: a seller-packed (SELLER_FULFILLED) AFROTRANSACT_DELIVERY shipment without the seller-store snapshot that is still
-- open becomes SELLER_SHIPS: the seller marks it shipped and delivered, and it is never in the Delivery desk. Delivered,
-- returned and cancelled shipments are left as they were (their history stays true). Idempotent: once changed a row no
-- longer matches. Each change is written to the append-only delivery audit.
with fixed as (
    update shipment s
       set delivery_method = 'SELLER_SHIPS',
           updated_at = now(),
           version = s.version + 1
     where s.fulfilment_type = 'SELLER_FULFILLED'
       and s.delivery_method = 'AFROTRANSACT_DELIVERY'
       and s.local_delivery is null
       and s.status in ('PLACED', 'CONFIRMED', 'PACKED', 'READY', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY',
                        'DELIVERY_FAILED')
    returning s.id, s.order_id, s.status
)
insert into delivery_audit (shipment_id, order_id, action, from_status, to_status, actor_id, actor_name, detail,
                            occurred_at)
select f.id, f.order_id, 'DATA_FIX', f.status, f.status, 'migration:orders-V11', 'Data fix V11',
       '{"was": "AFROTRANSACT_DELIVERY", "now": "SELLER_SHIPS", "why": "the seller did not opt into delivery by AfroTransact"}'::jsonb,
       now()
  from fixed f;
