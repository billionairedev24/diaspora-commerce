-- A community pre-order package (one per window) mixes the lines of several sellers; keep the seller on each line so
-- delivery-runs can give every seller a pick list and fulfilment can plan one pickup per seller.
alter table order_line add column seller_org_id varchar(80);
alter table order_line add column seller_name varchar(200);
