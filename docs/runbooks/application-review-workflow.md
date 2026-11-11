# Application review workflow: roles, queues, SLAs and escalation

Seller and service-provider applications go through two steps (seller-service `Application` state machine):
DRAFT, PENDING_REVIEW (**step 1**, `applications:review`), then AWAITING_FINAL_APPROVAL or FLAGGED (**step 2**,
`applications:approve`), then APPROVED or REJECTED. This page covers who may do what, who *has* a case, the queues, the
SLAs and the escalation. The state machine itself did not change.

## Roles and permissions (identity migration V39)

| Role | Permissions | Applications module |
|---|---|---|
| Application Reviewer (`platform.application_reviewer`) | `applications:review` | approve level |
| Application Approver (`platform.application_approver`) | `applications:approve` | approve level |
| Application Supervisor (`platform.application_supervisor`) | `applications:assign` only | edit level |
| Super Admin | all three, plus `applications:pii` | full |
| Operations Manager, Trust & Safety (unchanged) | review and approve | approve level |

- Anyone with **any** of review, approve or assign can **view** every application (list, sheet, SLA, who has it). Acting
  stays gated by permission; personal data stays masked without `applications:pii`; opening a document needs review or
  approve.
- The same person may hold both the reviewer and the approver role. The **two-person rule holds per application**: the
  person who did step 1 can never hold or decide step 2 of that application, and nobody holds their own application.
- A Supervisor does **not** get review or approve by default; the owner may grant them (then they can also take cases).

## Who has a case

Each application in staff hands has a **stage** (REVIEW or APPROVAL, from its status), an **assignee** or nobody (the
pool: "awaiting review from any reviewer"), who assigned it and when, and when it entered the stage (the SLA clock).

| Action | Who | Rule |
|---|---|---|
| Claim | an eligible person | case is unassigned; repeating it is a no-op |
| Assign / reassign / take over | `applications:assign`, the person an escalation went to, or an approver at the approval stage | assignee must hold the step permission, not be the applicant, and at approval not be the step 1 reviewer |
| Release | the assignee, or a manager | back to the pool |
| Escalate now | the assignee, or a supervisor | needs a reason; raises one level |

Movement between stages: step 1 approve or flag puts the case in the approval pool, unassigned (unless auto-assignment
is on); **Send back** returns it to the review pool (unassigned, or the original reviewer if that setting is on) with
the "Returned by approver" banner and a count; **Return to applicant** clears the assignment; resubmitting restarts at
step 1. Assignment never blocks a decision unless "only the assignee may decide an assigned case" is on (a supervisor
can always take over first). Every change is on the case timeline with names; the applicant never sees them.

## Queues (staff console, Applications)

My queue, Awaiting review, Awaiting approval, Escalated, Returned to applicant, All; chips Overdue and Unassigned.
Seller API: `GET /v1/applications?queue=&stage=&assignee=&unassigned=&overdue=&escalated=`.

## SLAs and escalation (Settings, "Application review: SLAs and escalation")

Defaults: review 24 h, approval 24 h, due soon at 75 %, a case nobody has taken is escalated after 4 h, level 2 comes 24 h
after the SLA ends, auto-assignment off. Hours, not business hours (not built). The job `SellerJobs.applicationEscalations`
runs every 5 minutes (`at.seller.jobs.escalation-interval`); each case is checked under its application row lock and only
moves up one level per run, so replicas and re-runs escalate once. An escalation writes a timeline entry, publishes
`seller.application.escalated` and tells the level's targets, who can take over or reassign. It clears when the case
moves to the next stage or is decided. A level with no target configured is skipped (and retried).

Settings need `applications:assign`, a reason (10+ characters) and are audited (`review_settings_audit`). Validation:
hours 1 to 720, due soon 1 to 99 %, level 2 needs level 1, every role must have active holders and every person must be
active staff. The console form takes one role and one person per level.

## Notifications (internal staff only)

Seller publishes `seller.application.assigned` (assigned to you, auto-assigned, ready for approval, sent back) and
`seller.application.escalated` through the outbox on `at.seller-application.events` with `recipientUserIds`. Ready for
final approval goes to the assignee if auto-assigned, otherwise to every holder of `applications:approve` except the step
1 reviewer; sent back goes to the previous reviewer and the review pool. notifications-service does not consume these
yet: until it does, staff find their cases in **My queue** and **Escalated**.

## FAQ

- **Someone is on leave.** A supervisor opens Awaiting review/approval, filters by their name (search), and uses
  **Assign…** to move each case, or the person releases them. With "only the assignee may decide" off, anyone eligible can
  decide a case anyway.
- **Nobody can approve (one approver, who did step 1).** Give a second person the Approver role, or have an Operations
  Manager (holds both) take it. The two-person rule cannot be bypassed.
- **A case is overdue but not escalated.** No target is set for that level, or the directory was unreachable; set the chain
  in Settings. The Overdue chip still lists it.
- **Does the applicant see any of this?** No.
- **Turn on auto-assignment.** Settings, Auto-assignment: round robin or least loaded among active staff who may hold the case.

## Restart and migration order

identity (V39: roles, `applications:assign`, `GET /v1/internal/staff`) first, then seller (V89: `application_case`,
`review_settings`; existing cases in review are backfilled unassigned), then gateway-bff and the staff console. seller
asks identity for the staff roster with its service token (scope `users:read`, already allowed for `seller-service`).
