package port

import (
	"context"
	"encoding/json"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// SellerTaxClient calls catalog's product tax endpoints with the member's token: the tax publish gate of a product
// (`GET /v1/products/{id}/tax-readiness`) and the proof files of exemption facts
// (`/v1/variants/{atin}/tax-evidence`, `/v1/tax-evidence/{id}/file`). catalog owns the rules and the organisation
// scoping; the BFF relays the JSON it answers.
type SellerTaxClient interface {
	Readiness(ctx context.Context, bearer, productID string) (json.RawMessage, error)
	UploadEvidence(ctx context.Context, bearer, atin, factID string, f UploadFile) (json.RawMessage, error)
	ListEvidence(ctx context.Context, bearer, atin string) (json.RawMessage, error)
	EvidenceFile(ctx context.Context, bearer, id string) (domain.FileDownload, error)
}
