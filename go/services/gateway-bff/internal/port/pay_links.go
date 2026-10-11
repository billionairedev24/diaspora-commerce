package port

import "context"

// UpPayLink is orders' PayLinkView (POST /v1/service/pay-links/{orderNumber}/lookup|confirm).
type UpPayLink struct {
	OrderNumber      string  `json:"orderNumber"`
	Status           string  `json:"status"`
	TotalMinor       int64   `json:"totalMinor"`
	DeliveryFeeMinor int64   `json:"deliveryFeeMinor"`
	Currency         string  `json:"currency"`
	ClientSecret     *string `json:"clientSecret"`
	FailureMessage   *string `json:"failureMessage"`
	ExpiresAt        *string `json:"expiresAt"`
	WindowName       *string `json:"windowName"`
	DeliversOn       *string `json:"deliversOn"`
	Fulfilment       *string `json:"fulfilment"`
	Lines            []struct {
		Title      string `json:"title"`
		Quantity   int    `json:"quantity"`
		TotalMinor int64  `json:"totalMinor"`
	} `json:"lines"`
}

// PayLinkClient resolves late pre-order pay-now links at orders with the BFF's service token (scope orders:pay-link).
// The token travels in the request body only.
type PayLinkClient interface {
	Lookup(ctx context.Context, orderNumber, token string) (UpPayLink, error)
	Confirm(ctx context.Context, orderNumber, token string) (UpPayLink, error)
}
