package app

import (
	"context"
	"encoding/json"
	"net/http"
	"regexp"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerProductTaxService is the seller's view of the tax publish gate: what a product still needs before it can go live
// (the tax decision per variant, in plain words) and the proof files (label photo, Drug Facts) of exemption facts.
// catalog-service owns the gate and scopes products and variants to the token's organisation; the BFF checks the
// Listings module, validates ids, file sizes and types cheaply and relays catalog's JSON. Evidence links are
// rewritten to the BFF's own download route so the browser never needs catalog's address.
type SellerProductTaxService interface {
	Readiness(ctx context.Context, c *domain.Caller, productID string) (json.RawMessage, error)
	UploadEvidence(ctx context.Context, c *domain.Caller, atin, factID string, f port.UploadFile) (json.RawMessage, error)
	ListEvidence(ctx context.Context, c *domain.Caller, atin string) (json.RawMessage, error)
	EvidenceFile(ctx context.Context, c *domain.Caller, id string) (domain.FileDownload, error)
}

// MaxTaxEvidenceBytes is catalog's limit for an evidence file (10 MB).
const MaxTaxEvidenceBytes = 10 << 20

type sellerProductTaxImpl struct{ r *orgResolver }

var _ SellerProductTaxService = (*sellerProductTaxImpl)(nil)

// NewSellerProductTaxService returns the SellerProductTaxService; d.ProductTax is catalog's client (nil answers 503).
func NewSellerProductTaxService(d SellerDeps) SellerProductTaxService { return &sellerProductTaxImpl{r: newResolver(d)} }

var (
	taxUUIDRe = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
	taxATINRe = regexp.MustCompile(`^[A-Za-z0-9_-]{3,40}$`)
	taxFactRe = regexp.MustCompile(`^[a-z][a-z0-9_]{1,60}$`)
	// catalogEvidenceURL is the link catalog puts on an evidence record.
	catalogEvidenceURL = regexp.MustCompile(`"/v1/tax-evidence/`)
)

func taxNotFound(what string) error {
	return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such "+what+".")
}

func (s *sellerProductTaxImpl) need(ctx context.Context, c *domain.Caller, level string) (port.SellerTaxClient, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", level); err != nil {
		return nil, err
	}
	if s.r.d.ProductTax == nil {
		return nil, domain.UpstreamUnavailable("catalog")
	}
	return s.r.d.ProductTax, nil
}

// ownLinks points evidence urls at /v1/seller/tax-evidence/{id}/file.
func ownLinks(b json.RawMessage) json.RawMessage {
	return catalogEvidenceURL.ReplaceAll(b, []byte(`"/v1/seller/tax-evidence/`))
}

func (s *sellerProductTaxImpl) Readiness(ctx context.Context, c *domain.Caller, productID string) (json.RawMessage, error) {
	if !taxUUIDRe.MatchString(productID) {
		return nil, taxNotFound("product")
	}
	cl, err := s.need(ctx, c, "view")
	if err != nil {
		return nil, err
	}
	out, err := cl.Readiness(ctx, c.BearerToken, productID)
	return out, mapErr("catalog", err)
}

func (s *sellerProductTaxImpl) UploadEvidence(ctx context.Context, c *domain.Caller, atin, factID string, f port.UploadFile) (json.RawMessage, error) {
	errs := map[string]string{}
	if !taxFactRe.MatchString(factID) {
		errs["factId"] = "Say which question this file proves"
	}
	switch strings.ToLower(f.ContentType) {
	case "application/pdf", "image/jpeg", "image/png":
	default:
		errs["file"] = "Use a PDF, JPEG or PNG file"
	}
	if len(f.Data) == 0 {
		errs["file"] = "Choose a file"
	} else if len(f.Data) > MaxTaxEvidenceBytes {
		errs["file"] = "Files can be up to 10 MB"
	}
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	if !taxATINRe.MatchString(atin) {
		return nil, taxNotFound("variant")
	}
	cl, err := s.need(ctx, c, "edit")
	if err != nil {
		return nil, err
	}
	out, err := cl.UploadEvidence(ctx, c.BearerToken, atin, factID, f)
	if err != nil {
		return nil, mapErr("catalog", err)
	}
	return ownLinks(out), nil
}

func (s *sellerProductTaxImpl) ListEvidence(ctx context.Context, c *domain.Caller, atin string) (json.RawMessage, error) {
	if !taxATINRe.MatchString(atin) {
		return nil, taxNotFound("variant")
	}
	cl, err := s.need(ctx, c, "view")
	if err != nil {
		return nil, err
	}
	out, err := cl.ListEvidence(ctx, c.BearerToken, atin)
	if err != nil {
		return nil, mapErr("catalog", err)
	}
	return ownLinks(out), nil
}

func (s *sellerProductTaxImpl) EvidenceFile(ctx context.Context, c *domain.Caller, id string) (domain.FileDownload, error) {
	if !taxUUIDRe.MatchString(id) {
		return domain.FileDownload{}, taxNotFound("file")
	}
	cl, err := s.need(ctx, c, "view")
	if err != nil {
		return domain.FileDownload{}, err
	}
	f, err := cl.EvidenceFile(ctx, c.BearerToken, id)
	return f, mapErr("catalog", err)
}
