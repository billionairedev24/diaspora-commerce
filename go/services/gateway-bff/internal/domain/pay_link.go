package domain

// PayLink is the storefront pay page of a late pre-order add (/orders/{orderNumber}/pay?token=…): the customer pays
// the order's existing PaymentIntent with Stripe Elements on AfroTransact's own page, never a Stripe-hosted one.
type PayLink struct {
	OrderNumber      string        `json:"orderNumber"`
	Status           string        `json:"status"` // awaiting_payment | paid
	TotalMinor       int64         `json:"totalMinor"`
	DeliveryFeeMinor int64         `json:"deliveryFeeMinor"`
	Currency         string        `json:"currency"`
	ClientSecret     *string       `json:"clientSecret"`
	PublishableKey   string        `json:"publishableKey"`
	FailureMessage   *string       `json:"failureMessage"`
	ExpiresAt        *string       `json:"expiresAt"`
	WindowName       *string       `json:"windowName"`
	DeliversOn       *string       `json:"deliversOn"`
	Fulfilment       *string       `json:"fulfilment"`
	Lines            []PayLinkLine `json:"lines"`
}

// PayLinkLine is one line of a pay-now order.
type PayLinkLine struct {
	Title      string `json:"title"`
	Quantity   int    `json:"quantity"`
	TotalMinor int64  `json:"totalMinor"`
}

// Pay-now link problem codes.
const (
	CodePayLinkNotFound = "PAY_LINK_NOT_FOUND"
	CodePayLinkExpired  = "PAY_LINK_EXPIRED"
)
