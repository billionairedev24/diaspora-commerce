package port

import (
	"context"
	"time"
)

// SellerBillingClient is the seller service's plan & billing API beyond the overview (S292–S297): the plans this
// organisation may choose, the rewards it can earn and received, plan change previews, and adding a payment method
// through a Stripe SetupIntent.
type SellerBillingClient interface {
	// OrgPlans lists public plans, invite-only plans the organisation was invited to and its current plan (seller
	// GET /v1/orgs/{orgId}/billing/plans).
	OrgPlans(ctx context.Context, bearer, orgID string) ([]SellerPlan, error)
	// Rewards is seller GET /v1/orgs/{orgId}/billing/rewards.
	Rewards(ctx context.Context, bearer, orgID string) (SellerRewards, error)
	// PreviewPlanChange is seller GET /v1/orgs/{orgId}/billing/plan/preview.
	PreviewPlanChange(ctx context.Context, bearer, orgID, planCode, cadence string) (SellerPlanChangePreview, error)
	// CreateSetupIntent is seller POST /v1/orgs/{orgId}/billing/setup-intents.
	CreateSetupIntent(ctx context.Context, bearer, orgID string) (SellerSetupIntent, error)
	// AddPaymentMethod is seller POST /v1/orgs/{orgId}/billing/payment-methods.
	AddPaymentMethod(ctx context.Context, bearer, orgID string, in SellerPaymentMethodInput) (SellerPaymentMethod, error)
	// PlanFeeCollection is seller GET /v1/orgs/{orgId}/billing/plan-fee-collection (S298).
	PlanFeeCollection(ctx context.Context, bearer, orgID string) (SellerPlanFeeCollection, error)
	// SetPlanFeeCollection is seller PUT /v1/orgs/{orgId}/billing/plan-fee-collection (owner / finance).
	SetPlanFeeCollection(ctx context.Context, bearer, orgID, mode string) (SellerPlanFeeCollection, error)
	// RetryInvoice is seller POST /v1/orgs/{orgId}/billing/invoices/{invoiceId}/retry (owner / finance): collects an
	// unpaid invoice again after the payment method was fixed.
	RetryInvoice(ctx context.Context, bearer, orgID, invoiceID string) (SellerInvoice, error)
	// InvoiceDocument is seller GET /v1/orgs/{orgId}/billing/invoices/{invoiceId}/document: a printable HTML invoice.
	InvoiceDocument(ctx context.Context, bearer, orgID, invoiceID string) ([]byte, error)
}

// SellerPlanFeeCollection is seller's PlanFeeCollection: how the organisation pays its plan (S298).
type SellerPlanFeeCollection struct {
	OrgID                string     `json:"orgId"`
	Available            bool       `json:"available"`
	Mode                 string     `json:"mode"`
	Effective            string     `json:"effective"`
	Source               string     `json:"source"`
	ChangedAt            *time.Time `json:"changedAt"`
	PlatformDefault      string     `json:"platformDefault"`
	AppliesFromInvoiceAt *time.Time `json:"appliesFromInvoiceAt"`
	AppliesFromLabel     string     `json:"appliesFromLabel"`
	InProgressInvoiceID  *string    `json:"inProgressInvoiceId"`
}

// SellerRewardProgress is seller's RewardProgress.
type SellerRewardProgress struct {
	RuleID         string     `json:"ruleId"`
	Name           string     `json:"name"`
	Description    string     `json:"description"`
	Metric         string     `json:"metric"`
	Value          int64      `json:"value"`
	Threshold      int64      `json:"threshold"`
	Percent        int        `json:"percent"`
	Qualifies      bool       `json:"qualifies"`
	Label          string     `json:"label"`
	RewardLabel    string     `json:"rewardLabel"`
	ConditionLabel string     `json:"conditionLabel"`
	EndsAt         *time.Time `json:"endsAt"`
	// S299: LESS_THAN rules (low-sales relief) are decided once, at CheckAt.
	Comparison     string     `json:"comparison"`
	WindowAnchor   string     `json:"windowAnchor"`
	WindowDays     *int       `json:"windowDays"`
	Pending        bool       `json:"pending"`
	CheckAt        *time.Time `json:"checkAt"`
	WindowClosesAt *time.Time `json:"windowClosesAt"`
}

// SellerGrant is seller's BillingGrant (the fields Seller Center shows).
type SellerGrant struct {
	ID          string     `json:"id"`
	Source      string     `json:"source"`
	Type        string     `json:"type"`
	Summary     string     `json:"summary"`
	Note        string     `json:"note"`
	Status      string     `json:"status"`
	CreditCents *int64     `json:"creditCents"`
	FreeDays    *int       `json:"freeDays"`
	CreatedAt   time.Time  `json:"createdAt"`
	AppliedAt   *time.Time `json:"appliedAt"`
	// NewTrialEnd is where free days moved the trial end (the next charge).
	NewTrialEnd *time.Time `json:"newTrialEnd"`
	// RuleComparison is the reward rule's comparison (S299; nil for staff adjustments).
	RuleComparison *string `json:"ruleComparison"`
}

// SellerRewards is seller's SellerRewards.
type SellerRewards struct {
	Available []SellerRewardProgress `json:"available"`
	Earned    []SellerGrant          `json:"earned"`
}

// SellerPlanChangePreview is seller's PlanChangePreview.
type SellerPlanChangePreview struct {
	Change            string     `json:"change"`
	PlanCode          string     `json:"planCode"`
	PlanName          string     `json:"planName"`
	Cadence           string     `json:"cadence"`
	EffectiveAt       *time.Time `json:"effectiveAt"`
	AmountDueNowCents int64      `json:"amountDueNowCents"`
	NextChargeCents   int64      `json:"nextChargeCents"`
	NextChargeAt      *time.Time `json:"nextChargeAt"`
	Trialing          bool       `json:"trialing"`
}

// SellerSetupIntent is seller's BillingSetupIntent.
type SellerSetupIntent struct {
	CustomerID    string `json:"customerId"`
	SetupIntentID string `json:"setupIntentId"`
	ClientSecret  string `json:"clientSecret"`
}

// SellerPaymentMethodInput is seller's AddPaymentMethodRequest (tokenised by Stripe.js; display fields only).
type SellerPaymentMethodInput struct {
	StripePaymentMethodID string `json:"stripePaymentMethodId"`
	Kind                  string `json:"kind"`
	Brand                 string `json:"brand,omitempty"`
	Last4                 string `json:"last4"`
	ExpMonth              int    `json:"expMonth,omitempty"`
	ExpYear               int    `json:"expYear,omitempty"`
	BankName              string `json:"bankName,omitempty"`
	AccountType           string `json:"accountType,omitempty"`
	MakeDefault           bool   `json:"makeDefault"`
}
