import { z } from 'zod';

import { type ApiClientOptions, createRequester } from './http';
import { type PhoneCodeSent, PhoneCodeSentSchema, type PhoneVerification, PhoneVerificationSchema } from './schemas/auth';
import {
  type AccountPreferences,
  AccountPreferencesSchema,
  type AccountProfile,
  type AccountProfileInput,
  AccountProfileSchema,
  type AccountSecurity,
  AccountSecuritySchema,
  type AlertPreferences,
  type AlertPreferencesInput,
  AlertPreferencesSchema,
  type Passkey,
  PasskeySchema,
  type SignInEvent,
  SignInEventSchema
} from './schemas/settings';
import {
  type LinkedAccounts,
  LinkedAccountsSchema,
  type NotificationPreferences,
  NotificationPreferencesSchema,
  type PasswordChange,
  type RecoveryCodes,
  RecoveryCodesSchema,
  type TotpSetup,
  TotpSetupSchema
} from './schemas/shopper';

const Void = z.undefined();
const enc = encodeURIComponent;

/**
 * The account settings routes every app uses through its own same-origin BFF proxy (/api/bff): profile, sign-in &
 * security, display preferences, back-office alerts and organisation memberships. Customers, Seller Center members
 * and staff share the client; the BFF decides what applies to whom (AccountProfile.kind).
 */
export function createSettingsApiClient(options: ApiClientOptions) {
  const request = createRequester(options);
  return {
    /* Profile */
    getAccountProfile: (signal?: AbortSignal): Promise<AccountProfile> => request('/v1/account/profile', AccountProfileSchema, { signal }),
    updateAccountProfile: (body: AccountProfileInput): Promise<AccountProfile> => request('/v1/account/profile', AccountProfileSchema, { method: 'PATCH', body }),
    startPhoneVerification: (): Promise<PhoneCodeSent> => request('/v1/account/phone/verification', PhoneCodeSentSchema, { method: 'POST' }),
    confirmPhoneVerification: (code: string): Promise<PhoneVerification> =>
      request('/v1/account/phone/verification/confirm', PhoneVerificationSchema, { method: 'POST', body: { code } }),
    leaveOrganisation: (orgId: string): Promise<void> => request(`/v1/account/orgs/${enc(orgId)}/leave`, Void, { method: 'POST' }),

    /* Sign-in & security */
    getSecurity: (signal?: AbortSignal): Promise<AccountSecurity> => request('/v1/account/security', AccountSecuritySchema, { signal }),
    changePassword: (body: PasswordChange): Promise<void> => request('/v1/account/password', Void, { method: 'PUT', body }),
    startTotp: (): Promise<TotpSetup> => request('/v1/account/mfa/totp', TotpSetupSchema, { method: 'POST' }),
    confirmTotp: (code: string): Promise<RecoveryCodes> => request('/v1/account/mfa/totp/confirm', RecoveryCodesSchema, { method: 'POST', body: { code } }),
    disableTotp: (): Promise<void> => request('/v1/account/mfa/totp', Void, { method: 'DELETE' }),
    startSmsMfa: () => request('/v1/account/mfa/sms', z.object({ codeExpiresAt: z.string().nullable() }), { method: 'POST' }),
    confirmSmsMfa: (code: string): Promise<RecoveryCodes> => request('/v1/account/mfa/sms/confirm', RecoveryCodesSchema, { method: 'POST', body: { code } }),
    disableSmsMfa: (): Promise<void> => request('/v1/account/mfa/sms', Void, { method: 'DELETE' }),
    regenerateRecoveryCodes: (): Promise<RecoveryCodes> => request('/v1/account/mfa/recovery-codes', RecoveryCodesSchema, { method: 'POST' }),
    renamePasskey: (id: string, label: string): Promise<Passkey> => request(`/v1/account/passkeys/${enc(id)}`, PasskeySchema, { method: 'PATCH', body: { label } }),
    removePasskey: (id: string): Promise<void> => request(`/v1/account/passkeys/${enc(id)}`, Void, { method: 'DELETE' }),
    forgetTrustedBrowser: (id: string): Promise<void> => request(`/v1/account/trusted-browsers/${enc(id)}`, Void, { method: 'DELETE' }),
    forgetTrustedBrowsers: (): Promise<void> => request('/v1/account/trusted-browsers', Void, { method: 'DELETE' }),
    revokeSession: (id: string): Promise<void> => request(`/v1/account/sessions/${enc(id)}`, Void, { method: 'DELETE' }),
    revokeOtherSessions: () => request('/v1/account/sessions/revoke-others', z.object({ revoked: z.number().int() }), { method: 'POST' }),
    removeDevice: (id: string): Promise<void> => request(`/v1/account/devices/${enc(id)}`, Void, { method: 'DELETE' }),
    unlinkAccount: (provider: string): Promise<LinkedAccounts> => request(`/v1/account/linked-accounts/${enc(provider)}`, LinkedAccountsSchema, { method: 'DELETE' }),
    getSignInActivity: async (signal?: AbortSignal): Promise<SignInEvent[]> =>
      (await request('/v1/account/sign-in-activity', z.object({ items: z.array(SignInEventSchema) }), { signal })).items,

    /* Notifications */
    getNotificationPreferences: (signal?: AbortSignal): Promise<NotificationPreferences> =>
      request('/v1/account/notification-preferences', NotificationPreferencesSchema, { signal }),
    saveNotificationPreferences: (body: NotificationPreferences): Promise<NotificationPreferences> =>
      request('/v1/account/notification-preferences', NotificationPreferencesSchema, { method: 'PUT', body }),
    getAlertPreferences: (signal?: AbortSignal): Promise<AlertPreferences> => request('/v1/account/alerts', AlertPreferencesSchema, { signal }),
    saveAlertPreferences: (body: AlertPreferencesInput): Promise<AlertPreferences> => request('/v1/account/alerts', AlertPreferencesSchema, { method: 'PUT', body }),

    /* Preferences */
    getAccountPreferences: (signal?: AbortSignal): Promise<AccountPreferences> => request('/v1/account/preferences', AccountPreferencesSchema, { signal }),
    saveAccountPreferences: (body: AccountPreferences): Promise<AccountPreferences> =>
      request('/v1/account/preferences', AccountPreferencesSchema, { method: 'PUT', body })
  };
}

export type SettingsApiClient = ReturnType<typeof createSettingsApiClient>;
