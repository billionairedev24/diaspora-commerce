-- S323 (ADR 0006): a shipping fee the customer's membership waived is recorded on the shipment with the tier and the
-- benefit, next to the fee rule snapshot of ADR 0005. shipping_minor is what was charged after the waiver. NULL = none.
alter table shipment add column membership_waiver jsonb;
