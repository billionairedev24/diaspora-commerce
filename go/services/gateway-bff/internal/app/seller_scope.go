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

// SellerScopeService is which catalog departments the organisation may sell in (docs/engineering/seller-catalog-scope.md).
// seller-service records them (from the departments chosen at onboarding) and catalog-service enforces them; the BFF
// relays the scope for Seller Center's pickers and navigation, lists the departments onboarding offers, and files
// "Request another department" for the active organisation. (Staff decide requests and override departments on
// seller-service directly: docs/engineering/seller-catalog-scope.md.)
type SellerScopeService interface {
	// Scope is catalog's GET /v1/seller/catalog-scope: departments (id, code, name, hasSizing), hasSizing, requests.
	Scope(ctx context.Context, c *domain.Caller) (json.RawMessage, error)
	// Departments are the catalog's departments onboarding offers, with the evidence each asks for.
	Departments(ctx context.Context, c *domain.Caller) (json.RawMessage, error)
	// RequestDepartment files a request to sell in another department for the active organisation.
	RequestDepartment(ctx context.Context, c *domain.Caller, body json.RawMessage) (json.RawMessage, error)
}

type sellerScopeImpl struct{ r *orgResolver }

var _ SellerScopeService = (*sellerScopeImpl)(nil)

// NewSellerScopeService returns the SellerScopeService; d.ScopeCatalog and d.ScopeSeller are the catalog and seller
// upstreams (nil answers 503).
func NewSellerScopeService(d SellerDeps) SellerScopeService {
	return &sellerScopeImpl{r: newResolver(d)}
}

// MaxScopeBodyBytes bounds the small JSON bodies of the scope routes.
const MaxScopeBodyBytes = 8 << 10

func (s *sellerScopeImpl) relay(ctx context.Context, up port.StaffUpstream, name, bearer string, call port.StaffCall) (json.RawMessage, error) {
	if up == nil {
		return nil, domain.UpstreamUnavailable(name)
	}
	var out json.RawMessage
	if err := up.Call(ctx, bearer, call, &out); err != nil {
		return nil, mapErr(name, err)
	}
	return out, nil
}

func (s *sellerScopeImpl) Scope(ctx context.Context, c *domain.Caller) (json.RawMessage, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "", ""); err != nil {
		return nil, err
	}
	return s.relay(ctx, s.r.d.ScopeCatalog, "catalog", c.BearerToken, port.StaffCall{Method: http.MethodGet, Path: "/v1/seller/catalog-scope"})
}

func (s *sellerScopeImpl) Departments(ctx context.Context, c *domain.Caller) (json.RawMessage, error) {
	if err := requireCustomer(c); err != nil {
		return nil, err
	}
	return s.relay(ctx, s.r.d.ScopeSeller, "seller", c.BearerToken, port.StaffCall{Method: http.MethodGet, Path: "/v1/departments"})
}

var departmentCodeRe = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,118}$`)

func (s *sellerScopeImpl) RequestDepartment(ctx context.Context, c *domain.Caller, body json.RawMessage) (json.RawMessage, error) {
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit)
	if err != nil {
		return nil, err
	}
	var in struct {
		DepartmentCode string `json:"departmentCode"`
		Note           string `json:"note"`
	}
	if len(body) > MaxScopeBodyBytes || json.Unmarshal(body, &in) != nil {
		return nil, domain.ValidationProblem(map[string]string{"body": "Send the department and an optional note"})
	}
	in.DepartmentCode = strings.ToLower(strings.TrimSpace(in.DepartmentCode))
	in.Note = strings.TrimSpace(in.Note)
	errs := map[string]string{}
	if !departmentCodeRe.MatchString(in.DepartmentCode) {
		errs["departmentCode"] = "Choose a department"
	}
	if len([]rune(in.Note)) > 500 {
		errs["note"] = "At most 500 characters"
	}
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	return s.relay(ctx, s.r.d.ScopeSeller, "seller", c.BearerToken, port.StaffCall{Method: http.MethodPost,
		Path: "/v1/orgs/" + url.PathEscape(sc.org.ID) + "/department-requests", Body: in})
}
