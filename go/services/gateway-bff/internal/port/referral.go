package port

import (
	"context"
	"time"
)

// ---------------------------------------------------------------- payments: referral program (S322)

// UpReferralReward is payments' ReferralRewardTerms: what one side earns.
type UpReferralReward struct {
	Type           string `json:"type"`
	CreditMinor    int64  `json:"creditMinor"`
	MembershipDays int    `json:"membershipDays"`
}

// UpReferralProgram is payments' ReferralProgramTerms: the offer in force.
type UpReferralProgram struct {
	Enabled          bool             `json:"enabled"`
	ReferrerReward   UpReferralReward `json:"referrerReward"`
	ReferredReward   UpReferralReward `json:"referredReward"`
	MinOrderMinor    int64            `json:"minOrderMinor"`
	ReturnWindowDays int              `json:"returnWindowDays"`
	ValidityDays     int              `json:"validityDays"`
	Currency         string           `json:"currency"`
}

// UpReferralEarnings is payments' ReferralEarnings.
type UpReferralEarnings struct {
	Invited        int   `json:"invited"`
	Open           int   `json:"open"`
	Rewarded       int   `json:"rewarded"`
	EarnedMinor    int64 `json:"earnedMinor"`
	MembershipDays int   `json:"membershipDays"`
	PendingMinor   int64 `json:"pendingMinor"`
}

// UpMyReferralReward is payments' MyReferralReward.
type UpMyReferralReward struct {
	Terms     UpReferralReward `json:"terms"`
	Granted   bool             `json:"granted"`
	GrantedAt *time.Time       `json:"grantedAt"`
}

// UpMyReferral is payments' MyReferral: a friend the shopper invited, or the shopper's own referral.
type UpMyReferral struct {
	ID         string              `json:"id"`
	FriendName *string             `json:"friendName"`
	Status     string              `json:"status"`
	SignedUpAt time.Time           `json:"signedUpAt"`
	ExpiresAt  time.Time           `json:"expiresAt"`
	EligibleAt *time.Time          `json:"eligibleAt"`
	Reward     *UpMyReferralReward `json:"reward"`
}

// UpReferralOverview is payments' ReferralOverview.
type UpReferralOverview struct {
	Code        string             `json:"code"`
	DisplayCode string             `json:"displayCode"`
	Program     UpReferralProgram  `json:"program"`
	Earnings    UpReferralEarnings `json:"earnings"`
	AsFriend    *UpMyReferral      `json:"asFriend"`
}

// ReferralClient calls payments with the shopper's token: their referral code, the offer, their friends and earnings.
type ReferralClient interface {
	Overview(ctx context.Context, bearer string) (UpReferralOverview, error)
	Friends(ctx context.Context, bearer string, limit int) ([]UpMyReferral, error)
}
