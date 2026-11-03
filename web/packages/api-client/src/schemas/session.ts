import { z } from 'zod';

/** Whose sign-out policy applies to a session (GET /v1/session/policy). */
export const SessionAudienceSchema = z.enum(['customer-web', 'customer-app', 'seller', 'staff', 'driver']);
export type SessionAudience = z.infer<typeof SessionAudienceSchema>;

/** Problem `code` the BFF answers with (401) once the sign-out policy has ended a session. */
export const SessionEndReasonSchema = z.enum(['SESSION_IDLE_TIMEOUT', 'SESSION_ABSOLUTE_TIMEOUT']);
export type SessionEndReason = z.infer<typeof SessionEndReasonSchema>;

/**
 * The sign-out policy in force for the caller's session and when it ends. Times are RFC 3339 instants. For cookie
 * sessions expiresAt = min(lastActivityAt + idle, sign-in + absolute); bearer callers get null for both.
 */
export const SessionPolicySchema = z.object({
  audience: SessionAudienceSchema,
  idleTimeoutSeconds: z.number().int(),
  absoluteTimeoutSeconds: z.number().int(),
  /** Warn this long before the idle timeout; 0 means no warning. */
  warnSeconds: z.number().int(),
  lastActivityAt: z.string().nullable(),
  expiresAt: z.string().nullable()
});
export type SessionPolicy = z.infer<typeof SessionPolicySchema>;

/** A loaded policy with the server's clock (epoch ms, from the response's Date header; null when it had none). */
export interface LoadedSessionPolicy {
  policy: SessionPolicy;
  serverTime: number | null;
}
