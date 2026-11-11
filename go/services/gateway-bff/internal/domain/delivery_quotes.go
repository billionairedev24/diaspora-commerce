package domain

import "time"

// QuoteRequest is a shopper's request for a delivery quote and its answer (listing rules D20): "Too far for standard
// delivery. Ask us for a quote". orders-service owns it; the shopper sees only what is meant for them: a plain status,
// the agreed amount and when it ends, never who agreed it or why.
type QuoteRequest struct {
	ID        string `json:"id"`
	Reference string `json:"reference"`
	// Status is REQUESTED, IN_PROGRESS, READY, EXPIRED, DECLINED or USED; StatusLabel is the plain word for it:
	// Requested, Being quoted, Quote ready, Expired, Declined, Used.
	Status      string `json:"status"`
	StatusLabel string `json:"statusLabel"`
	// AmountMinor is the agreed delivery charge (null until the quote is ready); ExpiresAt is when it stops being usable.
	AmountMinor *int64     `json:"amountMinor"`
	Currency    string     `json:"currency"`
	ExpiresAt   *time.Time `json:"expiresAt"`
	// Usable: the quote can be applied at checkout right now.
	Usable    bool                   `json:"usable"`
	Place     string                 `json:"place"`
	Packages  []QuoteRequestPackage `json:"packages"`
	Lines     []QuoteRequestLine    `json:"lines"`
	Contact   string                 `json:"contact"`
	Note      *string                `json:"note"`
	CreatedAt time.Time              `json:"createdAt"`
	UpdatedAt time.Time              `json:"updatedAt"`
	// Message says in plain words where the request stands and what to do next.
	Message string `json:"message"`
}

// QuoteRequestPackage is one package of the cart the quote is for.
type QuoteRequestPackage struct {
	PackageID  string  `json:"packageId"`
	SellerName *string `json:"sellerName"`
	Fulfilment string  `json:"fulfilment"`
	ItemCount  int     `json:"itemCount"`
}

// QuoteRequestLine is one cart line.
type QuoteRequestLine struct {
	VariantAtin    string  `json:"variantAtin"`
	Title          *string `json:"title"`
	PackageID      string  `json:"packageId"`
	Quantity       int     `json:"quantity"`
	UnitPriceMinor int64   `json:"unitPriceMinor"`
}

// NewQuoteRequest is POST /v1/me/delivery-quotes.
type NewQuoteRequest struct {
	// AddressID is a saved address (the street stays in the address book; only city, state and ZIP reach customer care).
	AddressID string `json:"addressId"`
	// Lines optionally limits the quote to part of the cart (default: every line).
	Lines []NewQuoteRequestLine `json:"lines"`
	// Contact is how customer care may reach the shopper: EMAIL (default), PHONE or SMS.
	Contact string `json:"contact"`
	// Note is optional, up to 500 characters.
	Note string `json:"note"`
}

// NewQuoteRequestLine names a cart line (by its variant ATIN) and how many of it the quote is for.
type NewQuoteRequestLine struct {
	VariantAtin string `json:"variantAtin"`
	Quantity    int    `json:"quantity"`
}
