import { z } from 'zod';

/** A back office the signed-in person can open from the storefront (Seller Center, Provider Hub, staff console). */
export const BackOfficeSchema = z.object({
  app: z.enum(['seller-center', 'provider-hub', 'staff-console']),
  /** active: open it; application: a seller or provider application in progress (continue onboarding). */
  state: z.enum(['active', 'application'])
});
export type BackOffice = z.infer<typeof BackOfficeSchema>;

export const MeSchema = z.object({
  authenticated: z.boolean(),
  user: z
    .object({
      id: z.string(),
      firstName: z.string(),
      lastName: z.string(),
      email: z.string(),
      initials: z.string(),
      roles: z.array(z.string()),
      /** Masked account phone (•••• 0142). */
      phone: z.string().optional(),
      phoneVerified: z.boolean().optional(),
      /** The person's back offices for the account menu (always sent by the BFF; [] when an older one omits it). */
      backOffices: z.array(BackOfficeSchema).default([])
    })
    .nullable(),
  counts: z.object({ orders: z.number().int(), returns: z.number().int(), addresses: z.number().int(), paymentMethods: z.number().int() }).nullable()
});
export type Me = z.infer<typeof MeSchema>;

/* ── Requests & responses ───────────────────────────────────────── */

/** POST /v1/auth/sign-out: identity's end-session URL to visit next (single sign-out), or null. */
export const SignOutResultSchema = z.object({ redirectTo: z.string().nullable() });
export type SignOutResult = z.infer<typeof SignOutResultSchema>;

export const PhoneCodeSentSchema = z.object({ sentTo: z.string(), expiresAt: z.string() });
export type PhoneCodeSent = z.infer<typeof PhoneCodeSentSchema>;
export const PhoneVerificationSchema = z.object({ phoneVerified: z.boolean(), verifiedAt: z.string().optional() });
export type PhoneVerification = z.infer<typeof PhoneVerificationSchema>;

export const CodeSchema = z.string().regex(/^\d{6}$/, 'Enter the 6-digit code.');
