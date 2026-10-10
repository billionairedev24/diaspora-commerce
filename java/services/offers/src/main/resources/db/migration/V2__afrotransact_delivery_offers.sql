-- S255: an offer can be AfroTransact Delivery from the seller's store: seller-packed, collected by an AfroTransact
-- driver at a ship-from location with AfroTransact pickup enabled (ship_from.locationId, a seller-service id).
alter table offer drop constraint offer_fulfilment_check;
alter table offer alter column fulfilment type varchar(30);
alter table offer add constraint offer_fulfilment_check
    check (fulfilment in ('AFROTRANSACT', 'SELLER', 'AFROTRANSACT_DELIVERY'));
