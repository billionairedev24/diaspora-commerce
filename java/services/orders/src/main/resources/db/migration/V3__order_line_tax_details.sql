-- S235: order lines keep the catalog product type and the tax quote's local jurisdiction and shipping tax, so
-- orders.order.placed and the service lookup give tax-returns everything it needs (no customer-entered data).
alter table order_line
    add column product_type           varchar(80),
    add column local_tax_jurisdiction varchar(40),
    add column shipping_tax_minor     bigint not null default 0;
alter table order_line add constraint order_line_shipping_tax_check
    check (shipping_tax_minor between 0 and tax_minor);
