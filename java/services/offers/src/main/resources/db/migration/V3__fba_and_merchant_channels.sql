-- S257: a seller can sell the same variant from their own store and Fulfilled by AfroTransact at the same time.
-- One open offer per seller, variant, condition and channel (Fulfilled by AfroTransact vs seller-fulfilled), and seller
-- SKUs unique per channel, so the FBA offer may reuse the seller's SKU.
drop index offer_open_seller_atin_condition;
create unique index offer_open_seller_atin_condition_channel
    on offer (seller_org, atin, item_condition, (fulfilment = 'AFROTRANSACT')) where status <> 'WITHDRAWN';
drop index offer_open_seller_sku;
create unique index offer_open_seller_sku_channel
    on offer (seller_org, seller_sku, (fulfilment = 'AFROTRANSACT')) where status <> 'WITHDRAWN';
