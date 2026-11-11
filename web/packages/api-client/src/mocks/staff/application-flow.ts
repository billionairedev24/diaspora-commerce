/* Application review workflow in the mock staff BFF: who holds each application (With / Waiting / SLA / Escalation),
   the queue tabs, assignment actions and the "Application review: SLAs and escalation" settings. Mirrors
   internal/app/staff_resources_application_flow.go of the Go BFF over seller-service. */
import { type FieldValue, type FormField, type StaffForm } from '../../schemas/staff';
import { type MockRecord, type Scalar } from './db';
import { type ActionCtx, type MockAction, type MockResource } from './resources';

const HOUR = 3_600;
const field = (key: string, label: string, type: FormField['type'], required: boolean, extra: Partial<FormField> = {}): FormField => ({ key, label, type, required, ...extra });
const str = (v: FieldValue | Scalar | undefined): string => (v === null || v === undefined ? '' : String(v));
const num = (v: FieldValue | Scalar | undefined): number => (typeof v === 'number' ? v : Number(v ?? 0));

export const APPLICATION_QUEUES = [
  { value: 'MY', label: 'My queue' },
  { value: 'AWAITING_REVIEW', label: 'Awaiting review' },
  { value: 'AWAITING_APPROVAL', label: 'Awaiting approval' },
  { value: 'ESCALATED', label: 'Escalated' },
  { value: 'RETURNED', label: 'Returned to applicant' },
  { value: 'ALL', label: 'All' },
  { value: 'OVERDUE', label: 'Overdue' },
  { value: 'UNASSIGNED', label: 'Unassigned' }
];

/** "12 min", "3 h", "2 days": how long a case has been at its stage. */
export function ago(seconds: number): string {
  if (seconds < 60) return 'under a minute';
  if (seconds < HOUR) return `${Math.floor(seconds / 60)} min`;
  if (seconds < 48 * HOUR) return `${Math.floor(seconds / HOUR)} h`;
  return `${Math.floor(seconds / (24 * HOUR))} days`;
}

/** Whether the record belongs to a queue tab for the signed-in person (the mock caller reviews and approves). */
export function inQueue(r: MockRecord, queue: string, me: string): boolean {
  const stage = str(r.fields.stage);
  const open = stage !== '';
  switch (queue) {
    case '':
    case 'ALL':
      return true;
    case 'MY':
      return open && (r.fields.assigneeId === me || (r.fields.assigneeId === null && str(r.fields.reviewerId) !== me));
    case 'AWAITING_REVIEW':
      return stage === 'REVIEW';
    case 'AWAITING_APPROVAL':
      return stage === 'APPROVAL';
    case 'ESCALATED':
      return open && num(r.fields.escalationLevel) > 0;
    case 'RETURNED':
      return r.fields.status === 'INFO_REQUESTED';
    case 'OVERDUE':
      return open && r.fields.sla === 'Overdue';
    case 'UNASSIGNED':
      return open && r.fields.assigneeId === null;
    default:
      return r.fields.status === queue;
  }
}

/** The stage line of the sheet header: "Awaiting review · with Amina Bello · since 2 days". */
export function stageLine(r: MockRecord): string {
  const stage = str(r.fields.stage);
  if (!stage) return str(r.fields.status).toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());
  const who = r.fields.assigneeName ? `with ${str(r.fields.assigneeName)}` : `any ${stage === 'REVIEW' ? 'reviewer' : 'approver'}`;
  return `${stage === 'REVIEW' ? 'Awaiting review' : 'Awaiting approval'} · ${who} · since ${ago(num(r.fields.waiting))}`;
}

/** Workflow fields of a seeded application by status (the caller is stf-1, who reviews and approves). */
export function seedWorkflow(status: string, i: number): Record<string, Scalar> {
  const base = { stage: '', with: null, waiting: null, sla: null, escalation: null, escalationLevel: 0, assigneeId: null, assigneeName: null, returnCount: 0, returnedByName: null, returnNote: null };
  if (status === 'PENDING_REVIEW') {
    const held = i === 0;
    const wait = i === 0 ? 50 * HOUR : i === 1 ? 3 * HOUR : 20 * HOUR;
    return { ...base, stage: 'REVIEW', with: held ? 'Amina Bello' : 'Any reviewer', waiting: wait, sla: wait >= 24 * HOUR ? 'Overdue' : wait >= 18 * HOUR ? 'Due soon' : 'On time', assigneeId: held ? 'stf-3' : null, assigneeName: held ? 'Amina Bello' : null, escalationLevel: i === 0 ? 1 : 0, escalation: i === 0 ? 'Level 1' : null,
      returnCount: i === 7 ? 1 : 0, returnedByName: i === 7 ? 'Adam Ade' : null, returnNote: i === 7 ? 'The tax ID was not checked against the IRS letter.' : null };
  }
  if (status === 'FLAGGED' || status === 'AWAITING_FINAL_APPROVAL') {
    return { ...base, stage: 'APPROVAL', with: 'Any approver', waiting: 5 * HOUR, sla: 'On time' };
  }
  if (status === 'INFO_REQUESTED') return { ...base, with: 'The applicant' };
  return base;
}

const flag = (r: MockRecord, ...allowed: string[]) => (allowed.includes(str(r.fields.stage)) ? null : 'Not possible while nobody on staff holds this case.');

const noteForm = (title: string, submit: string, label: string, required: boolean) => (): StaffForm => ({ title, submitLabel: submit, fields: [field(label === 'reason' ? 'reason' : 'note', label === 'reason' ? 'Why are you escalating?' : 'Note (internal)', 'textarea', required, { maxLength: 500 })] });

const touch = (r: MockRecord, ctx: ActionCtx, text: string) => r.activity.unshift({ at: ctx.now.toISOString(), actor: ctx.actor, text });

/** Claim, assign, release and escalate, as seller's flags allow them for the mock caller (a reviewer, approver and supervisor). */
export const ASSIGNMENT_ACTIONS: MockAction[] = [
  { key: 'claim', label: 'Claim', tone: 'primary', permission: 'applications:review', when: (r) => (r.fields.assigneeId === null ? flag(r, 'REVIEW', 'APPROVAL') : 'It is with someone. A supervisor can reassign it.'),
    apply: (r, _v, ctx) => { r.fields.assigneeId = ctx.actorId; r.fields.assigneeName = ctx.actor; r.fields.with = ctx.actor; touch(r, ctx, `Assigned: Claimed by ${ctx.actor}`); } },
  { key: 'assign', label: 'Assign…', tone: 'neutral', permission: 'applications:assign', when: (r) => flag(r, 'REVIEW', 'APPROVAL'),
    form: () => ({ title: 'Assign or take over', submitLabel: 'Assign', intro: 'Gives the case to one person. The person is told, and the change is recorded with their name.', fields: [field('assigneeId', 'Assign to', 'select', true, { options: [{ value: 'stf-3', label: 'Amina Bello' }, { value: 'stf-4', label: 'Rita Okoro' }] }), field('note', 'Note (internal)', 'textarea', false, { maxLength: 500 })] }),
    apply: (r, v, ctx) => { const name = v.assigneeId === 'stf-3' ? 'Amina Bello' : 'Rita Okoro'; r.fields.assigneeId = str(v.assigneeId); r.fields.assigneeName = name; r.fields.with = name; touch(r, ctx, `Assigned: Assigned to ${name}`); } },
  { key: 'release', label: 'Release to the pool', tone: 'neutral', permission: 'applications:review', when: (r) => (r.fields.assigneeId === null ? 'Nobody holds this case.' : null), form: noteForm('Release to the pool', 'Release', 'note', false),
    apply: (r, _v, ctx) => { r.fields.assigneeId = null; r.fields.assigneeName = null; r.fields.with = `Any ${str(r.fields.stage) === 'REVIEW' ? 'reviewer' : 'approver'}`; touch(r, ctx, 'Released: back to the pool'); } },
  { key: 'escalate', label: 'Escalate now', tone: 'danger', permission: 'applications:review', when: (r) => (num(r.fields.escalationLevel) >= 2 ? 'It is already at the highest level.' : flag(r, 'REVIEW', 'APPROVAL')), form: noteForm('Escalate now', 'Escalate', 'reason', true),
    apply: (r, v, ctx) => { const level = num(r.fields.escalationLevel) + 1; r.fields.escalationLevel = level; r.fields.escalation = `Level ${level}`; touch(r, ctx, `Escalated: Escalated to level ${level}: ${str(v.reason)}`); } }
];

/* ── Settings: Application review: SLAs and escalation ── */

const SETTINGS_ID = 'current';
const yesNo = [{ value: 'YES', label: 'Yes' }, { value: 'NO', label: 'No' }];
const MODES: Record<string, string> = { NONE: 'Off: cases wait in the pool', ROUND_ROBIN: 'Round robin', LEAST_LOADED: 'Least loaded' };

export function seedReviewSettings(now: Date): MockRecord[] {
  return [
    {
      id: SETTINGS_ID,
      fields: { name: 'Application review', reviewSla: '24 h', approvalSla: '24 h', autoAssign: MODES.NONE as string, updatedByName: 'Sue Supervisor', updatedAt: now.toISOString(), reviewSlaHours: 24, approvalSlaHours: 24, dueSoonPercent: 75, unassignedAfterHours: 4, level2AfterHours: 24, mode: 'NONE', enforceAssignee: 'NO', sendBackToReviewer: 'NO', reviewLevel1Role: 'platform.application_supervisor', reviewLevel1Person: null, reviewLevel2Role: 'platform.operations_manager', reviewLevel2Person: null, approvalLevel1Role: 'platform.application_supervisor', approvalLevel1Person: null, approvalLevel2Role: 'platform.operations_manager', approvalLevel2Person: null },
      activity: []
    }
  ];
}

const chainLine = (r: MockRecord, prefix: string, level: number) => {
  const role = str(r.fields[`${prefix}Level${level}Role`]);
  return role ? `everyone with the role ${role.replace('platform.', '').replace(/_/g, ' ')}` : 'Nobody set';
};

export const reviewSettingsDef: MockResource = {
  title: 'Application review: SLAs and escalation',
  columns: [
    { key: 'name', label: 'Settings', kind: 'strong', width: 24 }, { key: 'reviewSla', label: 'Review SLA', kind: 'text', width: 14 }, { key: 'approvalSla', label: 'Approval SLA', kind: 'text', width: 14 },
    { key: 'autoAssign', label: 'Auto-assignment', kind: 'text', width: 22 }, { key: 'updatedByName', label: 'Last changed by', kind: 'text', width: 20 }, { key: 'updatedAt', label: 'Changed', kind: 'datetime', width: 14 }
  ],
  facets: [],
  exportable: false,
  titleOf: () => 'Application review: SLAs and escalation',
  subtitleOf: () => 'How long a case may wait, who is told when it is late, and how cases are handed out',
  sheetSections: (r) => [
    { title: 'Service levels', fields: [
      { label: 'Review (step 1) must be done within', value: `${num(r.fields.reviewSlaHours)} hours`, kind: 'text' }, { label: 'Approval (step 2) must be done within', value: `${num(r.fields.approvalSlaHours)} hours`, kind: 'text' },
      { label: 'Due soon after', value: `${num(r.fields.dueSoonPercent)}% of the time`, kind: 'text' }, { label: 'Escalate a case nobody has taken after', value: `${num(r.fields.unassignedAfterHours)} hours`, kind: 'text' }] },
    { title: 'Escalation: review', fields: [{ label: 'Level 1', value: chainLine(r, 'review', 1), kind: 'text' }, { label: 'Level 2', value: chainLine(r, 'review', 2), kind: 'text' }] },
    { title: 'Escalation: approval', fields: [{ label: 'Level 1', value: chainLine(r, 'approval', 1), kind: 'text' }, { label: 'Level 2', value: chainLine(r, 'approval', 2), kind: 'text' }] },
    { title: 'Handing out cases', fields: [{ label: 'Auto-assignment', value: MODES[str(r.fields.mode)] ?? '', kind: 'text' }] }
  ],
  actions: [],
  edit: {
    permission: 'applications:assign',
    form: (r) => ({
      title: 'Application review: SLAs and escalation',
      submitLabel: 'Save',
      intro: 'Levels are in order: set level 1 before level 2. Every role and person must exist and be active. The change is recorded with your reason and what it was before.',
      fields: [
        field('reviewSlaHours', 'Review (step 1) must be done within, hours', 'number', true, { min: 1, max: 720, value: num(r.fields.reviewSlaHours) }),
        field('approvalSlaHours', 'Approval (step 2) must be done within, hours', 'number', true, { min: 1, max: 720, value: num(r.fields.approvalSlaHours) }),
        field('dueSoonPercent', 'Due soon after this percent of the time', 'number', true, { min: 1, max: 99, value: num(r.fields.dueSoonPercent) }),
        field('unassignedAfterHours', 'Escalate a case nobody has taken after, hours', 'number', true, { min: 1, max: 720, value: num(r.fields.unassignedAfterHours) }),
        field('level2AfterHours', 'Level 2 this many hours after the service level ends', 'number', true, { min: 1, max: 720, value: num(r.fields.level2AfterHours) }),
        field('autoAssign', 'Auto-assignment', 'select', true, { options: Object.entries(MODES).map(([value, label]) => ({ value, label })), value: str(r.fields.mode) }),
        field('enforceAssignee', 'Only the assignee may decide an assigned case', 'select', true, { options: yesNo, value: str(r.fields.enforceAssignee) }),
        field('sendBackToReviewer', 'A case sent back goes to the original reviewer', 'select', true, { options: yesNo, value: str(r.fields.sendBackToReviewer) }),
        field('reviewLevel1Role', 'Review: level 1 role (everyone holding it)', 'text', false, { value: str(r.fields.reviewLevel1Role) }),
        field('reviewLevel2Role', 'Review: level 2 role (everyone holding it)', 'text', false, { value: str(r.fields.reviewLevel2Role) }),
        field('approvalLevel1Role', 'Approval: level 1 role (everyone holding it)', 'text', false, { value: str(r.fields.approvalLevel1Role) }),
        field('approvalLevel2Role', 'Approval: level 2 role (everyone holding it)', 'text', false, { value: str(r.fields.approvalLevel2Role) }),
        field('reason', 'Reason (10 to 500 characters, kept in the access log)', 'textarea', true, { maxLength: 500, minLength: 10 })
      ],
      changeKeys: ['reviewSlaHours', 'approvalSlaHours', 'dueSoonPercent', 'unassignedAfterHours', 'level2AfterHours', 'autoAssign', 'enforceAssignee', 'sendBackToReviewer', 'reviewLevel1Role', 'reviewLevel2Role', 'approvalLevel1Role', 'approvalLevel2Role']
    }),
    validate: (_r, v) => {
      const errors: Record<string, string> = {};
      for (const k of ['reviewSlaHours', 'approvalSlaHours', 'unassignedAfterHours', 'level2AfterHours']) {
        const n = Number(v[k]);
        if (!Number.isFinite(n) || n < 1 || n > 720) errors[k] = 'Use 1 to 720 hours';
      }
      const pct = Number(v.dueSoonPercent);
      if (!Number.isFinite(pct) || pct < 1 || pct > 99) errors.dueSoonPercent = 'Use 1 to 99 percent';
      for (const prefix of ['review', 'approval']) {
        if (!str(v[`${prefix}Level1Role`]).trim() && str(v[`${prefix}Level2Role`]).trim()) errors[`${prefix}Level2Role`] = 'Set level 1 before level 2';
      }
      return errors;
    },
    apply: (r, v, ctx) => {
      r.fields.reviewSlaHours = Number(v.reviewSlaHours);
      r.fields.approvalSlaHours = Number(v.approvalSlaHours);
      r.fields.dueSoonPercent = Number(v.dueSoonPercent);
      r.fields.unassignedAfterHours = Number(v.unassignedAfterHours);
      r.fields.level2AfterHours = Number(v.level2AfterHours);
      r.fields.mode = str(v.autoAssign);
      r.fields.autoAssign = MODES[str(v.autoAssign)] ?? '';
      r.fields.enforceAssignee = str(v.enforceAssignee);
      r.fields.sendBackToReviewer = str(v.sendBackToReviewer);
      for (const k of ['reviewLevel1Role', 'reviewLevel2Role', 'approvalLevel1Role', 'approvalLevel2Role']) r.fields[k] = str(v[k]).trim() || null;
      r.fields.reviewSla = `${Number(v.reviewSlaHours)} h`;
      r.fields.approvalSla = `${Number(v.approvalSlaHours)} h`;
      r.fields.updatedByName = ctx?.actor ?? 'Unknown user';
      r.fields.updatedAt = ctx?.now.toISOString() ?? new Date().toISOString();
      r.activity.unshift({ at: ctx?.now.toISOString() ?? new Date().toISOString(), actor: ctx?.actor ?? 'Unknown user', text: `Changed: ${str(v.reason)}` });
    }
  }
};
