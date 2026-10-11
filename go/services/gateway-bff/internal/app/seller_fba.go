package app

import (
	"context"
	"net/http"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerFbaService is Seller Center's "Send to AfroTransact" (contracts/openapi/gateway-bff-seller.yaml, tag Inventory
// & FBA; S281): inbound plans with the packing-list batch, labels, tracking, receiving progress, and the seller's
// lots with expiry. It mirrors inventory-service with the member's token for the active organisation only: lists are
// always filtered to it and ownerOrg is never taken from the browser (inventory checks the token's org again).
// Reading needs the Inventory module at View, changing plans Edit.
type SellerFbaService interface {
	Relay(ctx context.Context, c *domain.Caller, r OpsRequest) (any, error)
	Stream(ctx context.Context, c *domain.Caller, r OpsRequest) (port.StaffStream, error)
}

type sellerFbaImpl struct{ r *orgResolver }

var _ SellerFbaService = (*sellerFbaImpl)(nil)

// NewSellerFbaService returns the seller FBA use case.
func NewSellerFbaService(d SellerDeps) SellerFbaService {
	return &sellerFbaImpl{r: &orgResolver{d: d}}
}

const areaFba = "fba"

// fbaOwned marks routes whose list is filtered to the active organisation.
var fbaOwned = map[string]bool{"/inbound": true, "/lots": true, "/inventory": true, "/lots/markdown": true}

var fbaRoutes = []opsRoute{
	route(areaFba, opGet, "/nodes", "/v1/nodes", lvView),
	route(areaFba, opGet, "/inventory", "/v1/fba/inventory", lvView, withPage("nodeId", "sku", "stranded")...),
	route(areaFba, opGet, "/inbound", "/v1/inbound/shipments", lvView, withPage("nodeId", "status", "sku")...),
	route(areaFba, opPost, "/inbound", "/v1/inbound/shipments", lvEdit),
	route(areaFba, opGet, "/inbound/{id}", "/v1/inbound/shipments/{id}", lvView),
	route(areaFba, opPut, "/inbound/{id}", "/v1/inbound/shipments/{id}", lvEdit),
	route(areaFba, opPut, "/inbound/{id}/tracking", "/v1/inbound/shipments/{id}/tracking", lvEdit),
	route(areaFba, opPost, "/inbound/{id}/ship", "/v1/inbound/shipments/{id}/ship", lvEdit),
	route(areaFba, opPost, "/inbound/{id}/cancel", "/v1/inbound/shipments/{id}/cancel", lvEdit),
	streamRoute(areaFba, "/inbound/{id}/labels", "/v1/inbound/shipments/{id}/labels", "kind", "format", "lineNo"),
	route(areaFba, opGet, "/lots", "/v1/lots", lvView, withPage("sku", "nodeId", "status", "lotNo")...),
	route(areaFba, opGet, "/lots/markdown", "/v1/lots/markdown", lvView, "within"),
	route(areaFba, opGet, "/lots/{id}", "/v1/lots/{id}", lvView),
}

func matchIn(table []opsRoute, r OpsRequest) (opsRoute, string, bool) {
	for _, rt := range table {
		if rt.area != r.Area || rt.method != r.Method {
			continue
		}
		if m := rt.pattern.FindStringSubmatch(r.Path); m != nil {
			return rt, expand(rt, m), true
		}
	}
	return opsRoute{}, "", false
}

func (s *sellerFbaImpl) prepare(ctx context.Context, c *domain.Caller, r OpsRequest) (opsRoute, port.StaffCall, error) {
	r.Area = areaFba
	rt, target, ok := matchIn(fbaRoutes, r)
	if !ok {
		return rt, port.StaffCall{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such FBA route.")
	}
	q, err := passQuery(r.Query, rt.query...)
	if err != nil {
		return rt, port.StaffCall{}, err
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "inventory", rt.level)
	if err != nil {
		return rt, port.StaffCall{}, err
	}
	if s.r.d.InventoryAPI == nil {
		return rt, port.StaffCall{}, domain.UpstreamUnavailable("inventory")
	}
	if r.Method == http.MethodGet && fbaOwned[r.Path] {
		q.Set("ownerOrg", sc.org.ID)
	}
	call := port.StaffCall{Method: r.Method, Path: target, Query: q, IfMatch: r.IfMatch, IdempotencyKey: r.Idem}
	if r.Method != http.MethodGet {
		body := map[string]any{}
		for k, v := range r.Body {
			if !strings.EqualFold(k, "ownerOrg") {
				body[k] = v
			}
		}
		call.Body = body
	}
	return rt, call, nil
}

func (s *sellerFbaImpl) Relay(ctx context.Context, c *domain.Caller, r OpsRequest) (any, error) {
	rt, call, err := s.prepare(ctx, c, r)
	if err != nil {
		return nil, err
	}
	if rt.stream {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such FBA route.")
	}
	var out any
	if err := s.r.d.InventoryAPI.Call(ctx, c.BearerToken, call, &out); err != nil {
		return nil, mapErr("inventory", err)
	}
	if list, ok := out.([]any); ok {
		return map[string]any{"items": list}, nil
	}
	if out == nil {
		return map[string]any{}, nil
	}
	return out, nil
}

func (s *sellerFbaImpl) Stream(ctx context.Context, c *domain.Caller, r OpsRequest) (port.StaffStream, error) {
	rt, call, err := s.prepare(ctx, c, r)
	if err != nil {
		return port.StaffStream{}, err
	}
	st, ok := s.r.d.InventoryAPI.(port.StaffStreamer)
	if !rt.stream || !ok {
		return port.StaffStream{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such FBA route.")
	}
	call.Accept = "application/pdf, text/plain;q=0.9, */*;q=0.5"
	out, err := st.Stream(ctx, c.BearerToken, call)
	if err != nil {
		return port.StaffStream{}, mapErr("inventory", err)
	}
	if out.ContentType == "" {
		out.ContentType = "application/pdf"
	}
	if out.ContentDisposition == "" {
		out.ContentDisposition = "inline"
	}
	return out, nil
}
