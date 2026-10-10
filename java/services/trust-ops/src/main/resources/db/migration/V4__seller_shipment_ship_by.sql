-- S229: seller-health lateness from the ship-by date and the seller's handover (fulfilment.shipment.handed_over or
-- orders picked_up) instead of the delivery date alone.
alter table seller_shipment add column ship_by date;
alter table seller_shipment add column handed_over_at timestamptz;
