package domain

// Plans & billing in the staff console (S292–S297; contracts/openapi/gateway-bff-staff.yaml, tag Plans & billing):
// AfroTransact's plan catalogue, trials, reward rules, the grants log and per-seller billing, over the seller service.

// PlansModule is the staff console module key of Plans & billing.
const PlansModule = "plans"

// Seller service permissions behind Plans & billing.
const (
	// PermPlansManage edits the plan catalogue, trial campaigns and reward rules, and syncs Stripe.
	PermPlansManage = "plans:manage"
	// PermBillingAdjust credits, gives free days, waives invoices and overrides trials for an organisation.
	PermBillingAdjust = "billing:adjust"
	// PermSellerBilling reads any organisation's plan, invoices and grants.
	PermSellerBilling = "seller:billing"
)

// BillingOrgHit is one organisation found by the per-seller billing search.
type BillingOrgHit struct {
	OrgID  string `json:"orgId"`
	Name   string `json:"name"`
	Kind   string `json:"kind"`
	Status string `json:"status"`
}

// BillingOrgSearch is GET /v1/staff/billing/orgs.
type BillingOrgSearch struct {
	Items []BillingOrgHit `json:"items"`
}
