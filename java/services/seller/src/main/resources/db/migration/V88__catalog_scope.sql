-- Catalog scope: the catalog departments (department node codes) an organisation may sell in, recorded when its
-- application is approved and changed by staff or an approved request. No rows: the organisation cannot list anything
-- that belongs to a department until some are recorded.
create table org_department (
    org_id          varchar(40)  not null references organisation (id),
    department_code varchar(120) not null,
    source          varchar(16)  not null check (source in ('ONBOARDING', 'STAFF', 'REQUEST')),
    added_by        varchar(80),
    created_at      timestamptz  not null,
    primary key (org_id, department_code)
);

create table department_request (
    id              uuid primary key,
    org_id          varchar(40)  not null references organisation (id),
    department_code varchar(120) not null,
    note            text,
    status          varchar(12)  not null check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by    varchar(80),
    created_at      timestamptz  not null,
    decided_by      varchar(80),
    decided_at      timestamptz,
    decision_note   text
);
create index department_request_org on department_request (org_id, created_at desc);
create index department_request_status on department_request (status, created_at);
-- One open request per organisation and department.
create unique index department_request_open on department_request (org_id, department_code) where status = 'PENDING';
