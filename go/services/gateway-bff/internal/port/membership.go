package port

import "context"

// ---------------------------------------------------------------- payments: membership (S323, ADR 0006)

// UpMembershipBenefit is payments' MembershipBenefit: one benefit of a tier with the numbers it takes.
type UpMembershipBenefit struct {
	Code          string `json:"code"`
	MinOrderMinor *int64 `json:"minOrderMinor"`
	CapMinor      *int64 `json:"capMinor"`
	PercentBps    *int   `json:"percentBps"`
	Hours         *int   `json:"hours"`
	AmountMinor   *int64 `json:"amountMinor"`
}

// UpMembershipTier is payments' MembershipTier.
type UpMembershipTier struct {
	Code              string                `json:"code"`
	Name              string                `json:"name"`
	Description       string                `json:"description"`
	Status            string                `json:"status"`
	SortOrder         int                   `json:"sortOrder"`
	Currency          string                `json:"currency"`
	MonthlyPriceMinor int64                 `json:"monthlyPriceMinor"`
	YearlyPriceMinor  int64                 `json:"yearlyPriceMinor"`
	TrialDays         int                   `json:"trialDays"`
	Benefits          []UpMembershipBenefit `json:"benefits"`
}

// UpMembership is payments' Membership.
type UpMembership struct {
	Status            string  `json:"status"`
	Source            string  `json:"source"`
	TierCode          string  `json:"tierCode"`
	Interval          string  `json:"interval"`
	PriceMinor        int64   `json:"priceMinor"`
	Currency          string  `json:"currency"`
	StartedAt         string  `json:"startedAt"`
	PeriodStart       string  `json:"periodStart"`
	PeriodEnd         string  `json:"periodEnd"`
	TrialEnd          *string `json:"trialEnd"`
	CancelAtPeriodEnd bool    `json:"cancelAtPeriodEnd"`
	EndedAt           *string `json:"endedAt"`
	EndReason         *string `json:"endReason"`
	PendingTierCode   *string `json:"pendingTierCode"`
	PendingInterval   *string `json:"pendingInterval"`
	RetryCount        int     `json:"retryCount"`
	NextRetryAt       *string `json:"nextRetryAt"`
	FreeDaysGranted   int     `json:"freeDaysGranted"`
}

// UpMembershipCharge is payments' MembershipCharge.
type UpMembershipCharge struct {
	Kind          string  `json:"kind"`
	AmountMinor   int64   `json:"amountMinor"`
	Currency      string  `json:"currency"`
	Status        string  `json:"status"`
	FailureCode   *string `json:"failureCode"`
	PeriodStart   *string `json:"periodStart"`
	PeriodEnd     *string `json:"periodEnd"`
	RefundedMinor int64   `json:"refundedMinor"`
	CreatedAt     string  `json:"createdAt"`
}

// UpMembershipUsage is payments' MembershipBenefitUsage.
type UpMembershipUsage struct {
	Benefit     string `json:"benefit"`
	Uses        int64  `json:"uses"`
	AmountMinor int64  `json:"amountMinor"`
	Currency    string `json:"currency"`
}

// UpMembershipRefundRule is payments' MembershipRefundRule.
type UpMembershipRefundRule struct {
	Eligible    bool    `json:"eligible"`
	Reason      string  `json:"reason"`
	AmountMinor int64   `json:"amountMinor"`
	Currency    string  `json:"currency"`
	Until       *string `json:"until"`
}

// UpMembershipCard is payments' MembershipCard.
type UpMembershipCard struct {
	Brand    *string `json:"brand"`
	Last4    *string `json:"last4"`
	ExpMonth *int    `json:"expMonth"`
	ExpYear  *int    `json:"expYear"`
}

// UpMyMembership is payments' MyMembership.
type UpMyMembership struct {
	Membership       *UpMembership          `json:"membership"`
	Tier             *UpMembershipTier      `json:"tier"`
	PendingTier      *UpMembershipTier      `json:"pendingTier"`
	Charges          []UpMembershipCharge   `json:"charges"`
	Usage            []UpMembershipUsage    `json:"usage"`
	Refund           UpMembershipRefundRule `json:"refund"`
	TrialAvailable   bool                   `json:"trialAvailable"`
	Card             *UpMembershipCard      `json:"card"`
	RefundWindowDays int                    `json:"refundWindowDays"`
}

// UpMembershipUse is payments' MembershipBenefitUse.
type UpMembershipUse struct {
	Benefit     string `json:"benefit"`
	TierCode    string `json:"tierCode"`
	AmountMinor int64  `json:"amountMinor"`
	Currency    string `json:"currency"`
	CreatedAt   string `json:"createdAt"`
}

// UpMembershipJoin is payments' MembershipJoinRequest.
type UpMembershipJoin struct {
	TierCode        string `json:"tierCode"`
	Interval        string `json:"interval"`
	PaymentMethodID string `json:"paymentMethodId,omitempty"`
}

// UpMembershipFee is payments' MembershipFee: a delivery fee of an order the membership may waive.
type UpMembershipFee struct {
	Key      string `json:"key"`
	Kind     string `json:"kind"`
	FeeMinor int64  `json:"feeMinor"`
}

// UpMembershipSavingsRequest is payments' MembershipSavingsRequest.
type UpMembershipSavingsRequest struct {
	ItemsMinor int64             `json:"itemsMinor"`
	Currency   string            `json:"currency"`
	Fees       []UpMembershipFee `json:"fees"`
}

// UpMembershipWaiver is payments' MembershipWaiver.
type UpMembershipWaiver struct {
	Key         string `json:"key"`
	Benefit     string `json:"benefit"`
	WaivedMinor int64  `json:"waivedMinor"`
}

// UpMembershipUpsell is payments' MembershipUpsell.
type UpMembershipUpsell struct {
	TierCode          string `json:"tierCode"`
	TierName          string `json:"tierName"`
	MonthlyPriceMinor int64  `json:"monthlyPriceMinor"`
	YearlyPriceMinor  int64  `json:"yearlyPriceMinor"`
	TrialDays         int    `json:"trialDays"`
	SavingMinor       int64  `json:"savingMinor"`
	Currency          string `json:"currency"`
}

// UpMembershipWaiverQuote is payments' MembershipWaiverQuote.
type UpMembershipWaiverQuote struct {
	Member           bool                 `json:"member"`
	TierCode         string               `json:"tierCode"`
	TierName         string               `json:"tierName"`
	Waivers          []UpMembershipWaiver `json:"waivers"`
	TotalWaivedMinor int64                `json:"totalWaivedMinor"`
	// CashbackMinor is the credit the member earns on this order once it is delivered (0 without cashback or in a trial).
	CashbackMinor int64               `json:"cashbackMinor"`
	Upsell        *UpMembershipUpsell `json:"upsell"`
}

// MembershipClient calls payments for the shopper's membership with the shopper's own token (Tiers also with the BFF's
// service token for visitors who are not signed in).
type MembershipClient interface {
	Tiers(ctx context.Context, bearer string) ([]UpMembershipTier, error)
	Me(ctx context.Context, bearer string) (UpMyMembership, error)
	Join(ctx context.Context, bearer, key string, req UpMembershipJoin) (UpMyMembership, error)
	Change(ctx context.Context, bearer, key string, req UpMembershipJoin) (UpMyMembership, error)
	Cancel(ctx context.Context, bearer string, refund bool) (UpMyMembership, error)
	Resume(ctx context.Context, bearer string) (UpMyMembership, error)
	Uses(ctx context.Context, bearer string, limit int) ([]UpMembershipUse, error)
	Savings(ctx context.Context, bearer string, req UpMembershipSavingsRequest) (UpMembershipWaiverQuote, error)
}
