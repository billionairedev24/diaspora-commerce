import { type AttributeDef, type CategoryTaxStatus, type FactExpression, type FactValue } from './schemas/seller';

/* The rules of the category tax questions, the same as the catalog's FactExpressions and TaxFactRules (shared test vectors:
   docs/engineering/fact-expression-vectors.json): what a question's answer must look like, which questions are asked
   (showWhen) and which are needed (requiredWhen). A missing answer makes every condition false, so nothing is decided
   over an answer that was not given. */

type Facts = Record<string, FactValue | undefined>;

const numberOf = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);

/** Evaluates a show-when / required-when expression; null or undefined is always true. */
export function evaluateFact(expr: FactExpression | null | undefined, facts: Facts): boolean {
  if (!expr) return true;
  if ('all' in expr) return expr.all.every((e) => evaluateFact(e, facts));
  if ('any' in expr) return expr.any.some((e) => evaluateFact(e, facts));
  if ('not' in expr) return !evaluateFact(expr.not, facts);
  const v = facts[expr.fact];
  if (expr.op === 'isTrue') return v === true;
  if (expr.op === 'isFalse') return v === false;
  if (v === undefined || v === null) return false;
  if (expr.op === 'eq') return v === expr.value;
  if (expr.op === 'in') return (expr.values ?? []).includes(v);
  const n = numberOf(v);
  if (n === null) return false;
  const ref = numberOf(expr.value) ?? 0;
  return { gt: n > ref, gte: n >= ref, lt: n < ref, lte: n <= ref }[expr.op] ?? false;
}

/** The questions of a product type that sellers answer (plain-word tax questions). */
export const taxQuestionsOf = (attributes: readonly AttributeDef[]): AttributeDef[] => attributes.filter((a) => a.taxFact);

export interface FactCheck {
  /** The visible, typed answers. */
  facts: Record<string, FactValue>;
  /** Question key to what is wrong with the answer given (plain words, a message key suffix with its label). */
  problems: Record<string, 'yesNo' | 'number' | 'whole' | 'percent' | 'choice'>;
  /** Shown, required questions without an answer (evidence files are listed too; they may follow the upload). */
  missing: string[];
}

/** One typed answer from what the form holds (strings): 'yes'/'no', a number, a choice value; null = not answered or not valid. */
function coerce(def: AttributeDef, raw: string, problems: FactCheck['problems']): FactValue | null {
  const text = raw.trim();
  if (!text) return null;
  switch (def.kind) {
    case 'BOOLEAN': {
      const t = text.toLowerCase();
      if (['yes', 'true', 'y', '1'].includes(t)) return true;
      if (['no', 'false', 'n', '0'].includes(t)) return false;
      problems[def.key] = 'yesNo';
      return null;
    }
    case 'DECIMAL':
    case 'INTEGER':
    case 'PERCENT': {
      const n = Number(text.replace(',', '.'));
      if (!Number.isFinite(n)) { problems[def.key] = 'number'; return null; }
      if (def.kind === 'PERCENT') {
        if (n < 0 || n > 100) { problems[def.key] = 'percent'; return null; }
        return n;
      }
      if (n <= 0 || n > 1_000_000) { problems[def.key] = 'number'; return null; }
      if (def.kind === 'INTEGER' && !Number.isInteger(n)) { problems[def.key] = 'whole'; return null; }
      return n;
    }
    case 'ENUM':
      if (def.options.includes(text)) return text;
      problems[def.key] = 'choice';
      return null;
    case 'FILE':
      return text;
    default:
      return null;
  }
}

/** Checks answers (raw strings by question key) against a product type's tax questions. */
export function checkFacts(defs: readonly AttributeDef[], raw: Record<string, string | undefined>): FactCheck {
  const facts: Record<string, FactValue> = {};
  const problems: FactCheck['problems'] = {};
  for (const d of defs) {
    const v = coerce(d, raw[d.key] ?? '', problems);
    if (v !== null) facts[d.key] = v;
  }
  // Drop the answers of questions that are not asked, until nothing depends on a dropped one.
  for (let round = 0, changed = true; changed && round <= defs.length; round++) {
    changed = false;
    for (const d of defs) {
      if (!evaluateFact(d.showWhen, facts) && (d.key in facts || d.key in problems)) {
        delete facts[d.key];
        delete problems[d.key];
        changed = true;
      }
    }
  }
  const missing = defs.filter((d) => d.required && !(d.key in facts) && !(d.key in problems) && evaluateFact(d.requiredWhen, facts)).map((d) => d.key);
  return { facts, problems, missing };
}

/** The plain message of a missing question, as the catalog words it ("Net weight (oz) is needed. The net weight ..."). */
export const missingMessage = (d: AttributeDef): string => {
  const what = d.kind === 'FILE' ? `Please upload: ${d.label}.` : `${d.label} is needed.`;
  return d.help ? `${what} ${d.help}` : what;
};

/** Gates where no seller action helps: the product stays a draft and cannot be submitted. */
export const CLOSED_CATEGORY_STATUSES: readonly CategoryTaxStatus[] = ['NEEDS_APPROVAL', 'BLOCKED_BUNDLE', 'CONFLICT_OPEN'];
export const categoryClosed = (status: CategoryTaxStatus | null | undefined): boolean => status != null && CLOSED_CATEGORY_STATUSES.includes(status);
