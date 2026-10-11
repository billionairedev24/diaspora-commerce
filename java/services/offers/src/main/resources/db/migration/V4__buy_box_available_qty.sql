-- S281: the winner's available quantity is part of the buy box headline, so offers.buybox.changed is published when
-- it changes (search shows the quantity the PDP sells, including stock held under a retail SKU or FBA FNSKU).
alter table buy_box add column winner_available_qty integer;
