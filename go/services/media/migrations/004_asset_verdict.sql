-- S388: slot-aware image checks. Each product image carries the slot it was checked for, a verdict (PASS, WARN, HOLD,
-- REJECT), the findings (codes, levels and numbers the seller's language is picked by) and the measurements the
-- verdict rests on, so another slot or a changed threshold can be judged without reading the file again. Applied on
-- every start, so every statement is idempotent.
alter table asset add column if not exists image_slot text;
alter table asset add column if not exists verdict text;
alter table asset add column if not exists checks jsonb;
alter table asset add column if not exists metrics jsonb;
alter table asset add column if not exists fill_ratio real;
alter table asset add column if not exists border_ratio real;
-- What the form told the checks (title, brand, product type); sent to the AI hook, never returned to clients.
alter table asset add column if not exists check_context jsonb;
-- True when the verdict was recorded but not applied (shadow mode, media.image.enforce off).
alter table asset add column if not exists shadow boolean not null default false;
-- Catalog's verify and rescore calls find a published image by its public URL.
create index if not exists asset_url on asset (url) where url is not null;

-- Existing product images have no measurements. They are backfilled as PASS (published), HOLD (held) or REJECT with
-- no findings until they are rescored (POST /v1/assets/rescore); until then only their size is judged.
update asset set verdict = case status when 'READY' then 'PASS' when 'FLAGGED' then 'HOLD' else 'REJECT' end,
    checks = '[]'::jsonb
where purpose = 'product-image' and verdict is null and status <> 'PENDING';

-- The staff override of the image rules (one row; each environment has its own database).
create table if not exists image_rules_override (
    id         int primary key check (id = 1),
    settings   jsonb       not null default '{}',
    updated_by text,
    updated_at timestamptz
);
