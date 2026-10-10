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
	UpsertProduct(ctx context.Context, market string, doc searchindex.ProductDocument, version int64) error
	// ApplyAvailability sets one variant×node quantity and recomputes the product's totals.
	// It returns false when no product carries the variant yet.
	ApplyAvailability(ctx context.Context, market, variantATIN, node string, qty int, version int64) (bool, error)
	Ping(ctx context.Context) error
}

// CatalogReader resolves category paths from the catalog service.
type CatalogReader interface {
	Category(ctx context.Context, categoryID string) (domain.Category, error)
}

// SellerDirectory resolves seller display names.
type SellerDirectory interface {
	DisplayName(ctx context.Context, orgID string) (string, error)
}
