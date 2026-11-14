-- Delivery desk (docs/engineering/delivery-fulfilment.md): AfroTransact staff run AfroTransact-delivered shipments end to
-- end from the staff console, without the driver app. Three tables:
--   delivery_job    who has the job (staff or courier, by name), the attempt count and an optimistic-lock version
--   delivery_proof  the proof of delivery: at least one photo (media asset ids), optional recipient name and note, the
--                   time and the staff member's name; coordinates only when the device had them. One row per
--                   shipment; saved once, changed only by an audited admin correction (version + corrections)
--   delivery_audit  append-only trail of every desk action (take, assign, pickup, out for delivery, delivered, failed,
--                   reattempt, proof correction), idempotent per (shipment, idempotency key)
create table delivery_job (
    shipment_id    uuid primary key references shipment (id),
    assignee_id    varchar(120),
    assignee_name  varchar(200),
    assigned_at    timestamptz,
    assigned_by    varchar(120),
    attempts       int          not null default 0 check (attempts >= 0),
    last_failure   text,
    created_at     timestamptz  not null,
    updated_at     timestamptz  not null,
    version        bigint       not null default 0
);
create index delivery_job_assignee on delivery_job (assignee_id) where assignee_id is not null;

create table delivery_proof (
    id                 uuid primary key,
    shipment_id        uuid         not null unique references shipment (id),
    photo_asset_ids    text[]       not null check (cardinality(photo_asset_ids) between 1 and 5),
    recipient_name     varchar(120),
    note               varchar(500),
    latitude           double precision check (latitude between -90 and 90),
    longitude          double precision check (longitude between -180 and 180),
    delivered_at       timestamptz  not null,
    recorded_by_id     varchar(120) not null,
    recorded_by_name   varchar(200) not null,
    corrected_at       timestamptz,
    corrected_by_id    varchar(120),
    corrected_by_name  varchar(200),
    corrections        int          not null default 0,
    created_at         timestamptz  not null,
    version            bigint       not null default 0,
    constraint delivery_proof_position check ((latitude is null) = (longitude is null))
);

create table delivery_audit (
    id               bigserial primary key,
    shipment_id      uuid         not null references shipment (id),
    order_id         uuid         not null references customer_order (id),
    action           varchar(30)  not null,
    from_status      varchar(30),
    to_status        varchar(30),
    actor_id         varchar(120) not null,
    actor_name       varchar(200),
    detail           jsonb,
    idempotency_key  varchar(200),
    occurred_at      timestamptz  not null
);
create index delivery_audit_shipment on delivery_audit (shipment_id, id);
create unique index delivery_audit_idem on delivery_audit (shipment_id, idempotency_key) where idempotency_key is not null;

-- Append-only audit; a proof is never deleted (corrections update it and write the old values to the audit).
create function delivery_desk_immutable() returns trigger as $$
begin
    raise exception '% on % is not allowed (append-only)', tg_op, tg_table_name;
end;
$$ language plpgsql;

create trigger delivery_audit_append_only before update or delete on delivery_audit
    for each row execute function delivery_desk_immutable();
create trigger delivery_proof_no_delete before delete on delivery_proof
    for each row execute function delivery_desk_immutable();

-- The queue reads in-flight AfroTransact-delivered shipments by status and recency.
create index shipment_delivery_queue on shipment (status, status_changed_at desc)
    where delivery_method = 'AFROTRANSACT_DELIVERY';
