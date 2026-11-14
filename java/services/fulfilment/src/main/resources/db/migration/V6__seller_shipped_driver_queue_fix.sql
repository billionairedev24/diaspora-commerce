-- Data fix: driver-queue rows of seller-shipped orders (see orders V11). The driver app is dormant, and these shipments are
-- shipped by their seller, so a seller-origin delivery_shipment with no seller-store pickup location that is still waiting
-- for a route is cancelled here. Pre-order packages (shipment_bag rows, delivered by their window's run) and anything
-- already on a route are left alone. Idempotent.
update delivery_shipment d
   set status = 'CANCELLED', updated_at = now()
 where d.origin = 'SELLER'
   and d.pickup_location_id is null
   and d.status = 'AWAITING_ROUTE'
   and d.route_id is null
   and not exists (select 1 from shipment_bag b where b.shipment_id = d.shipment_id)
   and not exists (select 1 from run_shipment r where r.shipment_id = d.shipment_id);
