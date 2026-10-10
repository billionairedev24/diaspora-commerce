import { z } from 'zod';

import { isPasswordAcceptable } from '../validation/password';
import { isCountryCode, isValidMobile, toE164 } from '../validation/phone';

const NAME = /^[A-Za-zÀ-ÖØ-öø-ÿ][A-Za-zÀ-ÖØ-öø-ÿ' .-]{0,48}$/;

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
      phoneVerified: z.boolean().optional()
    })
    .nullable(),
  counts: z.object({ orders: z.number().int(), returns: z.number().int(), addresses: z.number().int(), paymentMethods: z.number().int() }).nullable()
});
export type Me = z.infer<typeof MeSchema>;

/* ── Requests & responses ───────────────────────────────────────── */

export const SignInRequestSchema = z.object({ email: z.email(), password: z.string().min(1), returnTo: z.string().optional() });
export type SignInRequest = z.infer<typeof SignInRequestSchema>;

export const MfaMethodSchema = z.enum(['totp', 'sms', 'email', 'passkey']);
export type MfaMethod = z.infer<typeof MfaMethodSchema>;

export const AuthResultSchema = z.discriminatedUnion('status', [
  z.object({ status: z.literal('authenticated'), redirectTo: z.string() }),
  z.object({ status: z.literal('mfa_required'), challengeId: z.string(), methods: z.array(MfaMethodSchema), destination: z.string() }),
  z.object({ status: z.literal('verification_required'), challengeId: z.string(), destination: z.string(), resendAfterSeconds: z.number().int() })
]);
export type AuthResult = z.infer<typeof AuthResultSchema>;

export const PhoneCodeSentSchema = z.object({ sentTo: z.string(), expiresAt: z.string() });
export type PhoneCodeSent = z.infer<typeof PhoneCodeSentSchema>;
export const PhoneVerificationSchema = z.object({ phoneVerified: z.boolean(), verifiedAt: z.string().optional() });
export type PhoneVerification = z.infer<typeof PhoneVerificationSchema>;

export const CodeSchema = z.string().regex(/^\d{6}$/, 'Enter the 6-digit code.');

export const VerifyCodeRequestSchema = z.object({ challengeId: z.string(), code: CodeSchema, trustDevice: z.boolean().optional() });
export type VerifyCodeRequest = z.infer<typeof VerifyCodeRequestSchema>;

export const ResendResponseSchema = z.object({ resendAfterSeconds: z.number().int() });

export const SignUpRequestSchema = z.object({
  firstName: z.string(),
  lastName: z.string(),
  email: z.email(),
  /** E.164 mobile number. */
  phone: z.string().regex(/^\+[1-9]\d{6,14}$/),
  password: z.string(),
  termsAccepted: z.literal(true),
  smsConsent: z.boolean(),
  returnTo: z.string().optional(),
  /** Token from an organisation invitation link: the account starts verified and joins the organisation. */
  invitationToken: z.string().max(200).optional()
});
export type SignUpRequest = z.infer<typeof SignUpRequestSchema>;

export const PasskeyOptionsSchema = z.object({
  challengeId: z.string(),
  /** base64url challenge. */
  challenge: z.string(),
  rpId: z.string(),
  timeout: z.number().int(),
  userVerification: z.enum(['required', 'preferred', 'discouraged']),
  allowCredentials: z.array(z.object({ id: z.string(), type: z.literal('public-key') }))
});
export type PasskeyOptions = z.infer<typeof PasskeyOptionsSchema>;

export const PasskeyAssertionSchema = z.object({
  challengeId: z.string(),
  id: z.string(),
  rawId: z.string(),
  type: z.literal('public-key'),
  response: z.object({ clientDataJSON: z.string(), authenticatorData: z.string(), signature: z.string(), userHandle: z.string().nullable() })
});
export type PasskeyAssertion = z.infer<typeof PasskeyAssertionSchema>;

/* ── Form schemas (client-side validation, same rules as the identity service) ───────── */

export const SignInFormSchema = z.object({
  email: z.string().trim().pipe(z.email('Enter a valid email address.')),
  password: z.string().min(1, 'Enter your password.')
});
export type SignInForm = z.infer<typeof SignInFormSchema>;

export const SignUpFormSchema = z
  .object({
    firstName: z.string().trim().regex(NAME, 'Enter your first name'),
    lastName: z.string().trim().regex(NAME, 'Enter your last name'),
    email: z.string().trim().pipe(z.email('Enter a valid email address')),
    phoneCountry: z.string(),
    phone: z.string(),
    password: z.string(),
    confirmPassword: z.string(),
    termsAccepted: z.boolean(),
    smsConsent: z.boolean()
  })
  .superRefine((v, ctx) => {
    if (!isCountryCode(v.phoneCountry) || !isValidMobile(v.phone, v.phoneCountry)) {
      ctx.addIssue({ code: 'custom', path: ['phone'], message: 'Enter a valid mobile number' });
    }
    if (!isPasswordAcceptable(v.password, { firstName: v.firstName, lastName: v.lastName, email: v.email })) {
      ctx.addIssue({ code: 'custom', path: ['password'], message: 'Password must meet all the rules' });
    }
    if (v.password !== v.confirmPassword) ctx.addIssue({ code: 'custom', path: ['confirmPassword'], message: 'Passwords don’t match' });
    if (!v.termsAccepted) ctx.addIssue({ code: 'custom', path: ['termsAccepted'], message: 'Please accept the Conditions of Use to continue.' });
  });
export type SignUpForm = z.infer<typeof SignUpFormSchema>;

/** Converts a validated sign-up form into the BFF request. */
export function toSignUpRequest(form: SignUpForm, returnTo?: string, invitationToken?: string): SignUpRequest {
  const phone = isCountryCode(form.phoneCountry) ? toE164(form.phone, form.phoneCountry) : null;
  if (!phone) throw new Error('Invalid phone number');
  return {
    firstName: form.firstName.trim(),
    lastName: form.lastName.trim(),
    email: form.email.trim().toLowerCase(),
    phone,
    password: form.password,
    termsAccepted: true,
    smsConsent: form.smsConsent,
    ...(returnTo ? { returnTo } : {}),
    ...(invitationToken ? { invitationToken } : {})
  };
}
