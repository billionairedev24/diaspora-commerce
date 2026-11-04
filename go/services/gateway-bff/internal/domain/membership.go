package domain

// AfroTransact membership as the storefront and the apps see it (S323, ADR 0006): configurable tiers staff define, a
// subscription on a saved card, the benefits applied to orders and the rule for the membership fee. Names, never ids.

// Problem codes of membership.
const (
	// CodeMembershipExists (409): the shopper is a member already; change the plan instead.
	CodeMembershipExists = "MEMBERSHIP_EXISTS"
	// CodeMembershipNotFound (404): the shopper is not a member.
	CodeMembershipNotFound = "MEMBERSHIP_NOT_FOUND"
	// CodeMembershipTierUnavailable (404/409): the tier does not exist or is not open to new members.
	CodeMembershipTierUnavailable = "MEMBERSHIP_TIER_UNAVAILABLE"
	// CodeMembershipNoCard (422): membership is billed to a saved card and the shopper has none.
	CodeMembershipNoCard = "MEMBERSHIP_NO_CARD"
	// CodeMembershipChargeFailed (422): the card was declined; nothing was charged and nothing changed.
	CodeMembershipChargeFailed = "MEMBERSHIP_CHARGE_FAILED"
	// CodeMembershipRefundNotEligible (409): the fee can no longer be refunded (window passed or a benefit was used).
	CodeMembershipRefundNotEligible = "MEMBERSHIP_REFUND_NOT_ELIGIBLE"
	// CodeMembershipPastDue (409): the last renewal failed; update the card and wait for the retry before changing plan.
	CodeMembershipPastDue = "MEMBERSHIP_PAST_DUE"
	// CodeMembershipNoChange (409): the plan asked for is the one the shopper has, or there is nothing to take back.
	CodeMembershipNoChange = "MEMBERSHIP_NO_CHANGE"
	// CodeMembershipComplimentary (409): the shopper is on free days; join on a card to continue after them.
	CodeMembershipComplimentary = "MEMBERSHIP_COMPLIMENTARY"
	// CodeMembershipAuthRequired (409): the bank wants the shopper to confirm the payment; try another card.
	CodeMembershipAuthRequired = "MEMBERSHIP_AUTHENTICATION_REQUIRED"
)

// MembershipBenefit is one benefit of a tier with the numbers it takes. Code is FREE_DELIVERY, FREE_SHIPPING, CASHBACK,
// MEMBER_PRICES, EARLY_ACCESS, PRIORITY_SUPPORT, BOOSTED_REWARDS or MONTHLY_CREDIT.
type MembershipBenefit struct {
	Code string `json:"code"`
	// MinOrderMinor is the goods subtotal an order needs (free delivery, free shipping, cashback).
	MinOrderMinor *int64 `json:"minOrderMinor"`
	// CapMinor is the most the benefit is worth on one order; null for no cap.
	CapMinor *int64 `json:"capMinor"`
	// PercentBps is hundredths of a percent: the cashback share, or the extra on referral and gift rewards.
	PercentBps *int `json:"percentBps"`
	// Hours is how long before everyone else a member can order from pre-order windows and delivery runs.
	Hours *int `json:"hours"`
	// AmountMinor is the credit granted each month.
	AmountMinor *int64 `json:"amountMinor"`
}

// MembershipTier is a tier shoppers can join.
type MembershipTier struct {
	Code        string `json:"code"`
	Name        string `json:"name"`
	Description string `json:"description"`
	// SortOrder: lower is the entry tier; a higher order is an upgrade.
	SortOrder         int                 `json:"sortOrder"`
	Currency          string              `json:"currency"`
	MonthlyPriceMinor int64               `json:"monthlyPriceMinor"`
	YearlyPriceMinor  int64               `json:"yearlyPriceMinor"`
	TrialDays         int                 `json:"trialDays"`
	Benefits          []MembershipBenefit `json:"benefits"`
}

// MembershipOffer is GET /v1/membership/tiers: what a shopper can join.
type MembershipOffer struct {
	Tiers []MembershipTier `json:"tiers"`
}

// MembershipSummary is the shopper's membership.
type MembershipSummary struct {
	// Status is TRIALING, ACTIVE, PAST_DUE (a renewal failed; retries run), CANCELED, LAPSED or EXPIRED.
	Status string `json:"status"`
	// Source is PAID (billed on the saved card) or COMPLIMENTARY (free days a program gave).
	Source   string `json:"source"`
	TierCode string `json:"tierCode"`
	TierName string `json:"tierName"`
	Interval string `json:"interval"`
	// PriceMinor is what the shopper pays each period, locked at sign-up or the last plan change.
	PriceMinor int64  `json:"priceMinor"`
	Currency   string `json:"currency"`
	StartedAt  string `json:"startedAt"`
	// PeriodEnd is the renewal date, or when the benefits end for a canceled or complimentary membership.
	PeriodEnd         string  `json:"periodEnd"`
	TrialEnd          *string `json:"trialEnd"`
	CancelAtPeriodEnd bool    `json:"cancelAtPeriodEnd"`
	EndedAt           *string `json:"endedAt"`
	// PendingTierName and PendingInterval: the change scheduled for the end of the period.
	PendingTierCode *string `json:"pendingTierCode"`
	PendingTierName *string `json:"pendingTierName"`
	PendingInterval *string `json:"pendingInterval"`
	NextRetryAt     *string `json:"nextRetryAt"`
	FreeDaysGranted int     `json:"freeDaysGranted"`
}

// MembershipCharge is one charge of the membership fee.
type MembershipCharge struct {
	// Kind is JOIN, RENEWAL or UPGRADE; Status SUCCEEDED, FAILED or REFUNDED.
	Kind          string  `json:"kind"`
	AmountMinor   int64   `json:"amountMinor"`
	Currency      string  `json:"currency"`
	Status        string  `json:"status"`
	RefundedMinor int64   `json:"refundedMinor"`
	PeriodEnd     *string `json:"periodEnd"`
	CreatedAt     string  `json:"createdAt"`
}

// MembershipUsage is what one benefit gave the shopper so far.
type MembershipUsage struct {
	Benefit     string `json:"benefit"`
	Uses        int64  `json:"uses"`
	AmountMinor int64  `json:"amountMinor"`
	Currency    string `json:"currency"`
}

// MembershipRefundRule is the rule for the membership fee: a full refund to the card when canceling within the window
// after a charge and before any benefit was used.
type MembershipRefundRule struct {
	Eligible bool `json:"eligible"`
	// Reason is OK, NOT_PAID, WINDOW_PASSED, BENEFIT_USED or DISABLED.
	Reason      string  `json:"reason"`
	AmountMinor int64   `json:"amountMinor"`
	Currency    string  `json:"currency"`
	Until       *string `json:"until"`
	WindowDays  int     `json:"windowDays"`
}

// MembershipCard is the saved card that is billed (display data only).
type MembershipCard struct {
	Brand    *string `json:"brand"`
	Last4    *string `json:"last4"`
	ExpMonth *int    `json:"expMonth"`
	ExpYear  *int    `json:"expYear"`
}

// MembershipAccount is GET /v1/account/membership: the shopper's membership, what it gives and what they can change.
type MembershipAccount struct {
	// Member: the shopper has the benefits now (trialling, active or past due).
	Member     bool               `json:"member"`
	Membership *MembershipSummary `json:"membership"`
	Tier       *MembershipTier    `json:"tier"`
	// PendingTier is the tier a scheduled change moves to at the end of the period.
	PendingTier *MembershipTier      `json:"pendingTier"`
	Charges     []MembershipCharge   `json:"charges"`
	Usage       []MembershipUsage    `json:"usage"`
	Refund      MembershipRefundRule `json:"refund"`
	// TrialAvailable: joining a tier with a trial starts one (first-time members on a card that never had one).
	TrialAvailable bool            `json:"trialAvailable"`
	Card           *MembershipCard `json:"card"`
	// Tiers are the tiers the shopper can move to or join.
	Tiers []MembershipTier `json:"tiers"`
}

// MembershipJoinRequest joins or changes the plan.
type MembershipJoinRequest struct {
	TierCode string `json:"tierCode"`
	// Interval is MONTHLY or YEARLY.
	Interval string `json:"interval"`
	// PaymentMethodID is the saved card to bill; absent for the default card.
	PaymentMethodID string `json:"paymentMethodId,omitempty"`
}

// MembershipCancelRequest cancels; Refund also refunds the latest fee and ends the membership now (the refund rule).
type MembershipCancelRequest struct {
	Refund bool `json:"refund"`
}

// MembershipBenefitUse is one benefit used: a fee waived, cashback, monthly credit.
type MembershipBenefitUse struct {
	Benefit     string `json:"benefit"`
	TierName    string `json:"tierName"`
	AmountMinor int64  `json:"amountMinor"`
	Currency    string `json:"currency"`
	CreatedAt   string `json:"createdAt"`
}

// MembershipWaiver is a delivery fee the membership waived on one package of the cart or checkout.
type MembershipWaiver struct {
	PackageID   string `json:"packageId"`
	Seller      string `json:"seller"`
	Benefit     string `json:"benefit"`
	WaivedMinor int64  `json:"waivedMinor"`
}

// MembershipUpsell is "Join Plus to save $X on this order".
type MembershipUpsell struct {
	TierCode          string `json:"tierCode"`
	TierName          string `json:"tierName"`
	MonthlyPriceMinor int64  `json:"monthlyPriceMinor"`
	YearlyPriceMinor  int64  `json:"yearlyPriceMinor"`
	TrialDays         int    `json:"trialDays"`
	SavingMinor       int64  `json:"savingMinor"`
	Currency          string `json:"currency"`
}

// MembershipSavings is what the membership does for this cart or checkout: each fee waived ("fee waived: membership")
// and, for a shopper who is not a member, the upsell.
type MembershipSavings struct {
	Member           bool               `json:"member"`
	TierName         *string            `json:"tierName"`
	Waivers          []MembershipWaiver `json:"waivers"`
	TotalWaivedMinor int64              `json:"totalWaivedMinor"`
	// CashbackMinor is the credit the member earns on this order once it is delivered ("credit earned"); 0 when none.
	CashbackMinor int64             `json:"cashbackMinor"`
	Upsell        *MembershipUpsell `json:"upsell"`
}

// OrderMembershipWaiver is a shipping fee the shopper's membership waived on an order shipment.
type OrderMembershipWaiver struct {
	TierName    string `json:"tierName"`
	Benefit     string `json:"benefit"`
	WaivedMinor int64  `json:"waivedMinor"`
}
