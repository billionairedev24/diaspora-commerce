-- How exactly the ship-to was known when the provider was asked: ADDRESS (a street was sent), CITY (the ZIP was resolved
-- from a city name) or ZIP (only a ZIP). The shadow report says 'estimated from ZIP' for the last, so a difference is
-- not read as a provider error when the provider was given less than a full address.
alter table tax_shadow_quote add column address_precision varchar(10)
    check (address_precision is null or address_precision in ('ADDRESS', 'CITY', 'ZIP'));
