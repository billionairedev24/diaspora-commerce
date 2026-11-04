package port

import (
	"context"
	"encoding/json"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// SizeChartClient calls catalog's size chart endpoints (/v1/size-charts) with the member's token. catalog owns the
// chart rules and the organisation scoping; the BFF relays the JSON it answers.
type SizeChartClient interface {
	// Call relays one JSON call below /v1/size-charts ("" is the collection, "/{id}", "/{id}/duplicate",
	// "/suggestions") and returns catalog's JSON answer.
	Call(ctx context.Context, bearer, method, subpath string, query url.Values, body json.RawMessage) (json.RawMessage, error)
	// ImportTemplate is GET /v1/size-charts/import-template.
	ImportTemplate(ctx context.Context, bearer, productType, productTypeID, format string) (domain.FileDownload, error)
	// Import is POST /v1/size-charts/import (a .xlsx or .csv file of charts); dryRun only reports.
	Import(ctx context.Context, bearer string, f UploadFile, productType, productTypeID string, dryRun bool) (json.RawMessage, error)
}
