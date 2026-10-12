-- S310: the organisation's image library. Product images uploaded with library = true; the SKU and slot (MAIN,
-- PT01–PT08, SWCH) are parsed from the file name when the upload is requested. Applied on every start, so every
-- statement is idempotent.
alter table asset add column if not exists library boolean not null default false;
alter table asset add column if not exists library_sku text;
alter table asset add column if not exists library_slot text;
-- The library page (newest first), SKU matching and file-name matching.
create index if not exists asset_library on asset (owner_org, created_at desc) where library;
create index if not exists asset_library_sku on asset (owner_org, library_sku, library_slot) where library;
create index if not exists asset_library_name on asset (owner_org, lower(file_name)) where library;
