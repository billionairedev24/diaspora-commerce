import { z } from 'zod';

/* Refer a friend (S322, ADR 0006) — contracts/openapi/gateway-bff.yaml, tag Referral. AfroTransact pays both sides, as
   credit or free membership days; credit only buys products and services. Friends are shown by name, never by id. */

const MinorSchema = z.number().int();

export const REWARD_TYPES = ['CREDIT', 'MEMBERSHIP_DAYS'] as const;
export const REFERRAL_FRIEND_STATUSES = ['PENDING', 'WAITING', 'REWARDED', 'EXPIRED', 'CLOSED'] as const;

export const ReferralRewardTermsSchema = z.object({
  type: z.enum(REWARD_TYPES),
  /** Credit in minor units; 0 for membership days. */
  creditMinor: MinorSchema,
  /** Free membership days; 0 for credit. */
  membershipDays: z.number().int()
});
export type ReferralRewardTerms = z.infer<typeof ReferralRewardTermsSchema>;

export const ReferralProgramSchema = z.object({
  /** false while staff have switched the program off. */
  enabled: z.boolean(),
  referrerReward: ReferralRewardTermsSchema,
  referredReward: ReferralRewardTermsSchema,
  /** The friend's delivered order must be worth at least this. */
  minOrderMinor: MinorSchema,
  /** Rewards are granted this many days after delivery. */
  returnWindowDays: z.number().int(),
  /** Days after sign-up in which the friend can still qualify. */
  validityDays: z.number().int(),
  currency: z.string()
});
export type ReferralProgram = z.infer<typeof ReferralProgramSchema>;

export const ReferralEarningsSchema = z.object({
  invited: z.number().int(),
  open: z.number().int(),
  rewarded: z.number().int(),
  earnedMinor: MinorSchema,
  membershipDays: z.number().int(),
  /** Credit promised for friends who qualified, granted after the return window. */
  pendingMinor: MinorSchema
});
export type ReferralEarnings = z.infer<typeof ReferralEarningsSchema>;

export const ReferralFriendRewardSchema = z.object({
  terms: ReferralRewardTermsSchema,
  granted: z.boolean(),
  grantedAt: z.string().nullable()
});
export type ReferralFriendReward = z.infer<typeof ReferralFriendRewardSchema>;

export const ReferralFriendSchema = z.object({
  id: z.string(),
  /** First name and last initial; null on my own referral. */
  friendName: z.string().nullable(),
  /** PENDING: no qualifying order yet. WAITING: qualified, rewards after the return window (or under a check). CLOSED: not eligible. */
  status: z.enum(REFERRAL_FRIEND_STATUSES),
  signedUpAt: z.string(),
  expiresAt: z.string(),
  eligibleAt: z.string().nullable(),
  reward: ReferralFriendRewardSchema.nullable()
});
export type ReferralFriend = z.infer<typeof ReferralFriendSchema>;

export const AccountReferralSchema = z.object({
  /** Eight characters, for example KDM7XR3Q. */
  code: z.string(),
  /** The same as shown, KDM7-XR3Q. */
  displayCode: z.string(),
  /** The storefront link that opens sign-up with the code filled in. */
  shareUrl: z.string(),
  program: ReferralProgramSchema,
  earnings: ReferralEarningsSchema,
  friends: z.array(ReferralFriendSchema),
  /** My own referral when someone referred me. */
  asFriend: ReferralFriendSchema.nullable()
});
export type AccountReferral = z.infer<typeof AccountReferralSchema>;
