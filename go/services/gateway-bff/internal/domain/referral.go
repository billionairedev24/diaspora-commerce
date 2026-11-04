package domain

// The referral program as the storefront and the apps see it (S322, ADR 0006). AfroTransact pays both sides, as credit
// or free membership days; credit only buys products and services. Friends are shown by name, never by id.

// ReferralRewardTerms is what one side earns.
type ReferralRewardTerms struct {
	// Type is CREDIT or MEMBERSHIP_DAYS.
	Type           string `json:"type"`
	CreditMinor    int64  `json:"creditMinor"`
	MembershipDays int    `json:"membershipDays"`
}

// ReferralProgram is the offer in force.
type ReferralProgram struct {
	Enabled          bool                `json:"enabled"`
	ReferrerReward   ReferralRewardTerms `json:"referrerReward"`
	ReferredReward   ReferralRewardTerms `json:"referredReward"`
	MinOrderMinor    int64               `json:"minOrderMinor"`
	ReturnWindowDays int                 `json:"returnWindowDays"`
	ValidityDays     int                 `json:"validityDays"`
	Currency         string              `json:"currency"`
}

// ReferralEarnings is what the shopper's referrals have paid.
type ReferralEarnings struct {
	Invited        int   `json:"invited"`
	Open           int   `json:"open"`
	Rewarded       int   `json:"rewarded"`
	EarnedMinor    int64 `json:"earnedMinor"`
	MembershipDays int   `json:"membershipDays"`
	PendingMinor   int64 `json:"pendingMinor"`
}

// ReferralFriendReward is what the shopper earns from one referral.
type ReferralFriendReward struct {
	Terms     ReferralRewardTerms `json:"terms"`
	Granted   bool                `json:"granted"`
	GrantedAt *string             `json:"grantedAt"`
}

// ReferralFriend is a friend the shopper invited, or (as AccountReferral.AsFriend) the shopper's own referral.
type ReferralFriend struct {
	ID string `json:"id"`
	// FriendName is the first name and last initial; null on the shopper's own referral.
	FriendName *string `json:"friendName"`
	// Status is PENDING, WAITING, REWARDED, EXPIRED or CLOSED. A referral held for a check shows as WAITING.
	Status     string                `json:"status"`
	SignedUpAt string                `json:"signedUpAt"`
	ExpiresAt  string                `json:"expiresAt"`
	EligibleAt *string               `json:"eligibleAt"`
	Reward     *ReferralFriendReward `json:"reward"`
}

// AccountReferral is the "Refer a friend" page.
type AccountReferral struct {
	// Code is eight characters; DisplayCode is the same as XXXX-XXXX.
	Code        string `json:"code"`
	DisplayCode string `json:"displayCode"`
	// ShareURL opens the storefront's sign-up with the code filled in.
	ShareURL string           `json:"shareUrl"`
	Program  ReferralProgram  `json:"program"`
	Earnings ReferralEarnings `json:"earnings"`
	Friends  []ReferralFriend `json:"friends"`
	AsFriend *ReferralFriend  `json:"asFriend"`
}
