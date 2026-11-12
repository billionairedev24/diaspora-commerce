-- Never sell without a tax decision (docs/engineering/tax-gate-paths.md). A listing that is live must have a stored,
-- resolved tax decision on every variant. This migration (1) adds the hold that remembers why a listing was closed for tax,
-- so it comes back on its own when tax review approves it, and (2) closes every listing that is live today without one.
-- Nothing is invented: no decision is created, no tax status is changed. Closed listings go back to IN_REVIEW with a plain
-- note for the seller; `announced_at` stays empty until the catalog service has published catalog.product.paused for the
-- listing (search and offers drop it, sellers see the note), then it is set. Nothing is deleted; a hold is released, not removed.
create table product_tax_hold (
    product_id   uuid        not null primary key references product (id),
    reason_code  varchar(40) not null,
    held_at      timestamptz not null,
    announced_at timestamptz,
    released_at  timestamptz
);
create index product_tax_hold_open on product_tax_hold (held_at) where released_at is null;
create index product_tax_hold_unannounced on product_tax_hold (held_at) where announced_at is null and released_at is null;

-- A variant has a decision only when its gate status is READY and the rules stored a decision object. A live product with
-- no variant at all has none either. Re-running is harmless: a closed product is no longer LIVE.
with offenders as (
    select p.id
    from product p
    where p.status = 'LIVE'
      and (not exists (select 1 from product_variant v where v.product_id = p.id)
           or exists (select 1 from product_variant v
                      where v.product_id = p.id
                        and (v.tax_status <> 'READY' or jsonb_typeof(v.tax_decision) is distinct from 'object')))
), closed as (
    update product
       set status = 'IN_REVIEW',
           moderation_note = 'We are checking the tax details for this item.',
           updated_at = now(),
           version = version + 1
     where id in (select id from offenders) and status = 'LIVE'
    returning id
)
insert into product_tax_hold (product_id, reason_code, held_at)
select id, 'TAX_DECISION_MISSING', now() from closed
on conflict (product_id) do nothing;

-- The audit query (catalog_live_without_tax_decision) reads live products by status.
create index if not exists product_live_status on product (status) where status = 'LIVE';
