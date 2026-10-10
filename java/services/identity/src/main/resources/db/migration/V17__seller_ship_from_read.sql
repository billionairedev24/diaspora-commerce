-- S255: service clients read one seller ship-from location by id (AfroTransact Delivery pickup locations):
-- offers-service validates AFROTRANSACT_DELIVERY offers, orders-service measures checkout distances.
insert into permission (key, module, description) values
    ('sellers:ship-from.read', 'seller', 'Read seller ship-from / pickup locations by id (service clients only)')
on conflict (key) do nothing;
