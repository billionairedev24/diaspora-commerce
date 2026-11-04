package app

import (
	"context"
	"net/http"
	"net/url"
	"regexp"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerRunPickupsService gives a seller the delivery runs that collect from it (S264): when the driver comes and what
// to pack, per product and per order (contracts/openapi/gateway-bff-seller.yaml, tag Orders). It mirrors delivery-runs'
// `/v1/seller/run-pickups` for the active organisation only: the organisation is never taken from the browser, and
// delivery-runs checks it against the token again. Reading needs the Orders module at View.
type SellerRunPickupsService interface {
	List(ctx context.Context, c *domain.Caller) (domain.SellerRunPickupList, error)
	Get(ctx context.Context, c *domain.Caller, runID string) (domain.SellerRunPickup, error)
}

type sellerRunPickupsImpl struct{ r *orgResolver }

var _ SellerRunPickupsService = (*sellerRunPickupsImpl)(nil)

// NewSellerRunPickupsService returns the SellerRunPickupsService.
func NewSellerRunPickupsService(d SellerDeps) SellerRunPickupsService {
	return &sellerRunPickupsImpl{r: newResolver(d)}
}

var runUUID = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

func (s *sellerRunPickupsImpl) List(ctx context.Context, c *domain.Caller) (domain.SellerRunPickupList, error) {
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelView)
	if err != nil {
		return domain.SellerRunPickupList{}, err
	}
	if s.r.d.RunPickups == nil {
		return domain.SellerRunPickupList{}, domain.UpstreamUnavailable("delivery-runs")
	}
	var up jobj
	call := port.StaffCall{Method: http.MethodGet, Path: "/v1/seller/run-pickups", Query: url.Values{"orgId": {sc.org.ID}}}
	if err := s.r.d.RunPickups.Call(ctx, c.BearerToken, call, &up); err != nil {
		return domain.SellerRunPickupList{}, mapErr("delivery-runs", err)
	}
	out := domain.SellerRunPickupList{Items: []domain.SellerRunPickup{}}
	for _, p := range objs(up, "items") {
		out.Items = append(out.Items, runPickupOf(p))
	}
	return out, nil
}

func (s *sellerRunPickupsImpl) Get(ctx context.Context, c *domain.Caller, runID string) (domain.SellerRunPickup, error) {
	if !runUUID.MatchString(runID) {
		return domain.SellerRunPickup{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such run.")
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelView)
	if err != nil {
		return domain.SellerRunPickup{}, err
	}
	if s.r.d.RunPickups == nil {
		return domain.SellerRunPickup{}, domain.UpstreamUnavailable("delivery-runs")
	}
	var up jobj
	call := port.StaffCall{Method: http.MethodGet, Path: "/v1/seller/run-pickups/" + url.PathEscape(strings.ToLower(runID)), Query: url.Values{"orgId": {sc.org.ID}}}
	if err := s.r.d.RunPickups.Call(ctx, c.BearerToken, call, &up); err != nil {
		return domain.SellerRunPickup{}, mapErr("delivery-runs", err)
	}
	return runPickupOf(up), nil
}

// runPickupOf maps delivery-runs' pick list: a bag is collected once the driver has it (any stage after PENDING).
func runPickupOf(o jobj) domain.SellerRunPickup {
	out := domain.SellerRunPickup{RunID: jstr(o, "runId"), Town: jstr(o, "town"), State: jstr(o, "state"), TimeZone: jstr(o, "timeZone"),
		RunStatus: strings.ToUpper(jstr(o, "runStatus")), WindowStart: timeOf(o, "windowStart"), WindowEnd: timeOf(o, "windowEnd"),
		PickupAt: optTime(jstr(o, "sellerPickupAt")), DropMode: strings.ToUpper(firstNonEmpty(jstr(o, "dropMode"), "DOORSTEP")),
		Orders: int(num(o, "orders")), Units: int(num(o, "units")), CollectedOrders: int(num(o, "collectedOrders")),
		Items: pickItems(objs(o, "items")), Bags: []domain.RunOrderBag{}}
	for _, b := range objs(o, "bags") {
		out.Bags = append(out.Bags, domain.RunOrderBag{OrderNumber: jstr(b, "orderNumber"), Units: int(num(b, "units")),
			Collected: !strings.EqualFold(firstNonEmpty(jstr(b, "stage"), "PENDING"), "PENDING"), Lines: pickItems(objs(b, "lines"))})
	}
	return out
}

func pickItems(in []jobj) []domain.RunPickItem {
	out := make([]domain.RunPickItem, 0, len(in))
	for _, i := range in {
		out = append(out, domain.RunPickItem{ATIN: jstr(i, "atin"), Name: jstr(i, "name"), Units: int(num(i, "units"))})
	}
	return out
}
