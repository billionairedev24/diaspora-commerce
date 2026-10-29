package app

import (
	"context"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// StaffDispatchService is the Seller pickups screen of the dispatch module (contracts/openapi/gateway-bff-staff.yaml,
// tag Dispatch; S255): AfroTransact Delivery shipments the seller packs at its store and an AfroTransact driver
// collects. fulfilment ranks the nearest available drivers and auto-assigns; dispatchers see the suggestions, override
// with a reason, and read the assignment history.
type StaffDispatchService interface {
	Pickups(ctx context.Context, c *domain.Caller, nodeID string) (domain.PickupList, error)
	Suggestions(ctx context.Context, c *domain.Caller, shipmentID string) (domain.DriverSuggestions, error)
	Assign(ctx context.Context, c *domain.Caller, shipmentID string, req domain.PickupAssignRequest) (domain.PickupAssignResult, error)
	Assignments(ctx context.Context, c *domain.Caller, shipmentID string) (domain.PickupAssignmentList, error)
	ShipFromLocation(ctx context.Context, c *domain.Caller, locationID string) (domain.ShipFromLocation, error)
	// Runs lists the delivery runs fulfilment plans (S264): a closed pre-order window's paid orders delivered in one
	// trip, with their progress and, when delivery-runs can be read, their lifecycle.
	Runs(ctx context.Context, c *domain.Caller, status string) (domain.RunDispatchList, error)
	// Run is one run with its routes and every stop (seller pickups before drops).
	Run(ctx context.Context, c *domain.Caller, runID string) (domain.RunDispatch, error)
	// PlanRun plans the routes of the shipments that are not on a live route yet (harmless to repeat).
	PlanRun(ctx context.Context, c *domain.Caller, runID string) (domain.RunDispatch, error)
	// AdvanceRun moves a run to its next step by hand when the drivers' events did not arrive.
	AdvanceRun(ctx context.Context, c *domain.Caller, runID string, req domain.RunAdvanceRequest) (domain.RunDispatch, error)
}

type staffDispatchImpl struct{ r *staffResolver }

var _ StaffDispatchService = (*staffDispatchImpl)(nil)

// NewStaffDispatchService returns the seller-pickups use case.
func NewStaffDispatchService(d StaffDeps) StaffDispatchService {
	return &staffDispatchImpl{r: newStaffResolver(d)}
}

const (
	dispatchModule   = "dispatch"
	deliveryDispatch = "delivery:dispatch"
	pickupPoolLimit  = "200"
)

func canAssignPickups(a domain.StaffAccess) bool {
	return a.Can(deliveryDispatch) && a.Allows(dispatchModule, domain.LevelEdit)
}

func validShipmentID(id string) error {
	if id == "" || len(id) > 64 || strings.ContainsAny(id, "/?#") {
		return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such pickup.")
	}
	return nil
}

func optTime(s string) *time.Time {
	if s == "" {
		return nil
	}
	t, err := time.Parse(time.RFC3339, s)
	if err != nil {
		return nil
	}
	return &t
}

func optNum(o jobj, key string) *float64 {
	v, ok := o[key].(float64)
	if !ok {
		return nil
	}
	return &v
}

func (s *staffDispatchImpl) Pickups(ctx context.Context, c *domain.Caller, nodeID string) (domain.PickupList, error) {
	if len(nodeID) > 64 {
		return domain.PickupList{}, domain.ValidationProblem(map[string]string{"nodeId": "Too long"})
	}
	sc, err := s.r.require(ctx, c, dispatchModule, domain.LevelView)
	if err != nil {
		return domain.PickupList{}, err
	}
	q := url.Values{"limit": {pickupPoolLimit}}
	if nodeID != "" {
		q.Set("nodeId", nodeID)
	}
	var pool []jobj
	if err := sc.get(ctx, sc.up.Fulfilment, "/v1/dispatch/shipments", q, &pool); err != nil {
		return domain.PickupList{}, err
	}
	out := domain.PickupList{Items: []domain.PickupRow{}, CanAssign: canAssignPickups(sc.access)}
	nameAll(ctx, sc, pool, sellerRef("sellerOrgId").named("sellerName"))
	for _, p := range pool {
		if !strings.EqualFold(jstr(p, "origin"), "SELLER") {
			continue
		}
		row := domain.PickupRow{ShipmentID: jstr(p, "shipmentId"), OrderNumber: jstr(p, "orderNumber"), SellerOrgID: jstr(p, "sellerOrgId"),
			SellerName: jstr(p, "sellerName"), City: jstr(p, "city"), State: jstr(p, "state"),
			PostalCode: jstr(p, "postalCode"), Status: strings.ToUpper(firstNonEmpty(jstr(p, "status"), "AWAITING_ROUTE")), RouteID: strPtr(jstr(p, "routeId")),
			Perishable: boolean(p, "perishable"), Units: int(num(p, "units")), PromisedBy: strPtr(jstr(p, "promisedBy")), CreatedAt: optTime(jstr(p, "createdAt"))}
		if row.ShipmentID != "" {
			out.Items = append(out.Items, row)
		}
	}
	sort.SliceStable(out.Items, func(i, j int) bool {
		a, b := out.Items[i], out.Items[j]
		if (a.PromisedBy == nil) != (b.PromisedBy == nil) {
			return a.PromisedBy != nil
		}
		if a.PromisedBy != nil && *a.PromisedBy != *b.PromisedBy {
			return *a.PromisedBy < *b.PromisedBy
		}
		return a.ShipmentID < b.ShipmentID
	})
	return out, nil
}

func (s *staffDispatchImpl) Suggestions(ctx context.Context, c *domain.Caller, shipmentID string) (domain.DriverSuggestions, error) {
	if err := validShipmentID(shipmentID); err != nil {
		return domain.DriverSuggestions{}, err
	}
	sc, err := s.r.require(ctx, c, dispatchModule, domain.LevelView)
	if err != nil {
		return domain.DriverSuggestions{}, err
	}
	var up jobj
	if err := sc.get(ctx, sc.up.Fulfilment, "/v1/dispatch/pickups/"+url.PathEscape(shipmentID)+"/driver-suggestions", nil, &up); err != nil {
		return domain.DriverSuggestions{}, err
	}
	out := domain.DriverSuggestions{ShipmentID: firstNonEmpty(jstr(up, "shipmentId"), shipmentID), Drivers: []domain.DriverSuggestion{},
		CanAssign: canAssignPickups(sc.access)}
	drivers := objs(up, "drivers")
	if len(drivers) > 5 {
		drivers = drivers[:5]
	}
	nameAll(ctx, sc, drivers, driverRef("driverId"), routeRef("routeId"))
	for _, d := range drivers {
		out.Drivers = append(out.Drivers, domain.DriverSuggestion{DriverID: jstr(d, "driverId"), DriverName: jstr(d, "driverName"), RouteID: jstr(d, "routeId"),
			RouteName: firstNonEmpty(jstr(d, "routeName"), "Unknown route"), Zone: jstr(d, "zone"), RouteStatus: strings.ToUpper(jstr(d, "routeStatus")),
			StraightLineMiles: num(d, "straightLineMiles"), RoadMiles: optNum(d, "roadMiles"), DurationMinutes: optNum(d, "durationMinutes"),
			DistanceMethod: strings.ToUpper(firstNonEmpty(jstr(d, "distanceMethod"), "STRAIGHT_LINE")), PositionAt: optTime(jstr(d, "positionAt")),
			PackagesOnRoute: int(num(d, "packagesOnRoute")), CapacityLeft: int(num(d, "capacityLeft"))})
	}
	return out, nil
}

func (s *staffDispatchImpl) Assign(ctx context.Context, c *domain.Caller, shipmentID string, req domain.PickupAssignRequest) (domain.PickupAssignResult, error) {
	if err := validShipmentID(shipmentID); err != nil {
		return domain.PickupAssignResult{}, err
	}
	errs := map[string]string{}
	route, reason := strings.TrimSpace(req.RouteID), strings.TrimSpace(req.Reason)
	if route == "" || len(route) > 20 {
		errs["routeId"] = "Choose a route"
	}
	if n := len([]rune(reason)); n < 5 || n > 500 {
		errs["reason"] = "Say why you assign this route (5 to 500 characters)"
	}
	if len(errs) > 0 {
		return domain.PickupAssignResult{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, dispatchModule, domain.LevelEdit)
	if err != nil {
		return domain.PickupAssignResult{}, err
	}
	if !sc.access.Can(deliveryDispatch) {
		return domain.PickupAssignResult{}, needsPermission(deliveryDispatch)
	}
	var up jobj
	if err := sc.post(ctx, sc.up.Fulfilment, "/v1/dispatch/pickups/"+url.PathEscape(shipmentID)+"/assign", jobj{"routeId": route, "reason": reason}, &up); err != nil {
		if p, ok := domain.AsProblem(err); ok && p.Status == http.StatusConflict {
			return domain.PickupAssignResult{}, domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Can't move this pickup",
				"The pickup was already collected, or its drop-off carries other shipments.").WithCause(err)
		}
		return domain.PickupAssignResult{}, err
	}
	nameAll(ctx, sc, []jobj{up}, driverRef("driverId"))
	return domain.PickupAssignResult{ShipmentID: shipmentID, RouteID: firstNonEmpty(jstr(up, "id"), jstr(up, "routeId"), route),
		RouteName: strPtr(firstNonEmpty(jstr(up, "name"), jstr(up, "routeName"))), DriverID: strPtr(jstr(up, "driverId")), DriverName: strPtr(jstr(up, "driverName")),
		Status: strings.ToUpper(firstNonEmpty(jstr(up, "status"), "ASSIGNED"))}, nil
}

func (s *staffDispatchImpl) Assignments(ctx context.Context, c *domain.Caller, shipmentID string) (domain.PickupAssignmentList, error) {
	if err := validShipmentID(shipmentID); err != nil {
		return domain.PickupAssignmentList{}, err
	}
	sc, err := s.r.require(ctx, c, dispatchModule, domain.LevelView)
	if err != nil {
		return domain.PickupAssignmentList{}, err
	}
	var up []jobj
	if err := sc.get(ctx, sc.up.Fulfilment, "/v1/dispatch/pickups/"+url.PathEscape(shipmentID)+"/assignments", nil, &up); err != nil {
		return domain.PickupAssignmentList{}, err
	}
	out := domain.PickupAssignmentList{Items: []domain.PickupAssignment{}}
	nameAll(ctx, sc, up, driverRef("driverId"), routeRef("routeId"), routeRef("previousRouteId"), personRef("actor", "Assigned by"))
	for _, a := range up {
		mode := strings.ToUpper(jstr(a, "mode"))
		if mode != "DISPATCH_OVERRIDE" {
			mode = "AUTO_NEAREST"
		}
		out.Items = append(out.Items, domain.PickupAssignment{ID: jstr(a, "id"), ShipmentID: firstNonEmpty(jstr(a, "shipmentId"), shipmentID),
			RouteID: jstr(a, "routeId"), RouteName: strPtr(jstr(a, "routeName")), DriverID: strPtr(jstr(a, "driverId")), DriverName: strPtr(jstr(a, "driverName")), Mode: mode, Reason: strPtr(jstr(a, "reason")),
			PreviousRouteID: strPtr(jstr(a, "previousRouteId")), PreviousRouteName: strPtr(jstr(a, "previousRouteName")), StraightMiles: optNum(a, "straightMiles"), RoadMiles: optNum(a, "roadMiles"),
			DistanceMethod: strPtr(jstr(a, "distanceMethod")), Actor: strPtr(jstr(a, "actorName")), CreatedAt: optTime(jstr(a, "createdAt"))})
	}
	sort.SliceStable(out.Items, func(i, j int) bool {
		a, b := out.Items[i].CreatedAt, out.Items[j].CreatedAt
		return a != nil && (b == nil || a.After(*b))
	})
	return out, nil
}

func (s *staffDispatchImpl) ShipFromLocation(ctx context.Context, c *domain.Caller, locationID string) (domain.ShipFromLocation, error) {
	if err := validShipmentID(locationID); err != nil {
		return domain.ShipFromLocation{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such location.")
	}
	sc, err := s.r.require(ctx, c, dispatchModule, domain.LevelView)
	if err != nil {
		return domain.ShipFromLocation{}, err
	}
	var a jobj
	if err := sc.get(ctx, sc.up.Seller, "/v1/ship-from-locations/"+url.PathEscape(locationID), nil, &a); err != nil {
		return domain.ShipFromLocation{}, err
	}
	out := domain.ShipFromLocation{ID: firstNonEmpty(jstr(a, "id"), locationID), OrgID: jstr(a, "orgId"), Label: jstr(a, "label"), Line1: jstr(a, "line1"),
		Line2: jstr(a, "line2"), City: jstr(a, "city"), State: jstr(a, "state"), PostalCode: jstr(a, "postalCode"), Country: firstNonEmpty(jstr(a, "country"), "US"),
		PickupEnabled: boolean(a, "afrotransactPickup.enabled"), PickupHours: []domain.PickupWindow{}, ZoneName: strPtr(jstr(a, "afrotransactPickup.zoneName"))}
	if pos, ok := a["position"].(map[string]any); ok && pos != nil {
		out.Position = &domain.PickupPosition{Lat: num(pos, "lat"), Lng: num(pos, "lng"), Precision: strings.ToUpper(firstNonEmpty(jstr(pos, "precision"), "ADDRESS"))}
	}
	for _, h := range objs(a, "afrotransactPickup.hours") {
		out.PickupHours = append(out.PickupHours, domain.PickupWindow{Day: strings.ToUpper(jstr(h, "day")), Opens: jstr(h, "opens"), Closes: jstr(h, "closes")})
	}
	return out, nil
}
