-- Category tax build, phase E: each order line keeps the tax decision its sale used (tax-returns' stored decision at
-- order time, never a live recompute) and its share of the shipment's shipping, so refunds and returns reverse with
-- the code the sale was taxed with and the delivery tax follows the lines it was allocated to (TX-05 to TX-07).
-- Orders placed before this migration have all columns null: their shipping sat on the shipment's first line.
alter table order_line
    add column shipping_alloc_minor   bigint,
    add column tax_decision_status    varchar(20),
    add column tax_outcome            varchar(12),
    add column internal_tax_code      varchar(120),
    add column provider_tax_code      varchar(80),
    add column tax_rule_version       varchar(60),
    add column tax_provider           varchar(40),
    add column tax_address_precision  varchar(20);
alter table order_line add constraint order_line_shipping_alloc_check
    check (shipping_alloc_minor is null or shipping_alloc_minor >= 0);
alter table order_line add constraint order_line_tax_outcome_check
    check (tax_outcome is null or tax_outcome in ('EXEMPT', 'TAXABLE'));
-- A placed line only ever carries a definite decision: blocked lines stop the checkout before an order exists.
alter table order_line add constraint order_line_tax_decision_check
    check (tax_decision_status is null or tax_decision_status = 'RESOLVED');
