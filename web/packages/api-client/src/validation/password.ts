/** Password rules shared by sign-up forms (web, mobile) and the identity service. */
export interface PasswordContext {
  firstName?: string;
  lastName?: string;
  email?: string;
  /** When provided, a "Passwords match" rule is included. */
  confirm?: string;
}

export interface PasswordRule {
  id: 'length' | 'case' | 'number' | 'personal' | 'match';
  label: string;
  ok: boolean;
}

export const PASSWORD_MIN_LENGTH = 12;

const personalParts = (ctx: PasswordContext): string[] =>
  [ctx.firstName, ctx.lastName, ctx.email?.split('@')[0]]
    .map((s) => (s ?? '').trim().toLowerCase())
    .filter((s) => s.length >= 3);

export function evaluatePassword(password: string, ctx: PasswordContext = {}): PasswordRule[] {
  const lower = password.toLowerCase();
  const rules: PasswordRule[] = [
    { id: 'length', label: `${PASSWORD_MIN_LENGTH}+ characters`, ok: password.length >= PASSWORD_MIN_LENGTH },
    { id: 'case', label: 'Upper and lower case', ok: /[A-Z]/.test(password) && /[a-z]/.test(password) },
    { id: 'number', label: 'At least one number', ok: /\d/.test(password) },
    { id: 'personal', label: 'Not your name or email', ok: password.length > 0 && !personalParts(ctx).some((p) => lower.includes(p)) }
  ];
  if (ctx.confirm !== undefined) rules.push({ id: 'match', label: 'Passwords match', ok: password.length > 0 && password === ctx.confirm });
  return rules;
}

export function isPasswordAcceptable(password: string, ctx: PasswordContext = {}): boolean {
  return evaluatePassword(password, ctx).every((r) => r.ok);
}
