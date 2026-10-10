// Package domain holds the event payloads the indexer projects into the search read model.
package domain

import "time"

// Event types handled by the projector.
const (
	ProductCreated      = "catalog.product.created"
	ProductApproved     = "catalog.product.approved"
	ProductRejected     = "catalog.product.rejected"
	ProductPaused       = "catalog.product.paused"
	ProductResumed      = "catalog.product.resumed"
	AvailabilityChanged = "inventory.availability.changed"
)

// ProductEvent mirrors catalog-service's ProductEventPayload.
type ProductEvent struct {
	ProductID       string           `json:"productId"`
	ATIN            string           `json:"atin"`
	OwnerKind       string           `json:"ownerKind"`
	OwnerOrgID      string           `json:"ownerOrgId"`
	Status          string           `json:"status"`
	Title           string           `json:"title"`
	CategoryID      string           `json:"categoryId"`
	ProductTypeCode string           `json:"productTypeCode"`
	Variants        []VariantPayload `json:"variants"`
}

// VariantPayload is one variant of a product event.
type VariantPayload struct {
	ATIN       string `json:"atin"`
	SellerSKU  string `json:"sellerSku"`
	GTIN       string `json:"gtin"`
	PriceMinor int64  `json:"priceMinor"`
	Currency   string `json:"currency"`
	Quantity   int    `json:"quantity"`
}

// AvailabilityEvent mirrors inventory-service's inventory.availability.changed payload.
type AvailabilityEvent struct {
	SKU       string `json:"sku"`
	Node      string `json:"nodeId"`
	Available int    `json:"available"`
	Version   int64  `json:"version"`
}

// Category is the resolved department and path for a category id.
type Category struct {
	Department string
	Path       string
}

// Versioned wraps a projection with the ordering version derived from the event's occurred-at time.
type Versioned struct {
	Version int64
	At      time.Time
}

// VersionOf returns a monotonically comparable version for an event timestamp.
func VersionOf(at time.Time) int64 { return at.UnixMicro() }
