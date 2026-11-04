package domain

import "time"

// ProductExportPage mirrors catalog-service's GET /v1/search-export/products (SearchExportPage): one keyset page of
// products as the catalog.product.* payload the indexer would have received for their current state.
type ProductExportPage struct {
	Items []ProductExportItem `json:"items"`
	// NextAfter is the cursor of the next page; empty on the last page.
	NextAfter string `json:"nextAfter"`
}

// ProductExportItem is one exported product.
type ProductExportItem struct {
	Product ProductEvent `json:"product"`
	// Version is when the product's state last changed. It plays the part of the event's occurred-at time, so an event
	// about a later change always wins over what a reindex writes.
	Version time.Time `json:"version"`
	// Rating is the stored rating; nil when the product never had a published review.
	Rating *ProductExportRating `json:"rating"`
}

// ProductExportRating is a product's rating and when it was last recomputed.
type ProductExportRating struct {
	Average   float64   `json:"average"`
	Count     int       `json:"count"`
	UpdatedAt time.Time `json:"updatedAt"`
}

// ProductExportQuery selects the products of one export page.
type ProductExportQuery struct {
	// After is the cursor of the previous page ("" = from the start).
	After string
	// Statuses limits the products to these catalog statuses (empty = LIVE).
	Statuses []string
	// Since limits the products to those changed at or after it (zero = every product).
	Since time.Time
	Size  int
}

// VariantBuyBox is a variant's current buy box as offers-service publishes it publicly.
type VariantBuyBox struct {
	ATIN string
	// WinnerOfferID is empty when no offer can win ("currently unavailable").
	WinnerOfferID string
	// AvailableQty is the winner's sellable units.
	AvailableQty int
}

// NodeStock is the sellable quantity of one SKU at one inventory node.
type NodeStock struct {
	SKU       string
	Node      string
	Available int
	// Version is inventory's position version of the quantity (0 = unknown).
	Version int64
}
