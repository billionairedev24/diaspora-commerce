/* Plain words for the staff tax mocks: a port of the BFF's staff_tax_text.go, so the mock sheets read as the real ones do
   ("If net weight is at least 2.5 oz and servings per package is more than 1, then exempt"). */

export interface TaxFactDef {
  id: string;
  label: string;
  kind: string;
  unit?: string | null;
  owner: string;
  help?: string;
  evidence?: boolean;
  choices?: { value: string; label: string }[];
}

type Expr = { all?: Expr[]; any?: Expr[]; not?: Expr; op?: string; fact?: string; value?: unknown; values?: unknown[] } | null | undefined;

export const GATES: Record<string, { label: string; explain: string }> = {
  ACTIVE: { label: 'Ready', explain: 'Products in this category can go live once tax review approves them.' },
  NEEDS_APPROVAL: { label: 'Needs tax sign-off', explain: 'The Texas tax professional must approve this category’s rules before any product in it can go live.' },
  BLOCKED_BUNDLE: { label: 'Blocked until bundles are itemised', explain: 'Mixed bundles cannot be sold until each item is its own line with its own price and category.' },
  REFINE_CODE: { label: 'Needs a specific provider code', explain: 'The provider code is generic. Each product needs a verified specific code from tax review before it can go live.' },
  CONFLICT_OPEN: { label: 'Waiting on a decision', explain: 'Two codes conflict. An open decision must be made and the rules updated before this category can go live.' }
};
export const GATE_ORDER = ['ACTIVE', 'NEEDS_APPROVAL', 'REFINE_CODE', 'BLOCKED_BUNDLE', 'CONFLICT_OPEN'];

export const RECORD_STATUS: Record<string, string> = {
  DRAFT: 'Draft',
  MISSING_FACTS: 'Seller still has to answer questions',
  PENDING_REVIEW: 'Waiting for tax review',
  APPROVED: 'Approved',
  REJECTED: 'Sent back to the seller',
  STALE: 'Changed since approval, needs a new review'
};

export const RESOLVE_STATUS: Record<string, string> = {
  RESOLVED: 'Tax is decided',
  MISSING_FACTS: 'Answers are missing',
  BLOCKED: 'Cannot be sold as listed',
  NEEDS_APPROVAL: 'Category needs tax sign-off',
  REFINE_CODE: 'Needs a specific provider code',
  CONFLICT_OPEN: 'Category waits on a decision'
};

export const OUTCOME: Record<string, string> = { EXEMPT: 'Exempt (no tax)', TAXABLE: 'Taxable', BLOCKED: 'Blocked' };
export const OWNER: Record<string, string> = {
  VARIANT: 'Product (the seller answers)',
  OFFER: 'Listing (the seller answers)',
  SELLER: 'Seller profile',
  STAFF: 'Tax team only',
  CONTEXT: 'Worked out by the system'
};
export const PROVIDER: Record<string, string> = { STRIPE: 'Stripe Tax', AVALARA: 'Avalara' };
export const AUDIT_ACTION: Record<string, string> = {
  FACTS_CHANGED: 'Facts changed',
  SUBMITTED: 'Submitted for review',
  APPROVED: 'Approved',
  REJECTED: 'Sent back',
  RECLASSIFIED: 'Moved to another category',
  MARKED_STALE: 'Marked out of date',
  EVIDENCE_ADDED: 'Evidence added'
};

export const humanizeCode = (s: string): string => {
  const t = s.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[_.-]+/g, ' ').trim().toLowerCase();
  return t ? t[0]!.toUpperCase() + t.slice(1) : t;
};

const stripParens = (s: string) => s.replace(/\s*\([^)]*\)/g, '').trim();

function phrase(f: TaxFactDef): string {
  const label = stripParens(f.label) || humanizeCode(f.id);
  if (label.length > 1 && /[A-Z]/.test(label[1]!)) return label; // an acronym or a proper name
  return label[0]!.toLowerCase() + label.slice(1);
}

const subject = (f: TaxFactDef): string => (f.kind === 'BOOLEAN' || f.kind === 'CHOICE' ? `"${stripParens(f.label)}"` : phrase(f));

function valueText(f: TaxFactDef, v: unknown): string {
  if (typeof v === 'boolean') return v ? 'yes' : 'no';
  if (typeof v === 'number') {
    const unit = f.unit ? (f.unit === '%' ? '%' : ` ${f.unit}`) : '';
    return `${v}${unit}`.trim();
  }
  if (typeof v === 'string') return f.choices?.find((c) => c.value === v)?.label ?? v;
  return String(v);
}

const INVERSE: Record<string, string> = { isTrue: 'isFalse', isFalse: 'isTrue', eq: 'ne', in: 'notin', gt: 'lte', gte: 'lt', lt: 'gte', lte: 'gt' };

function leaf(m: NonNullable<Expr>, facts: Map<string, TaxFactDef>, negate: boolean): string {
  const id = m.fact ?? '';
  const f = facts.get(id) ?? { id, label: humanizeCode(id), kind: '', owner: '' };
  let op = m.op ?? '';
  if (negate && INVERSE[op]) op = INVERSE[op]!;
  const val = valueText(f, m.value);
  switch (op) {
    case 'isTrue':
      return `${subject(f)} is yes`;
    case 'isFalse':
      return `${subject(f)} is no`;
    case 'eq':
      return `${subject(f)} is ${val}`;
    case 'ne':
      return `${subject(f)} is not ${val}`;
    case 'in':
    case 'notin':
      return `${subject(f)} ${op === 'notin' ? 'is none of' : 'is one of'} ${(m.values ?? []).map((v) => valueText(f, v)).join(', ')}`;
    case 'gt':
      return `${subject(f)} is more than ${val}`;
    case 'gte':
      return `${subject(f)} is at least ${val}`;
    case 'lt':
      return `${subject(f)} is less than ${val}`;
    case 'lte':
      return `${subject(f)} is at most ${val}`;
    default:
      return `${subject(f)} ${op} ${val}`;
  }
}

function join(list: Expr[] | undefined, facts: Map<string, TaxFactDef>, word: string, parent: string): string {
  const parts = (list ?? []).filter((x): x is NonNullable<Expr> => !!x).map((x) => node(x, facts, word));
  if (parts.length === 0) return '';
  if (parts.length === 1) return parts[0]!;
  const text = parts.join(` ${word} `);
  return parent && parent !== word ? `(${text})` : text;
}

function node(m: NonNullable<Expr>, facts: Map<string, TaxFactDef>, parent: string): string {
  if (m.all) return join(m.all, facts, 'and', parent);
  if (m.any) return join(m.any, facts, 'or', parent);
  if (m.not) return m.not.op ? leaf(m.not, facts, true) : `not (${node(m.not, facts, '')})`;
  return leaf(m, facts, false);
}

export function describeCondition(expr: Expr, facts: Map<string, TaxFactDef>): string {
  return expr && Object.keys(expr).length ? node(expr, facts, '') : '';
}

export function describeRow(row: { when?: Expr; outcome: string }, facts: Map<string, TaxFactDef>): string {
  const cond = describeCondition(row.when, facts);
  const then = (OUTCOME[row.outcome] ?? humanizeCode(row.outcome)).toLowerCase().replace(/ \(.*$/, '');
  return cond ? `If ${cond}, then ${then}` : `Always, then ${then}`;
}

export function requiredText(req: { required?: boolean; requiredWhen?: Expr }, facts: Map<string, TaxFactDef>): string {
  if (!req.required) return 'Optional';
  const cond = describeCondition(req.requiredWhen, facts);
  return cond ? `Needed when ${cond}` : 'Always needed';
}

export function outcomeName(internal: string): string {
  return humanizeCode(internal.slice(internal.lastIndexOf('.') + 1));
}
