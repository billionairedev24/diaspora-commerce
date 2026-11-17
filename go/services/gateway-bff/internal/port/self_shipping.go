package port

import "context"

// SelfShippingClient reads the sellers that ship themselves from orders (GET /v1/public/self-shipping, no sign-in): the
// flat per-order charge (minor units) of each seller among the asked organisations that opted in. Orders answers an empty
// list while the staff switch (sellerSelfShippingEnabled, off by default) is off, so the checkout never offers it then.
type SelfShippingClient interface {
	Offered(ctx context.Context, currency string, orgIDs []string) (map[string]int64, error)
}

// SellerSelfShipping is a seller's own setting (orders GET/PUT /v1/seller/self-shipping). Available is the staff switch.
type SellerSelfShipping struct {
	SellerOrgID string `json:"sellerOrgId"`
	Currency    string `json:"currency"`
	Available   bool   `json:"available"`
	Enabled     bool   `json:"enabled"`
	ChargeMinor int64  `json:"chargeMinor"`
	Version     int64  `json:"version"`
}
