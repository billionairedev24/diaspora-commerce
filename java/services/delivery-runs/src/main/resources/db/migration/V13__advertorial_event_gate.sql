-- S263: views and clicks count once per visitor (or client address) and advertorial in a time window, so a direct POST
-- cannot inflate them. One row per advertorial, subject (a hash of the visitor id or of the client address) and kind
-- holds the start of the current window and the hits admitted in it.
create table local_advertorial_gate (
    advertorial_id uuid        not null references local_advertorial (id) on delete cascade,
    subject        varchar(80) not null,
    kind           varchar(8)  not null check (kind in ('VIEW', 'CLICK')),
    window_start   timestamptz not null,
    hits           integer     not null,
    primary key (advertorial_id, subject, kind)
);
create index local_advertorial_gate_window on local_advertorial_gate (window_start);
