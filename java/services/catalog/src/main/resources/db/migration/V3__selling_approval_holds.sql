-- Selling approvals (docs/engineering/listing-rules.md D4): a listing paused because its seller's approval for a scope
-- expired (after the grace), was revoked or paused by staff, or a new rule needs one. The hold remembers why, so the listing
-- comes back when the approval is renewed and never when the seller paused it themselves. Nothing is ever deleted.
create table product_selling_hold (
    product_id uuid         not null references product (id),
    scope_type varchar(24)  not null,
    scope_key  varchar(200) not null,
    org_id     varchar(80)  not null,
    reason     varchar(40)  not null,
    held_at    timestamptz  not null,
    primary key (product_id, scope_type, scope_key)
);
create index product_selling_hold_scope on product_selling_hold (scope_type, scope_key, org_id);
create index product_selling_hold_org on product_selling_hold (org_id);
