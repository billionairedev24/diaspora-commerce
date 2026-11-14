-- Shadow mode runs every configured provider. A line a provider was not asked about because no code is mapped is its
-- own kind (NO_CODE), and a provider that gave no quote says why: NOT_CONFIGURED, TIMEOUT or ERROR.
alter table tax_shadow_quote drop constraint tax_shadow_quote_kind_check;
alter table tax_shadow_quote add constraint tax_shadow_quote_kind_check
    check (kind in ('MATCH', 'OUTCOME', 'RATE', 'BOTH', 'PROVIDER_ERROR', 'NO_CODE', 'NO_DECISION'));
alter table tax_shadow_quote add column failure varchar(20)
    check (failure is null or failure in ('NOT_CONFIGURED', 'TIMEOUT', 'ERROR'));
-- Rows written before this migration recorded a missing code as PROVIDER_ERROR with this message.
update tax_shadow_quote set kind = 'NO_CODE' where kind = 'PROVIDER_ERROR' and provider_error like 'No % code is mapped for %';
update tax_shadow_quote set failure = 'ERROR' where kind = 'PROVIDER_ERROR' and failure is null;
create index tax_shadow_quote_order on tax_shadow_quote (order_ref, line_id);
