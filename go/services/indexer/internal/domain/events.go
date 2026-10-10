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
	ProductHidden       = "catalog.product.hidden"
	ProductRestored     = "catalog.product.restored"
	ProductTakenDown    = "catalog.product.taken_down"
	ProductReinstated   = "catalog.product.reinstated"
	AvailabilityChanged = "inventory.availability.changed"
	SellerOrgApproved   = "seller.org.approved"
	SellerOrgUpdated    = "seller.org.updated"
)

// StatusSellerHidden marks documents of an organisation whose listings seller-service has hidden (compliance
// enforcement). Search only returns LIVE documents, so hidden products drop out of results; catalog's
// catalog.product.restored event brings back the product's real status.
const StatusSellerHidden = "SELLER_HIDDEN"

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
	// SellerHidden is set while the owner's listings are hidden by seller-service compliance enforcement.
	SellerHidden bool `json:"sellerHidden"`
}

// SellerOrgEvent mirrors seller-service's seller.org.approved / seller.org.updated payload (fields the indexer uses).
type SellerOrgEvent struct {
	OrgID       string `json:"orgId"`
	Kind        string `json:"kind"`
	LegalName   string `json:"legalName"`
	DisplayName string `json:"displayName"`
	Status      string `json:"status"`
}

// Name is the public seller name: the display (store) name, else the legal name.
func (e SellerOrgEvent) Name() string {
	if e.DisplayName != "" {
		return e.DisplayName
	}
	return e.LegalName
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

// SellerOrg is one organisation of seller-service's directory (GET /v1/orgs/directory) or public profile
// (GET /v1/orgs/{id}/public).
type SellerOrg struct {
	ID          string `json:"id"`
	DisplayName string `json:"displayName"`
	Kind        string `json:"kind"`
	Status      string `json:"status"`
}

// SellerDirectoryPage is one page of the organisation directory.
type SellerDirectoryPage struct {
	Items []SellerOrg `json:"items"`
	Page  int         `json:"page"`
	Size  int         `json:"size"`
	Total int64       `json:"total"`
}
