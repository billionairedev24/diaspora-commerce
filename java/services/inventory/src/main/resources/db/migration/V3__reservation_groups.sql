-- Per-shipment reservations (S218) and stock owner on reservation events (S226).
-- A reservation line may carry the caller's group (orders: its order line id) and has its own status, so the lines
-- of one shipment can be shipped (COMMITTED) or given back (RELEASED) while other shipments stay HELD.
alter table reservation_line
    add column group_key       varchar(80),
    add column status          varchar(12) not null default 'HELD',
    add column stock_owner_org varchar(80);

update reservation_line l
set status = case r.status when 'HELD' then 'HELD' when 'COMMITTED' then 'COMMITTED' else 'RELEASED' end
from reservation r
where r.id = l.reservation_id;

update reservation_line l
set stock_owner_org = p.owner_org
from inventory_position p
where p.sku = l.sku and p.node_id = l.node_id;

alter table reservation_line
    add constraint reservation_line_status_check check (status in ('HELD', 'COMMITTED', 'RELEASED'));

create index reservation_line_group on reservation_line (reservation_id, group_key) where group_key is not null;
