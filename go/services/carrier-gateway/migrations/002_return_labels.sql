-- Return labels (S235): rate choice and box-free QR drop-off.
alter table shipment_label add column if not exists kind text not null default 'outbound';
alter table shipment_label add column if not exists rate_choice text not null default '';
alter table shipment_label add column if not exists box_free boolean not null default false;
alter table shipment_label add column if not exists qr_payload text not null default '';
alter table shipment_label add column if not exists drop_off_network text not null default '';
