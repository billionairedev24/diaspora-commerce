import { z } from 'zod';

import { NotificationPreferencesSchema, SecurityOverviewSchema } from './shopper';

/* Zod schemas for the account settings routes of contracts/openapi/gateway-bff.yaml (/v1/account/profile,
   /preferences, /alerts, /sign-in-activity, /trusted-browsers, /security). Shared by the storefront, Seller Center and
   staff console; test/settings-contract.test.ts keeps them assignable to the generated contract types. */

const nullableString = z.string().nullable();

export const AccountKindSchema = z.enum(['customer', 'seller', 'staff']);
export type AccountKind = z.infer<typeof AccountKindSchema>;

export const PasskeySchema = z.object({ credentialId: z.string(), label: z.string(), createdAt: nullableString, lastUsedAt: nullableString });
export type Passkey = z.infer<typeof PasskeySchema>;

export const TrustedBrowserSchema = z.object({ id: z.string(), label: z.string(), createdAt: nullableString, expiresAt: nullableString });
export type TrustedBrowser = z.infer<typeof TrustedBrowserSchema>;

/** Login & security with the per-factor removal rules (staff switch methods but never drop the last one). */
export const AccountSecuritySchema = SecurityOverviewSchema.extend({ passkeys: z.array(PasskeySchema), trustedBrowsers: z.array(TrustedBrowserSchema) });
export type AccountSecurity = z.infer<typeof AccountSecuritySchema>;

export const SignInEventSchema = z.object({ at: z.string(), method: z.string(), outcome: z.string(), app: z.string(), device: z.string(), ip: nullableString });
export type SignInEvent = z.infer<typeof SignInEventSchema>;

export const AccountMembershipSchema = z.object({
  orgId: z.string(),
  name: z.string(),
  kind: z.enum(['SELLER', 'PROVIDER', 'BOTH']),
  status: z.string(),
  roleKey: z.string(),
  roleLabel: z.string(),
  owner: z.boolean(),
  active: z.boolean()
});
export type AccountMembership = z.infer<typeof AccountMembershipSchema>;

export const AccountProfileSchema = z.object({
  id: z.string(),
  kind: AccountKindSchema,
  firstName: z.string(),
  lastName: z.string(),
  email: z.string(),
  emailVerified: z.boolean(),
  emailManaged: z.boolean(),
  initials: z.string(),
  phone: nullableString,
  phoneVerified: z.boolean(),
  jobTitle: nullableString,
  memberships: z.array(AccountMembershipSchema),
  roles: z.array(z.object({ key: z.string(), label: z.string() }))
});
export type AccountProfile = z.infer<typeof AccountProfileSchema>;

export const AccountProfileInputSchema = z.object({
  firstName: z.string().trim().min(1, 'Enter your first name').max(80).optional(),
  lastName: z.string().trim().min(1, 'Enter your last name').max(80).optional(),
  phone: z.string().max(32).optional(),
  jobTitle: z.string().max(80, 'Use at most 80 characters').nullable().optional()
});
export type AccountProfileInput = z.infer<typeof AccountProfileInputSchema>;

export const DATE_FORMATS = ['AUTO', 'MDY', 'DMY', 'YMD'] as const;
export const NUMBER_FORMATS = ['AUTO', 'COMMA_DOT', 'DOT_COMMA', 'SPACE_COMMA'] as const;
export const TABLE_DENSITIES = ['COMFORTABLE', 'COMPACT'] as const;

export const AccountPreferencesSchema = z.object({
  locale: nullableString,
  timeZone: nullableString,
  dateFormat: z.enum(DATE_FORMATS),
  numberFormat: z.enum(NUMBER_FORMATS),
  tableDensity: z.enum(TABLE_DENSITIES)
});
export type AccountPreferences = z.infer<typeof AccountPreferencesSchema>;

export const ALERT_TOPICS = ['seller.orders', 'seller.inventory', 'seller.payouts', 'seller.compliance', 'seller.claims', 'staff.assigned', 'staff.escalations', 'staff.approvals'] as const;
export const AlertTopicSchema = z.enum(ALERT_TOPICS);
export type AlertTopic = z.infer<typeof AlertTopicSchema>;
export const AlertChannelSchema = z.enum(['email', 'sms']);
export type AlertChannel = z.infer<typeof AlertChannelSchema>;

export const AlertPreferenceSchema = z.object({ topic: AlertTopicSchema, channel: AlertChannelSchema, enabled: z.boolean(), locked: z.boolean() });
export type AlertPreference = z.infer<typeof AlertPreferenceSchema>;

const Clock = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/, 'Use a time such as 21:00');
export const QuietHoursSchema = z.object({ enabled: z.boolean(), start: Clock, end: Clock });
export type QuietHours = z.infer<typeof QuietHoursSchema>;

export const AlertPreferencesSchema = z.object({ audience: z.enum(['seller', 'staff']), preferences: z.array(AlertPreferenceSchema), quietHours: QuietHoursSchema });
export type AlertPreferences = z.infer<typeof AlertPreferencesSchema>;

export interface AlertPreferencesInput {
  preferences: Array<{ topic: AlertTopic; channel: AlertChannel; enabled: boolean }>;
  quietHours?: QuietHours;
}

export { NotificationPreferencesSchema };
