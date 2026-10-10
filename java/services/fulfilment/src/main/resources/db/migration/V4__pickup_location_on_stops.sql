-- S259: seller pickups are per ship-from location. A PICKUP stop carries the seller-service location id, so two
-- locations of one seller become two stops, and dispatch can list routed and unrouted pickups by location.
alter table route_stop add column pickup_location_id varchar(64);

update route_stop st
set pickup_location_id = s.pickup_location_id
from route_package p
join delivery_shipment s on s.shipment_id = p.shipment_id
where p.pickup_stop_id = st.id and st.kind = 'PICKUP' and s.pickup_location_id is not null;

create index delivery_shipment_pickups on delivery_shipment (status, created_at) where origin = 'SELLER';
