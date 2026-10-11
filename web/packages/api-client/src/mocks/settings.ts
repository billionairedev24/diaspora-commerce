/* Mock BFF for the account settings routes (profile, sign-in & security, preferences, alerts, memberships) shared by
   the storefront, Seller Center and staff console mocks. The authenticator app works for real: the mock issues a
   random base32 secret and accepts the RFC 6238 code (HMAC-SHA1, 6 digits, 30 s) an authenticator computes from it,
   so Playwright journeys can enrol with the setup key exactly as a person would. */
import { http, type HttpHandler, HttpResponse, type JsonBodyType } from 'msw';

import { bffProblemType } from '../problem-types';
import {
  type AccountMembership,
  type AccountPreferences,
  type AccountProfile,
  type AccountSecurity,
  ALERT_TOPICS,
  type AlertPreference,
  type AlertTopic,
  type QuietHours,
  type SignInEvent
} from '../schemas/settings';
import { type NotificationPreferences } from '../schemas/shopper';

export interface SettingsMockPerson {
  id: string;
  firstName: string;
  lastName: string;
  email: string;
}

export interface SettingsMockConfig {
  kind: 'customer' | 'seller' | 'staff';
  /** The signed-in person, or null for a guest (401). */
  person: (request: Request) => SettingsMockPerson | null;
  memberships?: (request: Request) => AccountMembership[];
  roles?: (request: Request) => Array<{ key: string; label: string }>;
  /** Removes the person from an organisation (non-owners). */
  leave?: (orgId: string) => void;
  /** Checks and changes the person's password (accepts any non-empty current password when absent). */
  password?: { check: (request: Request, current: string) => boolean; set: (request: Request, next: string) => void };
}

interface SettingsState {
  profile: Pick<AccountProfile, 'firstName' | 'lastName' | 'phone' | 'phoneVerified' | 'jobTitle'>;
  security: Omit<AccountSecurity, 'passkeyRegistrationUrl'>;
  pendingTotp: string | null;
  pendingSms: boolean;
  pendingPhone: boolean;
  activity: SignInEvent[];
  notifications: NotificationPreferences;
  preferences: AccountPreferences;
  alerts: Map<string, boolean>;
  quiet: QuietHours;
}

/** Codes the mock accepts for SMS (and as a fallback for the authenticator in unit tests). */
export const SETTINGS_DEMO_CODE = '246810';

const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

function randomSecret(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(20));
  let bits = '';
  bytes.forEach((b) => { bits += b.toString(2).padStart(8, '0'); });
  let out = '';
  for (let i = 0; i + 5 <= bits.length; i += 5) out += B32[parseInt(bits.slice(i, i + 5), 2)];
  return out;
}

function base32Decode(secret: string): Uint8Array<ArrayBuffer> {
  let bits = '';
  for (const ch of secret.replace(/=+$/, '').toUpperCase()) {
    const v = B32.indexOf(ch);
    if (v >= 0) bits += v.toString(2).padStart(5, '0');
  }
  const out = new Uint8Array(new ArrayBuffer(Math.floor(bits.length / 8)));
  for (let i = 0; i < out.length; i++) out[i] = parseInt(bits.slice(i * 8, i * 8 + 8), 2);
  return out;
}

/** RFC 6238 TOTP (HMAC-SHA1, 6 digits) for a 30-second step. */
export async function totpAt(secret: string, step: number): Promise<string> {
  const key = await crypto.subtle.importKey('raw', base32Decode(secret), { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']);
  const msg = new ArrayBuffer(8);
  new DataView(msg).setBigUint64(0, BigInt(step));
  const mac = new Uint8Array(await crypto.subtle.sign('HMAC', key, msg));
  const offset = (mac[mac.length - 1] ?? 0) & 0x0f;
  const bin = (((mac[offset] ?? 0) & 0x7f) << 24) | ((mac[offset + 1] ?? 0) << 16) | ((mac[offset + 2] ?? 0) << 8) | (mac[offset + 3] ?? 0);
  return String(bin % 1_000_000).padStart(6, '0');
}

async function totpMatches(secret: string, code: string, now = Date.now()): Promise<boolean> {
  const step = Math.floor(now / 30_000);
  for (const s of [step - 1, step, step + 1]) if ((await totpAt(secret, s)) === code) return true;
  return false;
}

const iso = (d: Date) => d.toISOString();
const ago = (ms: number) => iso(new Date(Date.now() - ms));
const HOUR = 3_600_000;
const DAY = 24 * HOUR;

const problem = (status: number, code: string, title: string, detail: string, errors?: Record<string, string>) =>
  HttpResponse.json({ type: bffProblemType(code), title, status, detail, code, ...(errors ? { errors } : {}) } as JsonBodyType, {
    status,
    headers: { 'Content-Type': 'application/problem+json' }
  });
const ok = (data: unknown, status = 200) => HttpResponse.json(data as JsonBodyType, { status });
const none = () => new HttpResponse(null, { status: 204 });

async function read<T>(request: Request): Promise<Partial<T>> {
  try {
    const t = await request.text();
    return t ? (JSON.parse(t) as Partial<T>) : {};
  } catch {
    return {};
  }
}

function seed(kind: SettingsMockConfig['kind']): SettingsState {
  const staff = kind === 'staff';
  return {
    profile: { firstName: '', lastName: '', phone: '+17135550142', phoneVerified: true, jobTitle: kind === 'customer' ? null : staff ? 'Trust & Safety lead' : 'Store manager' },
    security: {
      // The demo shopper already uses both methods (as the storefront mock always has); staff start with SMS only.
      mfa: kind === 'customer'
        ? { totp: true, sms: true, smsPhone: '•••• 0142', recoveryCodesLeft: 8, required: true, mayDisable: true, mayRemoveTotp: true, mayRemoveSms: true }
        : { totp: false, sms: staff, smsPhone: staff ? '•••• 0142' : null, recoveryCodesLeft: staff ? 10 : 0, required: staff, mayDisable: !staff, mayRemoveTotp: false, mayRemoveSms: !staff },
      passkeys: [{ credentialId: 'cred-macbook', label: 'MacBook Pro · Touch ID', createdAt: ago(40 * DAY), lastUsedAt: ago(2 * DAY) }],
      trustedBrowsers: [{ id: 'tb-1', label: 'Chrome on macOS', createdAt: ago(3 * DAY), expiresAt: iso(new Date(Date.now() + 27 * DAY)) }],
      sessions: [
        { id: 'sess-web', clientName: staff ? 'Staff console · this browser' : kind === 'seller' ? 'Seller Center · this browser' : 'AfroTransact web · this browser', signedInAt: ago(HOUR), lastActiveAt: iso(new Date()), current: true },
        { id: 'sess-app', clientName: kind === 'customer' ? 'AfroTransact app · iPhone' : 'AfroTransact web · Safari on iOS', signedInAt: ago(9 * DAY), lastActiveAt: ago(DAY), current: false }
      ],
      devices: kind === 'customer' ? [{ id: 'dev-1', platform: 'ios', deviceName: 'iPhone 15', lastSeenAt: ago(DAY) }] : [],
      linkedAccounts: { accounts: kind === 'customer' ? [{ provider: 'google', email: 'adaeze.nwosu@gmail.com', canUnlink: true }] : [], hasPassword: true }
    },
    pendingTotp: null,
    pendingSms: false,
    pendingPhone: false,
    activity: [
      { at: ago(HOUR), method: staff ? 'MFA_SMS' : 'PASSWORD', outcome: 'SUCCEEDED', app: 'AfroTransact on the web', device: 'Chrome on macOS', ip: '203.0.113.7' },
      { at: ago(HOUR + 60_000), method: 'PASSWORD', outcome: staff ? 'MFA_REQUIRED' : 'FAILED', app: 'AfroTransact on the web', device: 'Chrome on macOS', ip: '203.0.113.7' },
      { at: ago(DAY), method: 'PASSKEY', outcome: 'SUCCEEDED', app: 'AfroTransact on the web', device: 'Safari on iOS', ip: '198.51.100.24' }
    ],
    notifications: { email: true, sms: true, push: true, marketingOptIn: false },
    preferences: { locale: null, timeZone: null, dateFormat: 'AUTO', numberFormat: 'AUTO', tableDensity: 'COMFORTABLE' },
    alerts: new Map(),
    quiet: { enabled: false, start: '21:00', end: '07:00' }
  };
}

const LOCKED: Partial<Record<AlertTopic, boolean>> = { 'seller.compliance': true, 'seller.claims': true, 'staff.escalations': true };

function recompute(s: SettingsState, staff: boolean) {
  const m = s.security.mfa;
  m.required = staff || m.totp || m.sms;
  m.mayDisable = !staff;
  m.mayRemoveTotp = m.totp && (!staff || m.sms);
  m.mayRemoveSms = m.sms && (!staff || m.totp);
}

/** MSW handlers for /v1/account/{profile,security,…}; state is per person and lives for the process. */
export function settingsHandlers(cfg: SettingsMockConfig): HttpHandler[] {
  const states = new Map<string, SettingsState>();
  const staff = cfg.kind === 'staff';
  const audience = staff ? 'staff' : 'seller';

  type Resolver = (args: { request: Request; params: Record<string, string | readonly string[] | undefined> }) => Promise<Response> | Response;
  const authed = (fn: (s: SettingsState, p: SettingsMockPerson, request: Request, params: Record<string, string>) => Promise<Response> | Response): Resolver =>
    async ({ request, params }) => {
      const person = cfg.person(request);
      if (!person) return problem(401, 'UNAUTHENTICATED', 'Sign in required', 'Sign in to continue.');
      let s = states.get(person.id);
      if (!s) {
        s = seed(cfg.kind);
        s.profile.firstName = person.firstName;
        s.profile.lastName = person.lastName;
        states.set(person.id, s);
      }
      const flat: Record<string, string> = {};
      Object.entries(params).forEach(([k, v]) => { if (typeof v === 'string') flat[k] = v; });
      return fn(s, person, request, flat);
    };

  const profile = (s: SettingsState, p: SettingsMockPerson, request: Request): AccountProfile => ({
    id: p.id,
    kind: cfg.kind,
    firstName: s.profile.firstName,
    lastName: s.profile.lastName,
    email: p.email,
    emailVerified: true,
    emailManaged: staff && p.email.endsWith('@afrotransact.com'),
    initials: `${s.profile.firstName.charAt(0)}${s.profile.lastName.charAt(0)}`.toUpperCase(),
    phone: s.profile.phone,
    phoneVerified: s.profile.phoneVerified,
    jobTitle: cfg.kind === 'customer' ? null : s.profile.jobTitle,
    memberships: cfg.memberships?.(request) ?? [],
    roles: cfg.roles?.(request) ?? []
  });

  const alerts = (s: SettingsState) => ({
    audience,
    preferences: ALERT_TOPICS.filter((t) => t.startsWith(`${audience}.`)).flatMap((topic) =>
      (['email', 'sms'] as const).map((channel): AlertPreference => {
        const locked = channel === 'email' && Boolean(LOCKED[topic]);
        return { topic, channel, enabled: locked || (s.alerts.get(`${topic}/${channel}`) ?? true), locked };
      })
    ),
    quietHours: s.quiet
  });

  const codeOf = async (request: Request) => String((await read<{ code: string }>(request)).code ?? '').trim();
  const badCode = () => problem(422, 'INVALID_CODE', 'Incorrect code', 'That code is incorrect or expired.', { code: 'That code is incorrect or expired.' });

  return [
    /* Profile */
    http.get('*/v1/account/profile', authed((s, p, r) => ok(profile(s, p, r)))),
    http.patch('*/v1/account/profile', authed(async (s, p, r) => {
      const b = await read<{ firstName: string; lastName: string; phone: string; jobTitle: string | null }>(r);
      const errors: Record<string, string> = {};
      if (b.firstName !== undefined && !b.firstName.trim()) errors.firstName = 'Use 1 to 80 characters';
      if (b.lastName !== undefined && !b.lastName.trim()) errors.lastName = 'Use 1 to 80 characters';
      if (b.phone !== undefined && b.phone.replace(/\D/g, '').length < 10) errors.phone = 'Enter a valid mobile number';
      if (b.jobTitle !== undefined && cfg.kind === 'customer') errors.jobTitle = 'Job titles are for Seller Center and staff accounts';
      if (Object.keys(errors).length) return problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
      if (b.firstName !== undefined) s.profile.firstName = b.firstName.trim();
      if (b.lastName !== undefined) s.profile.lastName = b.lastName.trim();
      if (b.phone !== undefined && b.phone !== s.profile.phone) {
        s.profile.phone = b.phone;
        s.profile.phoneVerified = false;
      }
      if (b.jobTitle !== undefined) s.profile.jobTitle = b.jobTitle?.trim() || null;
      return ok(profile(s, p, r));
    })),
    http.post('*/v1/account/phone/verification', authed((s) => {
      if (s.profile.phoneVerified) return problem(409, 'CONFLICT', 'Already verified', 'This number is already verified.');
      s.pendingPhone = true;
      return ok({ sentTo: `•••• ${(s.profile.phone ?? '').slice(-4)}`, expiresAt: iso(new Date(Date.now() + 600_000)) }, 202);
    })),
    http.post('*/v1/account/phone/verification/confirm', authed(async (s, _p, r) => {
      if (!s.pendingPhone || (await codeOf(r)) !== SETTINGS_DEMO_CODE) return badCode();
      s.pendingPhone = false;
      s.profile.phoneVerified = true;
      return ok({ phoneVerified: true, verifiedAt: iso(new Date()) });
    })),
    http.post('*/v1/account/orgs/:orgId/leave', authed((_s, _p, r, params) => {
      const m = (cfg.memberships?.(r) ?? []).find((x) => x.orgId === params.orgId);
      if (!m) return problem(404, 'NOT_FOUND', 'Not found', 'You’re not a member of that organisation.');
      if (m.owner) return problem(409, 'CONFLICT', 'Owners can’t leave', 'Make someone else an owner first.');
      cfg.leave?.(m.orgId);
      return none();
    })),

    /* Sign-in & security */
    http.get('*/v1/account/security', authed((s) => ok({ ...s.security, passkeyRegistrationUrl: 'http://localhost:8100/webauthn/register' }))),
    http.put('*/v1/account/password', authed(async (_s, _p, r) => {
      const b = await read<{ currentPassword: string; newPassword: string }>(r);
      if ((b.newPassword ?? '').length < 12) return problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Use at least 12 characters.', { newPassword: 'Use at least 12 characters' });
      const current = b.currentPassword ?? '';
      if (!current || (cfg.password && !cfg.password.check(r, current))) {
        return problem(422, 'VALIDATION_FAILED', 'Check your password', 'Your current password is incorrect.', { currentPassword: 'Your current password is incorrect.' });
      }
      cfg.password?.set(r, b.newPassword ?? '');
      return none();
    })),
    http.post('*/v1/account/mfa/totp', authed((s, p) => {
      s.pendingTotp = randomSecret();
      const label = `AfroTransact:${encodeURIComponent(p.email)}`;
      return ok({ secret: s.pendingTotp, otpauthUri: `otpauth://totp/${label}?secret=${s.pendingTotp}&issuer=AfroTransact&algorithm=SHA1&digits=6&period=30` });
    })),
    http.post('*/v1/account/mfa/totp/confirm', authed(async (s, _p, r) => {
      const code = await codeOf(r);
      if (!/^\d{6}$/.test(code)) return problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Enter the 6-digit code', { code: 'Enter the 6-digit code' });
      if (!s.pendingTotp || !(await totpMatches(s.pendingTotp, code))) return badCode();
      const m = s.security.mfa;
      const first = m.recoveryCodesLeft === 0;
      m.totp = true;
      s.pendingTotp = null;
      if (first) m.recoveryCodesLeft = 10;
      recompute(s, staff);
      return ok({ recoveryCodes: first ? ['k7q2m-9xdpw', 'p4wz3-hnac8', 'c8rty-v6eb2', 'l2jfq-k7bm4', 'x9tn4-r2dq7', 'm3hv8-z5kcw', 'b6pw2-n8tjx', 'f4yq7-c2mrh', 'd9kt3-w6zbp', 'h2nx5-q8vfa'] : [] });
    })),
    http.delete('*/v1/account/mfa/totp', authed((s) => {
      if (!s.security.mfa.mayRemoveTotp) return problem(403, 'FORBIDDEN', 'Two-step verification is required', 'Two-step verification is mandatory for staff accounts: turn on another method first.');
      s.security.mfa.totp = false;
      recompute(s, staff);
      return none();
    })),
    http.post('*/v1/account/mfa/sms', authed((s) => {
      if (!s.profile.phone) return problem(422, 'VALIDATION_FAILED', 'Add a mobile number', 'Add a mobile number to your profile first.', { phone: 'Add a mobile number first' });
      s.pendingSms = true;
      return ok({ codeExpiresAt: iso(new Date(Date.now() + 600_000)) }, 202);
    })),
    http.post('*/v1/account/mfa/sms/confirm', authed(async (s, _p, r) => {
      if (!s.pendingSms || (await codeOf(r)) !== SETTINGS_DEMO_CODE) return badCode();
      const m = s.security.mfa;
      const first = m.recoveryCodesLeft === 0;
      m.sms = true;
      m.smsPhone = `•••• ${(s.profile.phone ?? '').slice(-4)}`;
      s.pendingSms = false;
      if (first) m.recoveryCodesLeft = 10;
      recompute(s, staff);
      return ok({ recoveryCodes: first ? ['a2b3c-d4e5f', 'g6h7j-k8m9n', 'p2q3r-s4t5u', 'v6w7x-y8z2a', 'b3c4d-e5f6g', 'h7j8k-m9n2p', 'q3r4s-t5u6v', 'w7x8y-z9a2b', 'c3d4e-f5g6h', 'j7k8m-n9p2q'] : [] });
    })),
    http.delete('*/v1/account/mfa/sms', authed((s) => {
      if (!s.security.mfa.mayRemoveSms) return problem(403, 'FORBIDDEN', 'Two-step verification is required', 'Two-step verification is mandatory for staff accounts: turn on another method first.');
      s.security.mfa.sms = false;
      s.security.mfa.smsPhone = null;
      recompute(s, staff);
      return none();
    })),
    http.post('*/v1/account/mfa/recovery-codes', authed((s) => {
      s.security.mfa.recoveryCodesLeft = 10;
      return ok({ recoveryCodes: ['r4t7y-u2i9o', 'p3a6s-d8f2g', 'h5j7k-l9z2x', 'c4v6b-n8m2q', 'w3e5r-t7y9u', 'i2o4p-a6s8d', 'f3g5h-j7k9l', 'z2x4c-v6b8n', 'm3q5w-e7r9t', 'y2u4i-o6p8a'] });
    })),
    http.patch('*/v1/account/passkeys/:id', authed(async (s, _p, r, params) => {
      const label = String((await read<{ label: string }>(r)).label ?? '').trim();
      const pk = s.security.passkeys.find((x) => x.credentialId === params.id);
      if (!pk) return problem(404, 'NOT_FOUND', 'Not found', 'We couldn’t find that passkey.');
      if (!label || label.length > 64) return problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Use 1 to 64 characters', { label: 'Use 1 to 64 characters' });
      pk.label = label;
      return ok(pk);
    })),
    http.delete('*/v1/account/passkeys/:id', authed((s, _p, _r, params) => {
      s.security.passkeys = s.security.passkeys.filter((x) => x.credentialId !== params.id);
      return none();
    })),
    http.delete('*/v1/account/trusted-browsers', authed((s) => {
      s.security.trustedBrowsers = [];
      return none();
    })),
    http.delete('*/v1/account/trusted-browsers/:id', authed((s, _p, _r, params) => {
      if (!s.security.trustedBrowsers.some((b) => b.id === params.id)) return problem(404, 'NOT_FOUND', 'Not found', 'That browser is no longer trusted.');
      s.security.trustedBrowsers = s.security.trustedBrowsers.filter((b) => b.id !== params.id);
      return none();
    })),
    http.delete('*/v1/account/sessions/:id', authed((s, _p, _r, params) => {
      s.security.sessions = s.security.sessions.filter((x) => x.id !== params.id || x.current);
      return none();
    })),
    http.post('*/v1/account/sessions/revoke-others', authed((s) => {
      const before = s.security.sessions.length;
      s.security.sessions = s.security.sessions.filter((x) => x.current);
      return ok({ revoked: before - s.security.sessions.length });
    })),
    http.delete('*/v1/account/devices/:id', authed((s, _p, _r, params) => {
      s.security.devices = s.security.devices.filter((d) => d.id !== params.id);
      return none();
    })),
    http.delete('*/v1/account/linked-accounts/:provider', authed((s, _p, _r, params) => {
      const l = s.security.linkedAccounts;
      l.accounts = l.accounts.filter((a) => a.provider !== params.provider);
      return ok(l);
    })),
    http.get('*/v1/account/sign-in-activity', authed((s) => ok({ items: s.activity }))),

    /* Notifications */
    http.get('*/v1/account/notification-preferences', authed((s) => ok(s.notifications))),
    http.put('*/v1/account/notification-preferences', authed(async (s, _p, r) => {
      s.notifications = { ...s.notifications, ...(await read<NotificationPreferences>(r)) };
      return ok(s.notifications);
    })),
    http.get('*/v1/account/alerts', authed((s) => (cfg.kind === 'customer' ? problem(403, 'FORBIDDEN', 'Forbidden', 'Alerts are for Seller Center and staff accounts.') : ok(alerts(s))))),
    http.put('*/v1/account/alerts', authed(async (s, _p, r) => {
      if (cfg.kind === 'customer') return problem(403, 'FORBIDDEN', 'Forbidden', 'Alerts are for Seller Center and staff accounts.');
      const b = await read<{ preferences: Array<{ topic: AlertTopic; channel: 'email' | 'sms'; enabled: boolean }>; quietHours: QuietHours }>(r);
      for (const p of b.preferences ?? []) {
        if (!p.topic.startsWith(`${audience}.`)) return problem(422, 'VALIDATION_FAILED', 'Validation failed', `Unknown alert ${p.topic}`, { preferences: `Unknown alert ${p.topic}` });
        if (!p.enabled && p.channel === 'email' && LOCKED[p.topic]) return problem(422, 'VALIDATION_FAILED', 'Validation failed', `${p.topic} emails can’t be turned off`, { preferences: 'This alert stays on' });
        s.alerts.set(`${p.topic}/${p.channel}`, p.enabled);
      }
      if (b.quietHours) s.quiet = b.quietHours;
      return ok(alerts(s));
    })),

    /* Preferences */
    http.get('*/v1/account/preferences', authed((s) => ok(s.preferences))),
    http.put('*/v1/account/preferences', authed(async (s, _p, r) => {
      const b = await read<AccountPreferences>(r);
      if (b.timeZone) {
        try {
          new Intl.DateTimeFormat('en', { timeZone: b.timeZone });
        } catch {
          return problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Choose a time zone', { timeZone: 'Use an IANA time zone such as America/Chicago' });
        }
      }
      s.preferences = { ...s.preferences, ...b } as AccountPreferences;
      return ok(s.preferences);
    }))
  ];
}
