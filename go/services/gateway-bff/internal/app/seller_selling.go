package app

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"regexp"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerSellingService is the seller's selling approvals (docs/runbooks/selling-approvals.md): "Sell this product",
// "Apply to sell" or "Not available" for a product (the product form's status chip), the Selling approvals page, and
// applying with documents. seller-service decides everything; the BFF relays for the active organisation and
// validates the shape of what the browser sends.
type SellerSellingService interface {
	// Status is seller's GET /v1/orgs/{org}/selling-status for the facts in q (categoryCode, brand, productTypeCode, material, gtin).
	Status(ctx context.Context, c *domain.Caller, q url.Values) (json.RawMessage, error)
	// Approvals is every scope that needs approval with the organisation's state, notices and capabilities.
	Approvals(ctx context.Context, c *domain.Caller) (json.RawMessage, error)
	// Capabilities is the seller capabilities (food service); the product form shows food service fields only when approved.
	Capabilities(ctx context.Context, c *domain.Caller) (json.RawMessage, error)
	// Applications are the organisation's selling applications, newest first.
	Applications(ctx context.Context, c *domain.Caller) (json.RawMessage, error)
	Application(ctx context.Context, c *domain.Caller, id string) (json.RawMessage, error)
	// Apply files an application with its documents (already uploaded to media).
	Apply(ctx context.Context, c *domain.Caller, body json.RawMessage) (json.RawMessage, error)
	// Resubmit answers a request for information.
	Resubmit(ctx context.Context, c *domain.Caller, id string, body json.RawMessage) (json.RawMessage, error)
}

type sellerSellingImpl struct{ r *orgResolver }

var _ SellerSellingService = (*sellerSellingImpl)(nil)

// NewSellerSellingService returns the SellerSellingService; d.ScopeSeller is the seller upstream (nil answers 503).
func NewSellerSellingService(d SellerDeps) SellerSellingService {
	return &sellerSellingImpl{r: newResolver(d)}
}

// MaxSellingBodyBytes bounds the JSON body of an application (documents are uploaded to media first, not sent here).
const MaxSellingBodyBytes = 64 << 10

var (
	sellingScopeTypes = map[string]bool{"CATEGORY": true, "PRODUCT_TYPE": true, "BRAND": true, "BRAND_IN_CATEGORY": true, "PRODUCT": true, "CAPABILITY": true}
	sellingDocKinds   = map[string]bool{"PURCHASE_INVOICE": true, "PHOTOS_ALL_SIDES": true, "BRAND_AUTHORIZATION": true, "SAFETY_CERTIFICATE": true, "PERMIT_LICENCE": true, "LIABILITY_INSURANCE": true}
	sellingIDRe       = regexp.MustCompile(`^[A-Za-z0-9_-]{1,60}$`)
	sellingDateRe     = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)
)

func (s *sellerSellingImpl) relay(ctx context.Context, c *domain.Caller, level string, call func(org string) port.StaffCall) (json.RawMessage, error) {
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", level)
	if err != nil {
		return nil, err
	}
	up := s.r.d.ScopeSeller
	if up == nil {
		return nil, domain.UpstreamUnavailable("seller")
	}
	var out json.RawMessage
	if err := up.Call(ctx, c.BearerToken, call(url.PathEscape(sc.org.ID)), &out); err != nil {
		return nil, mapErr("seller", err)
	}
	return out, nil
}

func (s *sellerSellingImpl) Status(ctx context.Context, c *domain.Caller, q url.Values) (json.RawMessage, error) {
	v := url.Values{}
	for _, k := range []string{"categoryCode", "brand", "productTypeCode", "material", "gtin"} {
		if val := strings.TrimSpace(q.Get(k)); val != "" {
			if len(val) > 300 {
				return nil, domain.ValidationProblem(map[string]string{k: "Too long"})
			}
			v.Set(k, val)
		}
	}
	return s.relay(ctx, c, domain.LevelView, func(org string) port.StaffCall {
		return port.StaffCall{Method: http.MethodGet, Path: "/v1/orgs/" + org + "/selling-status", Query: v}
	})
}

func (s *sellerSellingImpl) Approvals(ctx context.Context, c *domain.Caller) (json.RawMessage, error) {
	return s.relay(ctx, c, domain.LevelView, func(org string) port.StaffCall {
		return port.StaffCall{Method: http.MethodGet, Path: "/v1/orgs/" + org + "/selling-approvals"}
	})
}

func (s *sellerSellingImpl) Capabilities(ctx context.Context, c *domain.Caller) (json.RawMessage, error) {
	return s.relay(ctx, c, domain.LevelView, func(org string) port.StaffCall {
		return port.StaffCall{Method: http.MethodGet, Path: "/v1/orgs/" + org + "/selling-capabilities"}
	})
}

func (s *sellerSellingImpl) Applications(ctx context.Context, c *domain.Caller) (json.RawMessage, error) {
	return s.relay(ctx, c, domain.LevelView, func(org string) port.StaffCall {
		return port.StaffCall{Method: http.MethodGet, Path: "/v1/orgs/" + org + "/selling-applications"}
	})
}

func (s *sellerSellingImpl) Application(ctx context.Context, c *domain.Caller, id string) (json.RawMessage, error) {
	if !sellingIDRe.MatchString(id) {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "We couldn't find that application.")
	}
	return s.relay(ctx, c, domain.LevelView, func(org string) port.StaffCall {
		return port.StaffCall{Method: http.MethodGet, Path: "/v1/orgs/" + org + "/selling-applications/" + url.PathEscape(id)}
	})
}

// sellingDocument is one uploaded document as the browser sends it.
type sellingDocument struct {
	Kind              string `json:"kind"`
	AssetID           string `json:"assetId"`
	FileName          string `json:"fileName,omitempty"`
	IssuedOn          string `json:"issuedOn,omitempty"`
	ExpiresOn         string `json:"expiresOn,omitempty"`
	NamesAfroTransact bool   `json:"namesAfroTransact,omitempty"`
	Note              string `json:"note,omitempty"`
}

func checkSellingDocuments(docs []sellingDocument, errs map[string]string) {
	if len(docs) > 20 {
		errs["documents"] = "At most 20 documents"
		return
	}
	for _, d := range docs {
		key := "documents." + d.Kind
		switch {
		case !sellingDocKinds[d.Kind]:
			errs["documents"] = "Unknown document type " + d.Kind
		case !sellingIDRe.MatchString(d.AssetID):
			errs[key] = "Upload the file first"
		case d.IssuedOn != "" && !sellingDateRe.MatchString(d.IssuedOn), d.ExpiresOn != "" && !sellingDateRe.MatchString(d.ExpiresOn):
			errs[key] = "Write dates as YYYY-MM-DD"
		}
	}
}

func (s *sellerSellingImpl) Apply(ctx context.Context, c *domain.Caller, body json.RawMessage) (json.RawMessage, error) {
	var in struct {
		ScopeType string            `json:"scopeType"`
		ScopeKey  string            `json:"scopeKey"`
		Note      string            `json:"note"`
		Documents []sellingDocument `json:"documents"`
	}
	if len(body) > MaxSellingBodyBytes || json.Unmarshal(body, &in) != nil {
		return nil, domain.ValidationProblem(map[string]string{"body": "Send what you are applying for and its documents"})
	}
	in.ScopeType, in.ScopeKey, in.Note = strings.ToUpper(strings.TrimSpace(in.ScopeType)), strings.TrimSpace(in.ScopeKey), strings.TrimSpace(in.Note)
	errs := map[string]string{}
	if !sellingScopeTypes[in.ScopeType] {
		errs["scopeType"] = "Choose what you are applying for"
	}
	if in.ScopeKey == "" || len(in.ScopeKey) > 200 {
		errs["scopeKey"] = "Choose what you are applying for"
	}
	if len([]rune(in.Note)) > 1000 {
		errs["note"] = "At most 1000 characters"
	}
	checkSellingDocuments(in.Documents, errs)
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	return s.relay(ctx, c, domain.LevelEdit, func(org string) port.StaffCall {
		return port.StaffCall{Method: http.MethodPost, Path: "/v1/orgs/" + org + "/selling-applications", Body: in}
	})
}

func (s *sellerSellingImpl) Resubmit(ctx context.Context, c *domain.Caller, id string, body json.RawMessage) (json.RawMessage, error) {
	if !sellingIDRe.MatchString(id) {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "We couldn't find that application.")
	}
	var in struct {
		Note      string            `json:"note"`
		Documents []sellingDocument `json:"documents"`
	}
	if len(body) > MaxSellingBodyBytes || json.Unmarshal(body, &in) != nil {
		return nil, domain.ValidationProblem(map[string]string{"body": "Send the documents we asked for"})
	}
	errs := map[string]string{}
	if len([]rune(in.Note)) > 1000 {
		errs["note"] = "At most 1000 characters"
	}
	checkSellingDocuments(in.Documents, errs)
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	return s.relay(ctx, c, domain.LevelEdit, func(org string) port.StaffCall {
		return port.StaffCall{Method: http.MethodPost, Path: "/v1/orgs/" + org + "/selling-applications/" + url.PathEscape(id) + "/resubmit", Body: in}
	})
}
