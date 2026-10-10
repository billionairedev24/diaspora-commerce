-- S030: moderation of public images (verdict, reasons, AI check, EXIF stripping) and perceptual-hash duplicate
-- detection. Applied on every start, so every statement is idempotent.
alter table asset add column if not exists moderation jsonb;
alter table asset add column if not exists phash bigint;
alter table asset add column if not exists version int not null default 0;  -- optimistic locking
-- Duplicate lookups scan completed images of the public purposes only.
create index if not exists asset_phash on asset (purpose) include (phash, owner_org)
    where phash is not null and status in ('READY', 'FLAGGED');
