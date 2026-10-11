-- S191 / S192 Your Privacy Choices: privacy requests (access, delete, correct, opt out) from the storefront form,
-- worked by staff in Back office -> Site content & legal; and the "Do not sell or share" choice on consent records.
-- Requests are due 45 days after they are received (US state privacy laws); every decision is audited.

create sequence privacy_request_seq start with 100001;

create table privacy_request (
    id              uuid         primary key,
    reference       varchar(20)  not null unique,
    request_type    varchar(16)  not null check (request_type in ('ACCESS', 'DELETE', 'CORRECT', 'OPT_OUT')),
    email           varchar(254) not null,
    state_code      char(2)      not null,
    details         text,
    account_subject varchar(120),
    locale          varchar(10)  not null,
    status          varchar(16)  not null check (status in ('RECEIVED', 'COMPLETED', 'DENIED')),
    resolution_note text,
    submitted_at    timestamptz  not null,
    due_at          timestamptz  not null,
    resolved_at     timestamptz,
    resolved_by     varchar(120),
    version         bigint       not null default 0,
    constraint privacy_request_resolved check ((status = 'RECEIVED') = (resolved_at is null))
);
create index privacy_request_queue on privacy_request (status, submitted_at desc);
create index privacy_request_email on privacy_request (email, submitted_at desc);

-- "Do not sell or share my personal information" (CCPA/CPRA and other state laws); GPC implies it.
alter table consent_record add column sale_opt_out boolean not null default false;
