// Package port declares the indexer's outbound interfaces.
package port

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
)

// ProductIndex writes the search read model. Every write is idempotent and guarded by version so
// redelivered or out-of-order events never overwrite newer data.
type ProductIndex interface {
	UpsertProduct(ctx context.Context, market string, doc searchindex.ProductDocument, version int64,
		opts UpsertOptions) error
	// ApplyAvailability sets one variant×node quantity and recomputes the product's totals.
	// It returns false when no product carries the variant yet.
	ApplyAvailability(ctx context.Context, market, variantATIN, node string, qty int, version int64) (bool, error)
	// RenameSeller sets seller_name on every document of the seller and returns how many changed.
	RenameSeller(ctx context.Context, market, sellerID, name string) (int, error)
	Ping(ctx context.Context) error
}

// UpsertOptions tunes one product upsert.
type UpsertOptions struct {
	// PreserveSellerName keeps an already indexed seller_name (the directory only knew a fallback name).
	PreserveSellerName bool
}

// CatalogReader resolves category paths from the catalog service.
type CatalogReader interface {
	Category(ctx context.Context, categoryID string) (domain.Category, error)
}

// SellerDirectory resolves seller display names. Names learned from seller.org.* events are remembered.
type SellerDirectory interface {
	// DisplayName returns the seller's name and whether it is known (false: the fallback name was returned).
	DisplayName(ctx context.Context, orgID string) (string, bool, error)
	// Remember records a name published by seller-service.
	Remember(ctx context.Context, orgID, name string)
}

// SellerSource reads organisations from seller-service (client-credentials token of client indexer-service).
type SellerSource interface {
	// Directory returns one page (from 0) of every organisation's id, display name, kind and status.
	Directory(ctx context.Context, page, size int) (domain.SellerDirectoryPage, error)
	// Org returns one organisation's public profile; found is false when seller-service does not know it.
	Org(ctx context.Context, orgID string) (org domain.SellerOrg, found bool, err error)
}
