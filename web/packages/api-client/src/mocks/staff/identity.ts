/* Identity & access (S291) in the mock staff BFF: OAuth clients, sessions and tokens, signing keys, authentication
   settings, and the token inspector. They mirror internal/app/staff_resources_identity.go and staff_identity.go of the Go
   BFF. Every change needs a reason; a secret is shown once (ActionCtx.showOnce) and is never kept in a record. */
import { type FieldValue, type FormField, type IdentityDocuments, type IdentityTokenInspection, type StaffForm } from '../../schemas/staff';
import { type MockRecord, type Scalar } from './db';
import { type MockAction, type MockResource } from './resources';

const MINUTE = 60_000;
const DAY = 86_400_000;

export const IDENTITY_ADMIN = 'identity:admin';
const REASON_PATTERN = '^[\\s\\S]{10,500}$';
const REASON_HELP = 'Give a reason of 10 to 500 characters';

const col = (key: string, label: string, kind: 'text' | 'strong' | 'mono' | 'number' | 'datetime' | 'status', width: number) => ({ key, label, kind, width });
const field = (key: string, label: string, type: FormField['type'], required: boolean, extra: Partial<FormField> = {}): FormField => ({ key, label, type, required, ...extra });
const reasonField = (): FormField =>
  field('reason', 'Reason (10 to 500 characters, kept in the access log)', 'textarea', true, { maxLength: 500, pattern: REASON_PATTERN, help: REASON_HELP });
const form = (title: string, submitLabel: string, fields: FormField[], intro?: string): StaffForm => ({ title, submitLabel, fields, ...(intro ? { intro } : {}) });
const str = (v: FieldValue | Scalar | undefined): string => (v === null || v === undefined ? '' : String(v));
const num = (v: FieldValue | Scalar | undefined): number => (typeof v === 'number' ? v : Number(v ?? 0));
const lines = (v: FieldValue | undefined): string[] =>
  str(Array.isArray(v) ? v.join('\n') : v)
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean);
const list = (csv: Scalar | undefined): string[] => str(csv).split(',').map((s) => s.trim()).filter(Boolean);
const log = (r: MockRecord, actor: string, now: Date, text: string) => r.activity.unshift({ at: now.toISOString(), actor, text });

const GRANTS = [
  { value: 'authorization_code', label: 'Authorization code' },
  { value: 'refresh_token', label: 'Refresh token' },
  { value: 'client_credentials', label: 'Client credentials' }
];

/* ── seeds ── */

export function seedIdentityAdmin(now: Date): Record<'oauth-clients' | 'identity-sessions' | 'signing-keys' | 'auth-settings', MockRecord[]> {
  const ago = (ms: number) => new Date(now.getTime() - ms).toISOString();
  const client = (
    clientId: string,
    name: string,
    type: 'confidential' | 'public',
    grants: string[],
    scopes: string[],
    redirects: string[],
    extra: Partial<Record<string, Scalar>> = {}
  ): MockRecord => ({
    id: clientId,
    fields: {
      clientId,
      name,
      type,
      grantTypes: grants.join(', '),
      scopes: scopes.join(', '),
      redirectUris: redirects.join('\n'),
      postLogoutRedirectUris: '',
      requirePkce: type === 'public' || grants.includes('authorization_code'),
      requireConsent: false,
      publicRefreshTokens: type === 'public' && grants.includes('refresh_token'),
      accessTokenTtlMinutes: 5,
      refreshTokenTtlMinutes: 43_200,
      enabled: true,
      status: 'ENABLED',
      platformClient: false,
      issuedAt: ago(200 * DAY),
      secretRotatedAt: null,
      previousSecretExpiresAt: null,
      ...extra
    },
    activity: []
  });
  const clients: MockRecord[] = [
    client('gateway-bff', 'Gateway BFF', 'confidential', ['authorization_code', 'client_credentials', 'refresh_token'], ['openid', 'profile', 'email', 'phone', 'offline_access'], ['https://app.afrotransact.com/api/bff/v1/auth/callback'], { platformClient: true }),
    client('customer-app', 'Customer app', 'public', ['authorization_code', 'refresh_token'], ['openid', 'profile', 'email', 'phone', 'offline_access'], ['afrotransact://auth/callback']),
    client('driver-app', 'Driver app', 'public', ['authorization_code', 'refresh_token'], ['openid', 'profile', 'offline_access'], ['afrotransact-driver://auth/callback']),
    client('inventory-service', 'Inventory service account', 'confidential', ['client_credentials'], ['inventory.read', 'inventory.write'], [], { secretRotatedAt: ago(40 * DAY) }),
    client('media-service', 'Media service account', 'confidential', ['client_credentials'], ['media.read'], [], { enabled: false, status: 'DISABLED' })
  ];

  const session = (id: string, userName: string | null, email: string | null, clientId: string, clientName: string, ip: string | null, signedMin: number, usedMin: number, refreshable: boolean): MockRecord => ({
    id,
    fields: {
      userName,
      userEmail: email,
      userId: userName ? `user-${id}` : clientId,
      clientId,
      clientName,
      ipAddress: ip,
      signedInAt: ago(signedMin * MINUTE),
      lastUsedAt: ago(usedMin * MINUTE),
      expiresAt: new Date(now.getTime() + (refreshable ? 29 * DAY : 4 * MINUTE)).toISOString(),
      refreshable
    },
    activity: []
  });
  const sessions: MockRecord[] = [
    session('auth-1001', 'Amara Nwosu', 'amara@example.com', 'customer-app', 'Customer app', '203.0.113.24', 60 * 24 * 3, 12, true),
    session('auth-1002', 'Amara Nwosu', 'amara@example.com', 'gateway-bff', 'Gateway BFF', '203.0.113.24', 60 * 6, 3, true),
    session('auth-1003', 'Kwame Mensah', 'kwame@example.com', 'gateway-bff', 'Gateway BFF', '198.51.100.7', 60 * 30, 90, true),
    session('auth-1004', 'Fatou Diallo', 'fatou@example.com', 'driver-app', 'Driver app', null, 60 * 2, 1, true),
    session('auth-1005', 'Ifeoma Okafor', 'ifeoma@afrotransact.com', 'gateway-bff', 'Gateway BFF', '192.0.2.88', 45, 2, true),
    session('auth-1006', null, null, 'inventory-service', 'Inventory service account', null, 5, 1, false)
  ];

  const key = (kid: string, status: 'ACTIVE' | 'RETIRING' | 'RETIRED', createdDays: number, extra: Partial<Record<string, Scalar>> = {}): MockRecord => ({
    id: kid,
    fields: { kid, algorithm: 'RS256', status, createdAt: ago(createdDays * DAY), rotatedAt: null, retireAfter: null, retiredAt: null, ...extra },
    activity: []
  });
  const keys: MockRecord[] = [
    key('k20260915T090000-3fa1', 'ACTIVE', 39),
    key('k20260601T090000-b27c', 'RETIRING', 145, { rotatedAt: ago(39 * DAY), retireAfter: new Date(now.getTime() + 3 * MINUTE).toISOString() }),
    key('identity-1', 'RETIRED', 300, { rotatedAt: ago(145 * DAY), retireAfter: ago(145 * DAY - 7 * MINUTE), retiredAt: ago(145 * DAY - 8 * MINUTE) })
  ];

  const settings: MockRecord = {
    id: 'settings',
    fields: {
      setting: 'Sign-in, password and token settings',
      passwordMinLength: 12,
      mfaRequiredAccount: false,
      mfaRequiredSeller: false,
      mfaRequiredProvider: false,
      sessionIdleTimeoutMinutes: 30,
      sessionAbsoluteTimeoutMinutes: 1440,
      accessTokenTtlCapMinutes: null,
      refreshTokenTtlCapMinutes: null
    },
    activity: []
  };
  return { 'oauth-clients': clients, 'identity-sessions': sessions, 'signing-keys': keys, 'auth-settings': [settings] };
}

/* ── oauth-clients ── */

function clientFields(r: MockRecord | null): FormField[] {
  const f = r?.fields;
  return [
    field('name', 'Name', 'text', true, { maxLength: 120, value: f ? str(f.name) : '' }),
    field('grantTypes', 'Grant types', 'multiselect', true, { options: GRANTS, value: f ? list(f.grantTypes) : ['authorization_code', 'refresh_token'] }),
    field('scopes', 'Scopes (one per line)', 'textarea', false, { maxLength: 2000, value: f ? list(f.scopes).join('\n') : '' }),
    field('redirectUris', 'Redirect URIs (one per line)', 'textarea', false, { maxLength: 4000, value: f ? str(f.redirectUris) : '' }),
    field('postLogoutRedirectUris', 'Post-logout redirect URIs (one per line)', 'textarea', false, { maxLength: 4000, value: f ? str(f.postLogoutRedirectUris) : '' }),
    field('requireConsent', 'Ask the user for consent', 'toggle', false, { value: f ? f.requireConsent === true : false }),
    field('publicRefreshTokens', 'Refresh tokens for a public client (mobile apps)', 'toggle', false, { value: f ? f.publicRefreshTokens === true : false }),
    field('accessTokenTtlMinutes', 'Access token lifetime (minutes)', 'number', true, { min: 1, max: 60, value: f ? num(f.accessTokenTtlMinutes) : 5 }),
    field('refreshTokenTtlMinutes', 'Refresh token lifetime (minutes)', 'number', true, { min: 1, max: 129_600, value: f ? num(f.refreshTokenTtlMinutes) : 43_200 }),
    reasonField()
  ];
}

function applyClient(r: MockRecord, v: Record<string, FieldValue>) {
  Object.assign(r.fields, {
    name: str(v.name),
    grantTypes: (Array.isArray(v.grantTypes) ? v.grantTypes : []).join(', '),
    scopes: lines(v.scopes).join(', '),
    redirectUris: lines(v.redirectUris).join('\n'),
    postLogoutRedirectUris: lines(v.postLogoutRedirectUris).join('\n'),
    requireConsent: v.requireConsent === true,
    publicRefreshTokens: v.publicRefreshTokens === true && r.fields.type === 'public',
    accessTokenTtlMinutes: num(v.accessTokenTtlMinutes),
    refreshTokenTtlMinutes: num(v.refreshTokenTtlMinutes)
  });
}

const reasonOnly = (title: string, submit: string, intro?: string) => () => form(title, submit, [reasonField()], intro);
const randomSecret = () => `mock-${Math.random().toString(36).slice(2, 12)}${Math.random().toString(36).slice(2, 12)}`;

const clientActions: MockAction[] = [
  {
    key: 'rotate-secret',
    label: 'Rotate secret',
    tone: 'neutral',
    permission: IDENTITY_ADMIN,
    form: reasonOnly('Rotate the client secret', 'Rotate secret', 'A new secret is shown once. The old one keeps working for 24 hours so the callers can be updated; then it stops.'),
    when: (r) => (r.fields.type === 'confidential' ? null : 'A public client has no secret.'),
    apply: (r, _v, ctx) => {
      r.fields.secretRotatedAt = ctx.now.toISOString();
      r.fields.previousSecretExpiresAt = new Date(ctx.now.getTime() + DAY).toISOString();
      log(r, ctx.actor, ctx.now, 'Rotated the secret');
      ctx.showOnce?.('New client secret', randomSecret(), 'Copy it now. It is not shown again and cannot be read back. The previous secret works for 24 hours.');
    }
  },
  {
    key: 'disable',
    label: 'Disable client',
    tone: 'danger',
    permission: IDENTITY_ADMIN,
    form: reasonOnly('Disable the client', 'Disable', 'The token endpoint refuses the client at once and its refresh tokens stop working. You can enable it again.'),
    when: (r) => (r.fields.enabled !== true ? 'Already disabled.' : r.fields.platformClient === true ? 'The platform signs in with this client; disabling it would lock every staff member out.' : null),
    apply: (r, _v, ctx) => {
      r.fields.enabled = false;
      r.fields.status = 'DISABLED';
      log(r, ctx.actor, ctx.now, 'Disabled');
    }
  },
  {
    key: 'enable',
    label: 'Enable client',
    tone: 'primary',
    permission: IDENTITY_ADMIN,
    form: reasonOnly('Enable the client', 'Enable'),
    when: (r) => (r.fields.enabled === true ? 'Already enabled.' : null),
    apply: (r, _v, ctx) => {
      r.fields.enabled = true;
      r.fields.status = 'ENABLED';
      log(r, ctx.actor, ctx.now, 'Enabled');
    }
  },
  {
    key: 'revoke-sessions',
    label: 'Revoke all its sign-ins',
    tone: 'danger',
    permission: IDENTITY_ADMIN,
    form: reasonOnly('Revoke every sign-in of this client', 'Revoke all', 'Every refresh token of the client stops working at once; people sign in again. Access tokens already issued expire within minutes.'),
    when: () => null,
    apply: (r, _v, ctx) => log(r, ctx.actor, ctx.now, 'Revoked every sign-in')
  }
];

/** The other sessions a revoke takes with it: all of a client's, or all of a person's (sign out everywhere). */
export function sessionsToDrop(resource: string, action: string, rec: MockRecord, sessions: MockRecord[]): MockRecord[] {
  if (resource === 'oauth-clients' && action === 'revoke-sessions') return sessions.filter((x) => x.fields.clientId === rec.id);
  if (resource === 'identity-sessions' && action === 'revoke-user') return sessions.filter((x) => x.fields.userId === rec.fields.userId);
  return [];
}

const clientDef: MockResource = {
  title: 'OAuth clients',
  columns: [col('name', 'Client', 'strong', 24), col('clientId', 'Client id', 'mono', 22), col('type', 'Type', 'text', 12), col('grantTypes', 'Grant types', 'text', 26), col('status', 'Status', 'status', 16)],
  statusKey: 'status',
  facets: [],
  searchPlaceholder: 'Search by name or client id',
  exportable: false,
  titleOf: (r) => str(r.fields.name),
  subtitleOf: (r) => `${str(r.fields.clientId)} · ${str(r.fields.type)} client`,
  sections: [
    ['Client', ['clientId', 'name', 'type', 'enabled', 'issuedAt', 'platformClient']],
    ['OAuth', ['grantTypes', 'scopes', 'redirectUris', 'postLogoutRedirectUris', 'requirePkce', 'requireConsent', 'publicRefreshTokens']],
    ['Token lifetimes', ['accessTokenTtlMinutes', 'refreshTokenTtlMinutes']],
    ['Secret', ['secretRotatedAt', 'previousSecretExpiresAt']]
  ],
  actions: clientActions,
  edit: {
    permission: IDENTITY_ADMIN,
    form: (r) => form('Edit the client', 'Save', clientFields(r), 'The client id and type cannot change. Edits made here are never overwritten by a deployment.'),
    apply: (r, v) => applyClient(r, v)
  },
  create: {
    permission: IDENTITY_ADMIN,
    form: form(
      'Register an OAuth client',
      'Register',
      [
        field('clientId', 'Client id', 'text', true, { pattern: '^[a-z][a-z0-9._-]{2,63}$', help: '3 to 64 lower-case letters, digits, dots, dashes or underscores, starting with a letter' }),
        field('type', 'Type', 'select', true, {
          options: [
            { value: 'confidential', label: 'Confidential (has a secret: servers, service accounts)' },
            { value: 'public', label: 'Public (PKCE, no secret: browser and mobile apps)' }
          ],
          value: 'confidential'
        }),
        ...clientFields(null)
      ],
      'A confidential client’s secret is shown once, right after you register it.'
    ),
    build: (v, _id, ctx) => {
      const id = str(v.clientId);
      const r: MockRecord = {
        id,
        fields: {
          clientId: id,
          name: '',
          type: str(v.type),
          grantTypes: '',
          scopes: '',
          redirectUris: '',
          postLogoutRedirectUris: '',
          requirePkce: str(v.type) === 'public' || (Array.isArray(v.grantTypes) && v.grantTypes.includes('authorization_code')),
          requireConsent: false,
          publicRefreshTokens: false,
          accessTokenTtlMinutes: 5,
          refreshTokenTtlMinutes: 43_200,
          enabled: true,
          status: 'ENABLED',
          platformClient: false,
          issuedAt: ctx.now.toISOString(),
          secretRotatedAt: null,
          previousSecretExpiresAt: null
        },
        activity: []
      };
      applyClient(r, v);
      log(r, ctx.actor, ctx.now, 'Registered');
      if (r.fields.type === 'confidential') ctx.showOnce?.('Client secret', randomSecret(), 'Copy it now. It is not shown again and cannot be read back.');
      return r;
    }
  }
};

/* ── identity-sessions ── */

const sessionDef: MockResource = {
  title: 'Sessions & tokens',
  columns: [col('userName', 'User', 'strong', 20), col('userEmail', 'Email', 'text', 22), col('clientName', 'App', 'text', 16), col('ipAddress', 'IP address', 'mono', 12), col('lastUsedAt', 'Last used', 'datetime', 15), col('refreshable', 'Refresh token', 'text', 15)],
  facets: [],
  searchPlaceholder: 'Search by user, email or client',
  exportable: false,
  titleOf: (r) => str(r.fields.userName) || `Service account ${str(r.fields.clientId)}`,
  subtitleOf: (r) => `${str(r.fields.userEmail) || str(r.fields.userId)} · ${str(r.fields.clientName)}`,
  sections: [['Sign-in', ['userName', 'userEmail', 'userId', 'clientName', 'clientId', 'ipAddress', 'signedInAt', 'lastUsedAt', 'expiresAt', 'refreshable']]],
  actions: [
    {
      key: 'revoke',
      label: 'Revoke this sign-in',
      tone: 'danger',
      permission: IDENTITY_ADMIN,
      removes: true,
      form: reasonOnly('Revoke this sign-in', 'Revoke', 'Its refresh token stops working at once. An access token already issued expires within minutes.'),
      when: () => null,
      apply: () => undefined
    },
    {
      key: 'revoke-user',
      label: 'Sign the user out everywhere',
      tone: 'danger',
      permission: IDENTITY_ADMIN,
      removes: true,
      form: reasonOnly('Sign the user out everywhere', 'Sign out everywhere', 'Every sign-in of this person on every app stops working, and their single sign-on browser sessions end.'),
      when: (r) => (str(r.fields.userName) || str(r.fields.userEmail) ? null : 'This sign-in belongs to a service account, not a person.'),
      apply: () => undefined
    }
  ]
};

/* ── signing-keys ── */

const keyMeaning: Record<string, string> = {
  ACTIVE: 'Signs every new token. Resource servers find its public half in the JWKS.',
  RETIRING: 'No longer signs. Its public half stays in the JWKS so tokens it signed keep verifying, until the longest of them has expired.',
  RETIRED: 'Gone from the JWKS. Tokens it signed no longer verify.'
};

const keyDef: MockResource = {
  title: 'Signing keys',
  columns: [col('kid', 'Key id (kid)', 'mono', 28), col('algorithm', 'Algorithm', 'text', 10), col('status', 'Status', 'status', 14), col('createdAt', 'Created', 'datetime', 16), col('retireAfter', 'Retires after', 'datetime', 16)],
  statusKey: 'status',
  facets: [],
  exportable: false,
  titleOf: (r) => str(r.fields.kid),
  subtitleOf: (r) => `${str(r.fields.algorithm)} · ${str(r.fields.status).toLowerCase()}`,
  sections: [['Key', ['kid', 'algorithm', 'status', 'createdAt', 'rotatedAt', 'retireAfter', 'retiredAt']]],
  actions: [],
  create: {
    permission: IDENTITY_ADMIN,
    form: form(
      'Rotate the signing key',
      'Rotate',
      [reasonField()],
      'A new key signs every new token from now on. The current key stays published until the longest access token issued under it has expired, then retires. Nothing needs redeploying.'
    ),
    build: (_v, _id, ctx) => {
      const kid = `k${ctx.now.toISOString().replace(/[-:]/g, '').slice(0, 15)}-${Math.random().toString(16).slice(2, 6)}`;
      return { id: kid, fields: { kid, algorithm: 'RS256', status: 'ACTIVE', createdAt: ctx.now.toISOString(), rotatedAt: null, retireAfter: null, retiredAt: null, meaning: keyMeaning.ACTIVE ?? '' }, activity: [] };
    }
  }
};

/** Called by the handlers after a rotation: the previous active key starts retiring. */
export function retireActiveKeys(keys: MockRecord[], keep: string, now: Date) {
  keys.forEach((k) => {
    if (k.id !== keep && k.fields.status === 'ACTIVE') {
      k.fields.status = 'RETIRING';
      k.fields.rotatedAt = now.toISOString();
      k.fields.retireAfter = new Date(now.getTime() + 7 * MINUTE).toISOString();
    }
  });
}

/* ── auth-settings ── */

const SETTING_FIELDS: { key: string; label: string; type: FormField['type']; min?: number; max?: number; optional?: boolean }[] = [
  { key: 'passwordMinLength', label: 'Shortest password (characters)', type: 'number', min: 8, max: 64 },
  { key: 'mfaRequiredAccount', label: 'Require a second factor of customers', type: 'toggle' },
  { key: 'mfaRequiredSeller', label: 'Require a second factor of seller members', type: 'toggle' },
  { key: 'mfaRequiredProvider', label: 'Require a second factor of service-provider members', type: 'toggle' },
  { key: 'sessionIdleTimeoutMinutes', label: 'Sign-in session idle timeout (minutes)', type: 'number', min: 5, max: 1440 },
  { key: 'sessionAbsoluteTimeoutMinutes', label: 'Sign-in session absolute timeout (minutes)', type: 'number', min: 60, max: 43_200 },
  { key: 'accessTokenTtlCapMinutes', label: 'Access token lifetime ceiling (minutes, blank = none)', type: 'number', min: 1, max: 60, optional: true },
  { key: 'refreshTokenTtlCapMinutes', label: 'Refresh token lifetime ceiling (minutes, blank = none)', type: 'number', min: 5, max: 129_600, optional: true }
];

const settingsDef: MockResource = {
  title: 'Settings',
  columns: [
    col('setting', 'Setting', 'strong', 34),
    col('passwordMinLength', 'Password min.', 'number', 12),
    col('sessionIdleTimeoutMinutes', 'Idle (min)', 'number', 12),
    col('sessionAbsoluteTimeoutMinutes', 'Absolute (min)', 'number', 14),
    col('accessTokenTtlCapMinutes', 'Access cap (min)', 'number', 14),
    col('refreshTokenTtlCapMinutes', 'Refresh cap (min)', 'number', 14)
  ],
  facets: [],
  exportable: false,
  titleOf: () => 'Sign-in, password and token settings',
  subtitleOf: () => 'Stored in the identity database; the configured values are the defaults',
  sections: [['In force', SETTING_FIELDS.map((s) => s.key)]],
  actions: [],
  edit: {
    permission: IDENTITY_ADMIN,
    form: (r) =>
      form(
        'Change the settings',
        'Save settings',
        [
          ...SETTING_FIELDS.map((s) =>
            field(s.key, s.label, s.type, !s.optional && s.type !== 'toggle', {
              ...(s.min !== undefined ? { min: s.min } : {}),
              ...(s.max !== undefined ? { max: s.max } : {}),
              value: s.type === 'toggle' ? r.fields[s.key] === true : (r.fields[s.key] ?? null)
            })
          ),
          reasonField()
        ],
        'A value equal to the configured default is stored as "use the default". The change is recorded with your reason and what it was before.'
      ),
    apply: (r, v) => {
      SETTING_FIELDS.forEach((s) => {
        const value = v[s.key];
        r.fields[s.key] = s.type === 'toggle' ? value === true : typeof value === 'number' ? value : null;
      });
    }
  }
};

export const IDENTITY_RESOURCES = { 'oauth-clients': clientDef, 'identity-sessions': sessionDef, 'signing-keys': keyDef, 'auth-settings': settingsDef } as const;

/* ── token inspector and documents ── */

const b64 = (s: string) => atob(s.replace(/-/g, '+').replace(/_/g, '/'));
const ISSUER = 'https://id.afrotransact.com';
/** The mock accepts a token whose signature part is exactly this text as validly signed (the demo token below has it). */
export const MOCK_VALID_SIGNATURE = 'bW9jay1zaWduYXR1cmU';

function json(part: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(b64(part));
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** Decodes a JWT the way identity's inspector does (the token is read, never kept). */
export function inspectToken(token: string, now: Date, knownKids: string[]): IdentityTokenInspection {
  const parts = token.trim().split('.');
  const header = parts.length === 3 ? json(parts[0] ?? '') : null;
  const claims = parts.length === 3 ? json(parts[1] ?? '') : null;
  if (!header || !claims) {
    return { wellFormed: false, header: {}, claims: {}, scopes: [], signatureValid: false, issuerValid: false, valid: false, problems: ['This is not a signed JWT (three base64url parts with JSON header and claims)'] };
  }
  const problems: string[] = [];
  const kid = typeof header.kid === 'string' ? header.kid : null;
  const exp = typeof claims.exp === 'number' ? new Date(claims.exp * 1000) : null;
  const iat = typeof claims.iat === 'number' ? new Date(claims.iat * 1000) : null;
  let signatureValid = parts[2] === MOCK_VALID_SIGNATURE && header.alg === 'RS256';
  if (header.alg !== 'RS256') problems.push(`The algorithm is ${String(header.alg)}; this server signs with RS256 only`);
  else if (kid && !knownKids.includes(kid)) {
    signatureValid = false;
    problems.push(`No published key has the id ${kid} (rotated out and retired, or another issuer)`);
  } else if (!signatureValid) problems.push('The signature does not verify against the published key');
  const issuerValid = claims.iss === ISSUER;
  if (!issuerValid) problems.push(`The issuer is ${typeof claims.iss === 'string' ? claims.iss : 'missing'}, not ${ISSUER}`);
  const expired = exp ? exp.getTime() <= now.getTime() : null;
  if (expired && exp) problems.push(`The token expired at ${exp.toISOString()}`);
  const scope = claims.scope;
  const scopes = typeof scope === 'string' ? scope.split(/\s+/).filter(Boolean) : Array.isArray(scope) ? scope.map(String) : [];
  return {
    wellFormed: true,
    header,
    claims,
    scopes,
    keyId: kid,
    issuedAt: iat ? iat.toISOString() : null,
    expiresAt: exp ? exp.toISOString() : null,
    expired,
    signatureValid,
    issuerValid,
    valid: signatureValid && issuerValid && !expired,
    problems
  };
}

export function identityDocuments(keys: MockRecord[]): IdentityDocuments {
  return {
    discovery: {
      issuer: ISSUER,
      authorization_endpoint: `${ISSUER}/oauth2/authorize`,
      token_endpoint: `${ISSUER}/oauth2/token`,
      jwks_uri: `${ISSUER}/oauth2/jwks`,
      userinfo_endpoint: `${ISSUER}/userinfo`,
      revocation_endpoint: `${ISSUER}/oauth2/revoke`,
      end_session_endpoint: `${ISSUER}/connect/logout`,
      response_types_supported: ['code'],
      grant_types_supported: ['authorization_code', 'refresh_token', 'client_credentials'],
      subject_types_supported: ['public'],
      id_token_signing_alg_values_supported: ['RS256'],
      code_challenge_methods_supported: ['S256'],
      token_endpoint_auth_methods_supported: ['client_secret_basic', 'client_secret_post', 'none'],
      scopes_supported: ['email', 'openid', 'phone', 'profile']
    },
    jwks: { keys: keys.filter((k) => k.fields.status !== 'RETIRED').map((k) => ({ kty: 'RSA', use: 'sig', alg: 'RS256', kid: str(k.fields.kid), n: 'mock-modulus', e: 'AQAB' })) }
  };
}
