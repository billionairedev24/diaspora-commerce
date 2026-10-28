package port

import (
	"context"
	"time"
)

// UpClaimEvidence is trust-ops' Evidence.
type UpClaimEvidence struct {
	AssetID string `json:"assetId"`
	Party   string `json:"party"`
}

// UpClaim is trust-ops' ClaimResponse (the members the customer side reads).
type UpClaim struct {
	ID           string            `json:"id"`
	Reference    string            `json:"reference"`
	Type         string            `json:"type"`
	Status       string            `json:"status"`
	OrderID      string            `json:"orderId"`
	OrderNumber  string            `json:"orderNumber"`
	CustomerID   string            `json:"customerId"`
	AmountMinor  int64             `json:"amountMinor"`
	Currency     string            `json:"currency"`
	Description  string            `json:"description"`
	Evidence     []UpClaimEvidence `json:"evidence"`
	Outcome      string            `json:"outcome"`
	RefundMinor  *int64            `json:"refundMinor"`
	DecidedAt    string            `json:"decidedAt"`
	DecisionNote string            `json:"decisionNote"`
	OpenedAt     string            `json:"openedAt"`
	UpdatedAt    string            `json:"updatedAt"`
}

// UpOpenClaim is trust-ops' OpenClaimRequest as the BFF sends it for a customer.
type UpOpenClaim struct {
	Type             string   `json:"type"`
	OrderID          string   `json:"orderId"`
	OrderNumber      string   `json:"orderNumber"`
	ShipmentID       string   `json:"shipmentId,omitempty"`
	SellerOrgID      string   `json:"sellerOrgId,omitempty"`
	AmountMinor      int64    `json:"amountMinor"`
	Currency         string   `json:"currency"`
	Description      string   `json:"description"`
	EvidenceAssetIDs []string `json:"evidenceAssetIds"`
}

// HelpRequestsClient calls trust-ops' customer claim routes with the customer's token.
type HelpRequestsClient interface {
	Open(ctx context.Context, bearer string, req UpOpenClaim) (UpClaim, error)
	// Mine lists the customer's claims, newest first (page is 0-based).
	Mine(ctx context.Context, bearer string, page, size int) ([]UpClaim, bool, error)
	Get(ctx context.Context, bearer, id string) (UpClaim, error)
}

// ReceiptStore remembers small facts with a TTL: which evidence uploads a customer made and which help request an
// Idempotency-Key already created (trust-ops has no idempotency of its own).
type ReceiptStore interface {
	// Recall returns ok=false when the key is unknown or expired.
	Recall(ctx context.Context, key string) (value string, ok bool, err error)
	Remember(ctx context.Context, key, value string, ttl time.Duration) error
}
