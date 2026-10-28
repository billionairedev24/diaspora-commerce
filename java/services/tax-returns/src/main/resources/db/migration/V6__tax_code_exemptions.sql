-- Items we know are not taxable, so they are neither charged tax nor sent to the tax provider (which bills per call).
--   taxable = false      : never taxed anywhere (gift cards, donations, items staff mark exempt everywhere)
--   exempt_states        : taxed elsewhere, known exempt in these states (comma-separated state codes, e.g. "TX,NJ"):
--                          staff decide with the CPA; the built-in rate table's zero rates are not used for this.
alter table tax_code add column taxable boolean not null default true;
alter table tax_code add column exempt_states varchar(200) not null default '';

insert into tax_code (code, label, category, provider_code, taxable) values
    ('NON_TAXABLE', 'Not taxable (gift cards, donations, exempt items)', 'GENERAL', null, false);
