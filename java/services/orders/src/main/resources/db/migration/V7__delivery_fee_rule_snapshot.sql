-- ADR 0005 §3: delivery fees are staff-managed rules (delivery-runs). The shipment keeps the rule it was charged by
-- (method, rule id and version, distance band, amount, where the amount came from) so later edits of a rule never
-- change an order already placed. NULL = flat rates, or AfroTransact Delivery from a seller's store (local_delivery).
alter table shipment add column delivery_fee_rule jsonb;
