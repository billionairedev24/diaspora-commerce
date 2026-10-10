-- S255: AfroTransact Delivery from the seller's store. The checkout snapshot of a shipment collected by our driver at
-- the seller's pickup location: location id and position, distance (straight line + estimated road miles), the
-- quoted customer charge (= shipping_minor) and seller fee, and the fee-schedule version that priced it.
alter table shipment add column local_delivery jsonb;
