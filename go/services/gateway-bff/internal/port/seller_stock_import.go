package port

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// SellerStockImportClient calls inventory's stock CSV import (POST /v1/stock/imports and its siblings, S395) with the
// member's token. ownerOrg is the organisation the BFF resolved for the signed-in member (never a value from the
// request); inventory only honours it for staff and reads the organisation from the token for sellers.
type SellerStockImportClient interface {
	Template(ctx context.Context, bearer, mode string) (domain.FileDownload, error)
	Upload(ctx context.Context, bearer, ownerOrg string, f UploadFile, mode string) (domain.StockImport, error)
	List(ctx context.Context, bearer, ownerOrg string, page, size int) (domain.StockImportPage, error)
	Get(ctx context.Context, bearer, id string) (domain.StockImport, error)
	Rows(ctx context.Context, bearer, id, status, outcome string, page, size int) (domain.StockImportRowPage, error)
	Report(ctx context.Context, bearer, id string) (domain.FileDownload, error)
	Start(ctx context.Context, bearer, id string, validOnly bool) (domain.StockImport, error)
}
