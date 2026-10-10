-- Label billing (S256): who bought a label, for which shipment, who pays for it and what the seller is charged.
alter table shipment_label add column if not exists org_id text not null default '';
alter table shipment_label add column if not exists shipment_id text not null default '';
alter table shipment_label add column if not exists order_id text not null default '';
alter table shipment_label add column if not exists fulfilment text not null default '';
alter table shipment_label add column if not exists bill_to text not null default '';
alter table shipment_label add column if not exists billing_reason text not null default '';
alter table shipment_label add column if not exists handling_minor bigint not null default 0;
alter table shipment_label add column if not exists charge_minor bigint not null default 0;
create index if not exists shipment_label_org_idx on shipment_label (org_id, created_at) where org_id <> '';
