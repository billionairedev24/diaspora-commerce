-- Application review workflow: who holds each application (the case), SLA and escalation settings with an audit trail.
-- The review state machine itself (application.status, reviewer_id, final_approver_id) is unchanged.

create table application_case (
    application_id           varchar(40) primary key references application (id),
    stage                    varchar(10) check (stage in ('REVIEW', 'APPROVAL')),
    assignee_id              varchar(80),
    assignee_name            varchar(200),
    assigned_by_id           varchar(80),
    assigned_by_name         varchar(200),
    assigned_at              timestamptz,
    stage_entered_at         timestamptz not null,
    escalation_level         int         not null default 0 check (escalation_level between 0 and 2),
    escalated_at             timestamptz,
    escalation_reason        varchar(500),
    escalation_target_ids    jsonb       not null default '[]',
    escalation_target_names  jsonb       not null default '[]',
    returned_by_id           varchar(80),
    returned_by_name         varchar(200),
    returned_at              timestamptz,
    return_note              text,
    return_count             int         not null default 0,
    updated_at               timestamptz not null,
    version                  bigint      not null default 0
);
create index application_case_assignee on application_case (assignee_id) where assignee_id is not null;
create index application_case_stage on application_case (stage, stage_entered_at);
create index application_case_escalated on application_case (escalation_level) where escalation_level > 0;

-- Cases already in review when this runs: unassigned, the wait counted from the last change.
insert into application_case (application_id, stage, stage_entered_at, updated_at)
select id, case when status = 'PENDING_REVIEW' then 'REVIEW' else 'APPROVAL' end,
       coalesce(reviewed_at, submitted_at, updated_at), updated_at
from application where status in ('PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL');

-- One row of settings (id = 1); the defaults apply until staff change them.
create table review_settings (
    id                      int primary key check (id = 1),
    review_sla_hours        int         not null default 24,
    approval_sla_hours      int         not null default 24,
    due_soon_percent        int         not null default 75,
    unassigned_after_hours  int         not null default 4,
    level2_after_hours      int         not null default 24,
    auto_assign             varchar(16) not null default 'NONE' check (auto_assign in ('NONE', 'ROUND_ROBIN', 'LEAST_LOADED')),
    enforce_assignee        boolean     not null default false,
    send_back_to_reviewer   boolean     not null default false,
    review_chain            jsonb       not null default '{}',
    approval_chain          jsonb       not null default '{}',
    round_robin_cursor      jsonb       not null default '{}',
    updated_by              varchar(80),
    updated_by_name         varchar(200),
    updated_at              timestamptz
);
insert into review_settings (id) values (1);

create table review_settings_audit (
    id         uuid primary key,
    actor_id   varchar(80)  not null,
    actor_name varchar(200),
    reason     varchar(500) not null,
    before_json jsonb       not null,
    after_json  jsonb       not null,
    changed_at timestamptz  not null
);
