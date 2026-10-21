package port

import "context"

// UpClaimSLA is trust-ops' SlaResponse.
type UpClaimSLA struct {
	DueAt string `json:"dueAt"`
	State string `json:"state"`
}

// UpClaim is trust-ops' ClaimResponse (a dispute or A-to-z claim).
type UpClaim struct {
	Reference      string      `json:"reference"`
	Type           string      `json:"type"`
	Status         string      `json:"status"`
	OrderNumber    string      `json:"orderNumber"`
	AmountMinor    int64       `json:"amountMinor"`
	Description    string      `json:"description"`
	SellerResponse string      `json:"sellerResponse"`
	SLA            *UpClaimSLA `json:"sla"`
	Escalated      bool        `json:"escalated"`
	Outcome        string      `json:"outcome"`
	RefundMinor    int64       `json:"refundMinor"`
	OpenedAt       string      `json:"openedAt"`
}

// SellerCasesClient reads the returns and disputes of the seller's organisation with the member's own token (tax-returns
// and trust-ops scope them to the organisation and authorise again).
type SellerCasesClient interface {
	// Returns is tax-returns GET /v1/returns for a seller: its organisation's returns, optionally one status.
	Returns(ctx context.Context, bearer, status string) ([]UpRma, error)
	// Claims is trust-ops GET /v1/claims/seller/{orgId}, optionally one status.
	Claims(ctx context.Context, bearer, orgID, status string) ([]UpClaim, error)
}
